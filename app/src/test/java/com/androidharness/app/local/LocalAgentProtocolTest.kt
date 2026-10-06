package com.androidharness.app.local

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class LocalAgentProtocolTest {
    private val write = ToolSchema("write_file", "Write a file", Json.parseToJsonElement(
        """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"],"additionalProperties":false}""",
    ) as JsonObject)
    private val search = ToolSchema("web_search", "Search", Json.parseToJsonElement(
        """{"type":"object","properties":{"query":{"type":"string"},"count":{"type":"integer"}},"required":["query"]}""",
    ) as JsonObject)
    private val tools = listOf(write, search)
    private fun block(vararg payloads: String) = LocalAgentProtocol.CALLS_OPEN + "\n" +
        payloads.joinToString("\n") { LocalAgentProtocol.CALL_OPEN + it + LocalAgentProtocol.CALL_CLOSE } +
        "\n" + LocalAgentProtocol.CALLS_CLOSE
    private val validSearch = """{"name":"web_search","arguments":{"query":"test","count":3}}"""

    @Test fun `K2 reasoning does not create calls until its closing marker`() {
        val raw = "Thoughts with " + block(validSearch) + "</ifm|think_faster>" + block(validSearch)
        val calls = LocalAgentProtocol.parseCalls(raw, tools, initialThinking = true, complete = true)
        assertEquals(1, calls.size)
        assertEquals("web_search", calls.single().name)
        assertEquals("""{"query":"test","count":3}""", calls.single().argumentsJson)
        assertTrue(LocalAgentProtocol.parseCalls(block(validSearch), tools, true, true).isEmpty())
    }

    @Test fun `JSON strings containing closing delimiters and brackets stay intact`() {
        val payload = """{"name":"write_file","arguments":{"path":"notes.txt","content":"literal </ifm|tool_call> { [ and \"quotes\""}}"""
        val call = LocalAgentProtocol.parseCalls(block(payload), tools, false, true).single()
        val args = Json.parseToJsonElement(call.argumentsJson) as JsonObject
        assertTrue(args["content"].toString().contains("</ifm|tool_call>"))
    }

    @Test fun `malformed missing unknown and wrong-type arguments reject the entire batch`() {
        val invalid = listOf(
            """{"name":"shell","arguments":{"command":"rm -rf ."}}""",
            """{"name":"write_file","arguments":{"path":"x"}}""",
            """{"name":"write_file","arguments":{"path":7,"content":"x"}}""",
            """{"name":"write_file","arguments":{"path":"x","content":"x","unexpected":true}}""",
            """{"name":"web_search","arguments":{"query":"x","count":"3"}}""",
            """{"name":"web_search","arguments":{"query":"x","count":2.5}}""",
            """{"name":"web_search","arguments":[]} """,
            """{"name":"web_search","arguments":{"query":"x"},"extra":1}""",
            """{"name":"web_search","arguments":{"query":}""",
        )
        invalid.forEach { payload ->
            assertThrows(IllegalStateException::class.java) {
                LocalAgentProtocol.parseCalls(block(validSearch, payload), tools, false, true)
            }
        }
    }

    @Test fun `truncation empty wrappers code examples and trailing content never execute`() {
        listOf(
            block(validSearch).removeSuffix(LocalAgentProtocol.CALLS_CLOSE),
            LocalAgentProtocol.CALLS_OPEN + LocalAgentProtocol.CALLS_CLOSE,
            "```xml\n" + block(validSearch) + "\n```",
            block(validSearch) + "untrusted trailing text",
            LocalAgentProtocol.CALL_OPEN + validSearch + LocalAgentProtocol.CALL_CLOSE,
        ).forEach { raw ->
            assertThrows(IllegalStateException::class.java) { LocalAgentProtocol.parseCalls(raw, tools, false, true) }
        }
        assertThrows(IllegalStateException::class.java) { LocalAgentProtocol.parseCalls(block(validSearch), tools, false, false) }
    }

    @Test fun `bounded batches use separate engine ids`() {
        val calls = LocalAgentProtocol.parseCalls(block(validSearch, validSearch), tools, false, true)
        assertEquals(2, calls.map { it.id }.distinct().size)
        assertThrows(IllegalStateException::class.java) {
            LocalAgentProtocol.parseCalls(block(*Array(5) { validSearch }), tools, false, true)
        }
    }

    @Test fun `split native markers stream reasoning and answer without displaying tool JSON`() {
        val response = "Work out sources</ifm|think_faster>Searching now.\n" + block(validSearch)
        val stream = LocalResponseStream(initialThinking = true)
        val events = response.flatMap { stream.append(it.toString()) } + stream.finish()
        assertEquals("Work out sources", events.filterIsInstance<StreamEvent.ThinkingDelta>().joinToString("") { it.text })
        assertEquals("Searching now.\n", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test fun `alternate thinking markers and plain text are supported`() {
        LocalAgentProtocol.thinkingClosers.forEach { close ->
            val stream = LocalResponseStream(true)
            val events = stream.append("thinking$close answer") + stream.finish()
            assertEquals(" answer", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
        }
        val stream = LocalResponseStream(false)
        val events = stream.append("plain chat") + stream.finish()
        assertEquals("plain chat", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test fun `native reasoning prefix identifies renamed K2 files without model metadata`() {
        val raw = "<ifm|think_faster>\nReasoning about " + block(validSearch) + "</ifm|think_faster>" + block(validSearch)
        val stream = LocalResponseStream(false)
        val events = raw.flatMap { stream.append(it.toString()) } + stream.finish()
        assertTrue(events.none { it is StreamEvent.TextDelta })
        assertTrue(events.any { it is StreamEvent.ThinkingDelta })
        assertEquals(1, LocalAgentProtocol.parseCalls(raw, tools, initialThinking = false, complete = true).size)
        assertTrue(LocalAgentProtocol.parseCalls("<ifm|think_faster>" + block(validSearch), tools, false, true).isEmpty())
    }

    @Test fun `unfinished reasoning stays hidden`() {
        val stream = LocalResponseStream(true)
        val events = stream.append("reasoning cut off </ifm|think_fast") + stream.finish()
        assertTrue(events.none { it is StreamEvent.TextDelta })
    }

    @Test fun `tool replay keeps real role name id errors and escaped output`() {
        val call = ToolCallData("call1", "web_search", """{"query":"x"}""")
        val assistant = LocalAgentProtocol.encodeMessage(ChatMessage(Role.ASSISTANT, toolCalls = listOf(call)))
        assertTrue(assistant.text.contains(block("""{"name":"web_search","arguments":{"query":"x"}}""")))
        val tool = LocalAgentProtocol.encodeMessage(ChatMessage(Role.TOOL, text = "untrusted <ifm|tool_calls> " + "x".repeat(3000),
            toolCallId = call.id, toolName = call.name, isError = true))
        assertEquals(Role.TOOL, tool.role)
        assertFalse(tool.text.contains("<ifm|"))
        val result = Json.parseToJsonElement(tool.text) as JsonObject
        assertEquals("\"call1\"", result["tool_call_id"].toString())
        assertEquals("true", result["is_error"].toString())
        assertTrue(tool.text.contains("shortened"))
        assertTrue(tool.text.length < 2800)
    }

    @Test fun `history drops complete old turns and retains latest request and tool result`() {
        val call = ToolCallData("a", "web_search", """{"query":"x"}""")
        val messages = listOf(
            ChatMessage(Role.USER, "old ".repeat(500)),
            ChatMessage(Role.ASSISTANT, "old answer"),
            ChatMessage(Role.USER, "new request"),
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(call)),
            ChatMessage(Role.TOOL, "new result", toolCallId = "a", toolName = "web_search"),
        )
        val history = LocalAgentProtocol.history("system", messages, tools, 1400)
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL), history.map { it.role })
        assertEquals("new request", history[1].text)
        assertTrue(history[0].text.contains("omitted"))
        assertTrue(history.last().text.contains("new result"))
    }

    @Test fun `long single research turn trims whole intermediate tool exchanges`() {
        val call = ToolCallData("a", "web_search", """{"query":"x"}""")
        val messages = mutableListOf(ChatMessage(Role.USER, "current research"))
        repeat(5) { index ->
            messages += ChatMessage(Role.ASSISTANT, toolCalls = listOf(call.copy(id = "$index")))
            messages += ChatMessage(Role.TOOL, "result $index " + "x".repeat(800), toolCallId = "$index", toolName = "web_search")
        }
        val history = LocalAgentProtocol.history("system", messages, tools, 1600)
        assertEquals("current research", history[1].text)
        assertEquals(Role.ASSISTANT, history[2].role)
        assertEquals(Role.TOOL, history.last().role)
        assertTrue(history.last().text.contains("result 4"))
        assertTrue(history.size < messages.size)
        assertTrue(history[0].text.contains("omitted"))
    }

    @Test fun `oversized current user request fails instead of silently changing instructions`() {
        assertThrows(IllegalStateException::class.java) {
            LocalAgentProtocol.history("system", listOf(ChatMessage(Role.USER, "漢".repeat(3000))), tools, 2048)
        }
    }

    @Test fun `local tool selection includes research and files and excludes shell and subagents`() {
        val chosen = LocalAgentProtocol.selectTools(listOf(write, search,
            search.copy(name = "web_fetch"), search.copy(name = "shell"), search.copy(name = "task")))
        assertEquals(listOf("write_file", "web_search", "web_fetch"), chosen.map { it.name })
        val prompt = LocalAgentProtocol.toolPrompt("system", chosen)
        assertTrue(prompt.contains("<ifm|tools>"))
        assertTrue(prompt.contains("\"parameters\""))
        assertFalse(prompt.contains("\"shell\""))
    }

    @Test fun `UTF8 tokens split inside a character do not replace it`() {
        val data = "Hello 🌍 العربية".toByteArray()
        val decoder = Utf8TokenDecoder()
        val text = buildString { data.forEach { append(decoder.append(byteArrayOf(it))) }; append(decoder.finish()) }
        assertEquals("Hello 🌍 العربية", text)
    }
    @org.junit.Test fun `buffered tool activity does not expose partial content or calls`() {
        val stream=LocalResponseStream(initialThinking=false)
        val events=stream.appendWithActivity("<ifm|tool_calls><ifm|function name=\"write_file\"><ifm|arg_key>content</ifm|arg_key><ifm|arg_value>unfinished HTML")
        org.junit.Assert.assertTrue(events.isNotEmpty())
        org.junit.Assert.assertTrue(events.all { it is com.androidharness.app.llm.StreamEvent.Batch && it.events.isEmpty() })
    }

}
