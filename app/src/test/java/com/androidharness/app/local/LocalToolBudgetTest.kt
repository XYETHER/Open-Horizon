package com.androidharness.app.local

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.ToolSchema
import com.androidharness.app.tools.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class LocalToolBudgetTest {
    private fun actualSchemas(): List<ToolSchema> {
        val client = OkHttpClient()
        val actual = listOf(WebSearchTool(client), WebFetchTool(client), ListDirTool(), ReadFileTool(),
            FileInfoTool(), WriteFileTool(), EditFileTool(), CreateDirTool(), AskUserTool())
            .map { ToolSchema(it.name, it.description, it.parametersSchema) }
        return LocalAgentProtocol.selectTools(actual)
    }

    @Test fun `actual research and file schemas leave room for a tool round in 4K context`() {
        val schemas = actualSchemas()
        assertEquals(LocalAgentProtocol.toolNames, schemas.map { it.name }.toSet())
        val prompt = LocalAgentProtocol.systemPrompt("/data/user/0/com.xyether.agent/files/workspace", plan = false, fullAccess = false)
        val user = "Research this topic with reliable sources. " + "research skill instructions ".repeat(32)
        val call = ToolCallData("search1", "web_search", """{"query":"Android local inference","count":3}""")
        val history = LocalAgentProtocol.history(prompt, listOf(
            ChatMessage(Role.USER, user),
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(call)),
            ChatMessage(Role.TOOL, "https://example.org/source\n" + "Useful source evidence. ".repeat(200),
                toolCallId = call.id, toolName = call.name),
        ), schemas, inputTokens = 3072)
        assertEquals(user, history[1].text)
        assertEquals(Role.TOOL, history.last().role)
        assertTrue(history.last().text.contains("https://example.org/source"))
        assertTrue(history.sumOf { it.text.toByteArray().size + 64 } <= 6144)
    }

    @Test fun `minimum 3K context fits basic chat and a short web result with actual tools`() {
        val schemas = actualSchemas()
        val prompt = LocalAgentProtocol.systemPrompt("/data/user/0/com.xyether.agent/files/workspace", plan = false, fullAccess = false)
        val user = ChatMessage(Role.USER, "Search for the Android developer documentation.")
        val first = LocalAgentProtocol.history(prompt, listOf(user), schemas, inputTokens = 2304)
        assertEquals(user.text, first.last().text)
        val call = ToolCallData("search1", "web_search", """{"query":"Android developer documentation","count":3}""")
        val result = "Android Developers\nhttps://developer.android.com/\nOfficial guides for Android apps.\n\n" +
            "Android Studio\nhttps://developer.android.com/studio\nAndroid development tools.\n\n" +
            "Android API reference\nhttps://developer.android.com/reference\nAndroid platform APIs."
        val next = LocalAgentProtocol.history(prompt, listOf(user,
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(call)),
            ChatMessage(Role.TOOL, result, toolCallId = call.id, toolName = call.name)), schemas, inputTokens = 2304)
        assertEquals(user.text, next[1].text)
        assertEquals(Role.TOOL, next.last().role)
        assertTrue(next.last().text.contains("https://developer.android.com/reference"))
        assertTrue(next.sumOf { it.text.toByteArray().size + 64 } <= 4608)
    }
}
