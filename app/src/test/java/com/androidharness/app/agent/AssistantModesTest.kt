package com.androidharness.app.agent

import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.ToolSchema
import com.androidharness.app.local.LocalAgentProtocol
import com.androidharness.app.tools.WriteFileTool
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AssistantModesTest {
    @Test fun `chat rejects file creation while research and coding offer real file schemas`() {
        val write = WriteFileTool()
        val schema = ToolSchema(write.name, write.description, write.parametersSchema)
        val raw = """<ifm|tool_calls><ifm|tool_call>{"name":"write_file","arguments":{"path":"index.html","content":"<h1>Hello</h1>"}}</ifm|tool_call></ifm|tool_calls>"""
        val chatTools = LocalAgentProtocol.selectTools(listOf(schema), AssistantMode.CHAT)
        assertTrue(chatTools.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            LocalAgentProtocol.parseCalls(raw, chatTools, initialThinking = false, complete = true)
        }
        for (mode in listOf(AssistantMode.RESEARCH, AssistantMode.CODE)) {
            val tools = LocalAgentProtocol.selectTools(listOf(schema), mode)
            assertEquals("write_file", LocalAgentProtocol.parseCalls(raw, tools, false, true).single().name)
        }
    }

    @Test fun `legacy settings default to chat and paused coding tasks retain their mode`() {
        assertEquals(AssistantMode.CHAT, Json.decodeFromString(AppSettings.serializer(), "{}").assistantMode)
        val saved = Json.decodeFromString(TaskRecord.serializer(), """{"status":"paused","assistantMode":"CODE"}""")
        assertEquals(AssistantMode.CODE, saved.assistantMode)
        val restored = Json.decodeFromString(TaskRecord.serializer(), Json.encodeToString(TaskRecord.serializer(), saved))
        assertEquals(AssistantMode.CODE, restored.assistantMode)
        assertTrue(restored.resumable)
    }

    @Test fun `UI reasoning levels map to all three actual publisher profiles`() {
        assertEquals("low", ThinkingLevel.OFF.k2ReasoningEffort())
        assertEquals("low", ThinkingLevel.LOW.k2ReasoningEffort())
        assertEquals("medium", ThinkingLevel.MEDIUM.k2ReasoningEffort())
        assertEquals("high", ThinkingLevel.HIGH.k2ReasoningEffort())
        assertEquals("high", ThinkingLevel.ULTRA.k2ReasoningEffort())
    }
}
