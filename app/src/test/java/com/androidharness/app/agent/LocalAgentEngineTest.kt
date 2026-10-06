package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.browser.HtmlArtifacts
import com.androidharness.app.data.CheckpointStore
import com.androidharness.app.data.ImageStore
import com.androidharness.app.data.db.CheckpointEntity
import com.androidharness.app.data.db.HarnessDao
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShizukuManager
import com.androidharness.app.llm.*
import com.androidharness.app.local.LocalAgentProtocol
import com.androidharness.app.local.LocalModelCatalog
import com.androidharness.app.skills.SkillStore
import com.androidharness.app.tools.ReadFileTool
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.tools.WriteFileTool
import com.androidharness.app.workspace.FileFs
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAgentEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `local agent offers file tools executes them and feeds results into the next model round`() = runBlocking {
        val workspace = FileFs(tmp.root)
        val checkpoints = mutableListOf<CheckpointEntity>()
        val dao = Proxy.newProxyInstance(HarnessDao::class.java.classLoader, arrayOf(HarnessDao::class.java)) { _, method, args ->
            when (method.name) {
                "checkpointsForTurn" -> checkpoints.toList()
                "insertCheckpoint" -> { checkpoints += args[0] as CheckpointEntity; Unit }
                else -> error("Unexpected DAO call ${method.name}")
            }
        } as HarnessDao
        val write = WriteFileTool()
        val read = ReadFileTool()
        val registry = ToolRegistry(listOf(write, read))
        val requests = mutableListOf<List<ChatMessage>>()
        val offered = mutableListOf<List<String>>()
        var lastCallId = ""
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
                requests += messages.toList()
                offered += tools.map { it.name }
                assertTrue(systemPrompt.contains("local Android assistant"))
                assertFalse(systemPrompt.contains("cannot execute tools"))
                val payload = when (requests.size) {
                    1 -> """{"name":"write_file","arguments":{"path":"site/index.html","content":"<html><body>A saved local note.</body></html>"}}"""
                    2 -> {
                        assertEquals(Role.TOOL, messages.last().role)
                        assertEquals(lastCallId, messages.last().toolCallId)
                        assertEquals("write_file", messages.last().toolName)
                        assertFalse(messages.last().isError)
                        assertTrue(messages.last().text.contains("Created site/index.html"))
                        """{"name":"read_file","arguments":{"path":"site/index.html"}}"""
                    }
                    else -> {
                        assertEquals(3, requests.size)
                        assertEquals(Role.TOOL, messages.last().role)
                        assertEquals(lastCallId, messages.last().toolCallId)
                        assertEquals("read_file", messages.last().toolName)
                        assertTrue(messages.last().text.contains("A saved local note."))
                        emit(StreamEvent.TextDelta("Done. Saved and verified site/index.html."))
                        emit(StreamEvent.Done("stop"))
                        return@flow
                    }
                }
                val raw = LocalAgentProtocol.CALLS_OPEN + LocalAgentProtocol.CALL_OPEN + payload +
                    LocalAgentProtocol.CALL_CLOSE + LocalAgentProtocol.CALLS_CLOSE
                val calls = LocalAgentProtocol.parseCalls(raw, tools, initialThinking = false, complete = true)
                lastCallId = calls.single().id
                emit(StreamEvent.ToolCallBatch(calls))
                emit(StreamEvent.Done("tool_calls"))
            }
        }
        val skills = SkillStore(emptyMap(), tmp.newFolder("skills"), { null }, { emptySet() })
        val engine = AgentEngine({ provider }, registry, CheckpointStore(dao), platformStub(ImageStore::class.java),
            platformStub(LinuxEnvironmentManager::class.java), platformStub(ShizukuManager::class.java), skills)
        val id = LocalModelCatalog.PROVIDER_PREFIX + "k2-horizon-37b"
        val events = engine.run("session", "turn", ProviderConfig(id, "Local K2", ProviderType.OPENAI_COMPAT, "local://k2", id),
            "", listOf(ChatMessage(Role.USER, "Create a note in an HTML page and verify it.")), { PermissionMode.FULL_AUTO }, mutableSetOf(),
            workspace, RequestOptions(maxOutputTokens = 1024, assistantMode = AssistantMode.CHAT), 4096, AgentMode.ACT).toList()
        assertEquals(3, requests.size)
        assertTrue(offered.all { it.toSet() == setOf("read_file", "write_file") })
        assertEquals("<html><body>A saved local note.</body></html>\n", tmp.root.resolve("site/index.html").readText())
        assertEquals(1, checkpoints.size)
        assertEquals("site/index.html", checkpoints.single().relPath)
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolFinished>().size)
        assertTrue(events.filterIsInstance<AgentEvent.Text>().any { it.delta == "Done. Saved and verified site/index.html." })
        assertTrue(events.any { it is AgentEvent.Finished })
        assertTrue(events.filterIsInstance<AgentEvent.Error>().isEmpty())
        val committed = events.mapNotNull { event ->
            when (event) {
                is AgentEvent.AssistantCommitted -> event.message
                is AgentEvent.ToolMessageCommitted -> event.message
                else -> null
            }
        }
        assertEquals(listOf("site/index.html"), HtmlArtifacts.existing(committed, workspace).map { it.path })
    }

    @Test fun `local full access cannot escape and unadvertised shell never executes`() = runBlocking {
        val root = tmp.newFolder("agent")
        val outside = tmp.newFile("private.txt").apply { writeText("PRIVATE_SENTINEL") }
        val dao = Proxy.newProxyInstance(HarnessDao::class.java.classLoader, arrayOf(HarnessDao::class.java)) { _, method, _ ->
            if (method.name == "checkpointsForTurn") emptyList<CheckpointEntity>() else error("Unexpected write")
        } as HarnessDao
        var shellExecuted = false
        val shell = object : com.androidharness.app.tools.Tool {
            override val name = "shell"
            override val description = "Unsafe"
            override val parametersSchema = kotlinx.serialization.json.Json.parseToJsonElement("{\"type\":\"object\"}") as kotlinx.serialization.json.JsonObject
            override val isReadOnly = true
            override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: com.androidharness.app.tools.ToolContext): com.androidharness.app.tools.ToolResult {
                shellExecuted = true; return com.androidharness.app.tools.ToolResult(true,"BAD")
            }
        }
        var requests = 0
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow<StreamEvent> {
                assertFalse(tools.any { it.name == "shell" })
                assertFalse(systemPrompt.contains("permits absolute paths"))
                if (requests++ == 0) emit(StreamEvent.ToolCallBatch(listOf(com.androidharness.app.core.ToolCallData("read", "read_file", "{\"path\":\"../private.txt\"}"))))
                else { assertTrue(messages.last().isError); emit(StreamEvent.ToolCallBatch(listOf(com.androidharness.app.core.ToolCallData("shell", "shell", "{}")))) }
                emit(StreamEvent.Done("tool_calls"))
            }
        }
        val skills = SkillStore(emptyMap(), tmp.newFolder("skills2"), {null}, {emptySet()})
        val engine = AgentEngine({provider}, ToolRegistry(listOf(ReadFileTool(),shell)), CheckpointStore(dao), platformStub(ImageStore::class.java), platformStub(LinuxEnvironmentManager::class.java), platformStub(ShizukuManager::class.java), skills)
        val id = LocalModelCatalog.PROVIDER_PREFIX + "k2-horizon-09b"
        val events = engine.run("s","t",ProviderConfig(id,"Local",ProviderType.OPENAI_COMPAT,"local://k2",id),"",listOf(ChatMessage(Role.USER,"Try escaping")),{PermissionMode.FULL_ACCESS},mutableSetOf("shell"),FileFs(root),RequestOptions(assistantMode=AssistantMode.CODE),4096,AgentMode.ACT,extraTools=listOf(shell)).toList()
        assertEquals(2,requests)
        assertFalse(shellExecuted)
        assertEquals("PRIVATE_SENTINEL",outside.readText())
        assertTrue(events.filterIsInstance<AgentEvent.ToolFinished>().single().result.ok == false)
        assertTrue(events.filterIsInstance<AgentEvent.Error>().any { it.message.contains("restricted") })
    }


    @Test fun `invalid local batch can repair without executing rejected calls`() = repairCheck(false)
    @Test fun `permanently invalid local batches stop after two repairs`() = repairCheck(true)
    private fun repairCheck(permanent: Boolean) = runBlocking {
        tmp.newFile("note.txt").writeText("SAFE_NOTE")
        val dao = Proxy.newProxyInstance(HarnessDao::class.java.classLoader,arrayOf(HarnessDao::class.java)) { _,method,_ ->
            if(method.name=="checkpointsForTurn") emptyList<CheckpointEntity>() else error("Unexpected write")
        } as HarnessDao
        var rounds=0
        val provider=object:LlmProvider {
            override fun streamChat(config:ProviderConfig,apiKey:String,systemPrompt:String,messages:List<ChatMessage>,tools:List<ToolSchema>,options:RequestOptions)=flow<StreamEvent> {
                rounds++
                if(permanent || rounds==1) emit(StreamEvent.Failure(com.androidharness.app.local.LocalModelProvider.INVALID_TOOL_CALL_PREFIX+"Unknown argument"))
                else if(rounds==2) {
                    assertTrue(messages.last().text.contains("no tools ran"))
                    emit(StreamEvent.ToolCallBatch(listOf(com.androidharness.app.core.ToolCallData("safe","read_file","{\"path\":\"note.txt\"}"))))
                    emit(StreamEvent.Done("tool_calls"))
                } else {
                    assertTrue(messages.last().text.contains("SAFE_NOTE"))
                    emit(StreamEvent.TextDelta("Done"));emit(StreamEvent.Done("stop"))
                }
            }
        }
        val skills=SkillStore(emptyMap(),tmp.newFolder("skills-repair"),{null},{emptySet()})
        val engine=AgentEngine({provider},ToolRegistry(listOf(ReadFileTool())),CheckpointStore(dao),platformStub(ImageStore::class.java),platformStub(LinuxEnvironmentManager::class.java),platformStub(ShizukuManager::class.java),skills)
        val id=LocalModelCatalog.PROVIDER_PREFIX+"k2-horizon-09b"
        val events=engine.run("s","t",ProviderConfig(id,"Local",ProviderType.OPENAI_COMPAT,"local://k2",id),"",listOf(ChatMessage(Role.USER,"Read my note")),{PermissionMode.FULL_AUTO},mutableSetOf(),FileFs(tmp.root),RequestOptions(assistantMode=AssistantMode.RESEARCH),4096,AgentMode.ACT).toList()
        assertEquals(3,rounds)
        assertEquals(if(permanent)0 else 1,events.filterIsInstance<AgentEvent.ToolFinished>().size)
        assertEquals(permanent,events.any{it is AgentEvent.Error})
        assertEquals(!permanent,events.any{it is AgentEvent.Finished})
        assertEquals("SAFE_NOTE",tmp.root.resolve("note.txt").readText())
    }

    @Test fun `repeated web loop stops without committing orphan tool calls`() = runBlocking {
        var executed=0;var rounds=0
        val tool=object:com.androidharness.app.tools.Tool {
            override val name="web_search"
            override val description="Search"
            override val isReadOnly=true
            override val parametersSchema=kotlinx.serialization.json.Json.parseToJsonElement("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}") as kotlinx.serialization.json.JsonObject
            override suspend fun execute(args:kotlinx.serialization.json.JsonObject,ctx:com.androidharness.app.tools.ToolContext):com.androidharness.app.tools.ToolResult {
                executed++;return com.androidharness.app.tools.ToolResult(true,"https://example.com/result")
            }
        }
        val dao=Proxy.newProxyInstance(HarnessDao::class.java.classLoader,arrayOf(HarnessDao::class.java)) { _,method,_ ->
            if(method.name=="checkpointsForTurn")emptyList<CheckpointEntity>() else error("Unexpected write")
        } as HarnessDao
        val provider=object:LlmProvider {
            override fun streamChat(config:ProviderConfig,apiKey:String,systemPrompt:String,messages:List<ChatMessage>,tools:List<ToolSchema>,options:RequestOptions)=flow<StreamEvent> {
                rounds++
                if(rounds>3)assertTrue(messages.last().text.contains("No tools in this batch ran"))
                emit(StreamEvent.ToolCallBatch(listOf(com.androidharness.app.core.ToolCallData("c$rounds","web_search","{\"query\":\"same query\"}"))))
                emit(StreamEvent.Done("tool_calls"))
            }
        }
        val skills=SkillStore(emptyMap(),tmp.newFolder("loop-skills"),{null},{emptySet()})
        val engine=AgentEngine({provider},ToolRegistry(listOf(tool)),CheckpointStore(dao),platformStub(ImageStore::class.java),platformStub(LinuxEnvironmentManager::class.java),platformStub(ShizukuManager::class.java),skills)
        val id=LocalModelCatalog.PROVIDER_PREFIX+"k2-horizon-09b"
        val events=engine.run("s","t",ProviderConfig(id,"Local",ProviderType.OPENAI_COMPAT,"local://k2",id),"",listOf(ChatMessage(Role.USER,"Research")),{PermissionMode.FULL_AUTO},mutableSetOf(),FileFs(tmp.root),RequestOptions(assistantMode=AssistantMode.RESEARCH),4096,AgentMode.ACT).toList()
        assertEquals(5,rounds);assertEquals(2,executed)
        assertEquals(2,events.filterIsInstance<AgentEvent.AssistantCommitted>().size)
        assertEquals(2,events.filterIsInstance<AgentEvent.ToolFinished>().size)
        assertTrue(events.filterIsInstance<AgentEvent.Error>().single().message.contains("repeated web request"))
        assertFalse(events.any { it is AgentEvent.Finished })
    }

    @Test fun `local vision image bytes reach provider instead of being silently omitted`() = runBlocking {
        val image=com.androidharness.app.core.ImageData("image/png","AQID")
        val dao=Proxy.newProxyInstance(HarnessDao::class.java.classLoader,arrayOf(HarnessDao::class.java)) { _,method,_ ->
            if(method.name=="checkpointsForTurn")emptyList<CheckpointEntity>() else error("Unexpected write")
        } as HarnessDao
        var received=false
        val provider=object:LlmProvider {
            override fun streamChat(config:ProviderConfig,apiKey:String,systemPrompt:String,messages:List<ChatMessage>,tools:List<ToolSchema>,options:RequestOptions)=flow<StreamEvent> {
                assertEquals(listOf(image),messages.last().imageData);received=true
                emit(StreamEvent.TextDelta("Provider received the image."));emit(StreamEvent.Done("stop"))
            }
        }
        val skills=SkillStore(emptyMap(),tmp.newFolder("vision-route-skills"),{null},{emptySet()})
        val engine=AgentEngine({provider},ToolRegistry(emptyList()),CheckpointStore(dao),platformStub(ImageStore::class.java),platformStub(LinuxEnvironmentManager::class.java),platformStub(ShizukuManager::class.java),skills)
        val id=LocalModelCatalog.PROVIDER_PREFIX+"qwen35-2b"
        val events=engine.run("s","t",ProviderConfig(id,"Local vision",ProviderType.OPENAI_COMPAT,"local://qwen",id),"",listOf(ChatMessage(Role.USER,"Describe image",imageData=listOf(image))),{PermissionMode.FULL_AUTO},mutableSetOf(),FileFs(tmp.root),RequestOptions(assistantMode=AssistantMode.CHAT),4096,AgentMode.ACT).toList()
        assertTrue(received);assertTrue(events.any {it is AgentEvent.Finished})
    }

    @Test fun `simple factual chat finishes after a search without extra fetch and reports measured context`() = runBlocking {
        val dao=Proxy.newProxyInstance(HarnessDao::class.java.classLoader,arrayOf(HarnessDao::class.java)) { _,method,_ ->
            if(method.name=="checkpointsForTurn")emptyList<CheckpointEntity>() else error("Unexpected write")
        } as HarnessDao
        var searches=0;var fetches=0;var rounds=0
        fun webTool(name:String)=object:com.androidharness.app.tools.Tool {
            override val name=name
            override val description="Web evidence"
            override val isReadOnly=true
            override val parametersSchema=kotlinx.serialization.json.buildJsonObject { put("type",kotlinx.serialization.json.JsonPrimitive("object")) }
            override suspend fun execute(args:kotlinx.serialization.json.JsonObject,ctx:com.androidharness.app.tools.ToolContext):com.androidharness.app.tools.ToolResult {
                if(name=="web_search")searches++ else fetches++
                return com.androidharness.app.tools.ToolResult(true,"Official date: September 29, 2026. https://example.com/devday")
            }
        }
        val provider=object:LlmProvider {
            override fun streamChat(config:ProviderConfig,apiKey:String,systemPrompt:String,messages:List<ChatMessage>,tools:List<ToolSchema>,options:RequestOptions)=flow<StreamEvent> {
                assertTrue(systemPrompt.contains("then stop"));assertTrue(systemPrompt.contains("wait for consent"))
                rounds++
                if(rounds==1) {emit(StreamEvent.ToolCallBatch(listOf(com.androidharness.app.core.ToolCallData("search","web_search","{}"))));emit(StreamEvent.Done("tool_calls"))}
                else {assertEquals(2,rounds);emit(StreamEvent.TextDelta("September 29, 2026. Would you like more details?"));emit(StreamEvent.Usage(321,17));emit(StreamEvent.Done("stop"))}
            }
        }
        val skills=SkillStore(emptyMap(),tmp.newFolder("concise-skills"),{null},{emptySet()})
        val engine=AgentEngine({provider},ToolRegistry(listOf(webTool("web_search"),webTool("web_fetch"))),CheckpointStore(dao),platformStub(ImageStore::class.java),platformStub(LinuxEnvironmentManager::class.java),platformStub(ShizukuManager::class.java),skills)
        val id=LocalModelCatalog.PROVIDER_PREFIX+"k2-horizon-09b"
        val events=engine.run("s","t",ProviderConfig(id,"Local",ProviderType.OPENAI_COMPAT,"local://k2",id),"",listOf(ChatMessage(Role.USER,"When was DevDay?")),{PermissionMode.FULL_AUTO},mutableSetOf(),FileFs(tmp.root),RequestOptions(assistantMode=AssistantMode.CHAT),28672,AgentMode.ACT).toList()
        assertEquals(2,rounds);assertEquals(1,searches);assertEquals(0,fetches)
        assertTrue(events.any {it is AgentEvent.Finished});assertFalse(events.any {it is AgentEvent.Error})
        val context=events.filterIsInstance<AgentEvent.EstimatedContext>().last().estimate
        assertTrue(context.measured);assertEquals(338,context.total)
    }

    // This local text/file route must never start Android image, shell or Shizuku services.
    // Allocate unused fixtures without their Android constructors; no mocking dependency is needed.
    @Suppress("UNCHECKED_CAST")
    private fun <T> platformStub(type: Class<T>): T {
        val unsafe = Class.forName("sun.misc.Unsafe")
        val field = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return unsafe.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
    }
}
