package com.androidharness.app.local

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class K2XmlProtocolTest {
    private val schema = ToolSchema("write_file", "Write", Json.parseToJsonElement("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""").jsonObject)
    private val tools = listOf(schema)
    private fun call(path: String = "site/index.html", content: String = "<h1>Local K2 Passed</h1>") = "<ifm|tool_call>write_file\n<ifm|arg_key>path</ifm|arg_key>\n<ifm|arg_value>$path</ifm|arg_value>\n<ifm|arg_key>content</ifm|arg_key>\n<ifm|arg_value>$content</ifm|arg_value>\n</ifm|tool_call>"
    private fun batch(content: String) = "<ifm|tool_calls>\n$content\n</ifm|tool_calls>"
    @Test fun actualHtmlTagsRemainPlainStringArguments() {
        val result = K2XmlProtocol.parseCalls("<ifm|think_faster>\n</ifm|think_faster>\n" + batch(call()), tools, true).single()
        assertEquals("write_file", result.name)
        assertEquals("<h1>Local K2 Passed</h1>", Json.parseToJsonElement(result.argumentsJson).jsonObject["content"]!!.jsonPrimitive.content)
    }
    @Test fun invalidSecondCallPreventsEntireBatch() {
        assertThrows(IllegalStateException::class.java) { K2XmlProtocol.parseCalls(batch(call() + call().replace("write_file", "shell")), tools, true) }
    }
    @Test fun truncatedQuotedDuplicateAndTrailingCallsAreRejected() {
        listOf(batch(call()).dropLast(4), "```xml\n" + batch(call()) + "\n```", batch(call()) + "more", batch(call().replace("<ifm|arg_key>content", "<ifm|arg_key>path")), batch(call(content="<ifm|tool_call>"))).forEach { raw ->
            assertThrows(IllegalStateException::class.java) { K2XmlProtocol.parseCalls(raw, tools, true) }
        }
        assertThrows(IllegalStateException::class.java) { K2XmlProtocol.parseCalls(batch(call()), tools, false) }
    }
    @Test fun unfinishedThoughtDoesNotExecuteMentionedCalls() {
        assertTrue(K2XmlProtocol.parseCalls("<ifm|think>" + batch(call()), tools, true).isEmpty())
    }
    @Test fun replayRoundTripsValidatedArguments() {
        val message = ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCallData("id", "write_file", """{"path":"a.txt","content":"two\nlines & <tags>"}""")))
        val replay = K2XmlProtocol.encodeAssistant(message)
        assertTrue(replay.toolCalls.isEmpty())
        assertEquals(message.toolCalls.single().argumentsJson, K2XmlProtocol.parseCalls(replay.text, tools, true).single().argumentsJson)
    }
    @Test fun completePublisherJsonCallsRemainCompatible() {
        val raw = batch("<ifm|tool_call>{\"name\":\"write_file\",\"arguments\":{\"path\":\"x\",\"content\":\"ok\"}}</ifm|tool_call>")
        assertEquals("write_file", K2XmlProtocol.parseCalls(raw, tools, true).single().name)
    }
}
