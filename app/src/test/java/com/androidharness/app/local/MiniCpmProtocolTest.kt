package com.androidharness.app.local
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class MiniCpmProtocolTest {
    private val write = ToolSchema("write_file", "Save a file", Json.parseToJsonElement("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"],"additionalProperties":false}""").jsonObject)
    private val read = ToolSchema("read_file", "Read a file", Json.parseToJsonElement("""{"type":"object","properties":{"path":{"type":"string"},"limit":{"type":"integer"}},"required":["path"],"additionalProperties":false}""").jsonObject)
    private val tools = listOf(write, read)
    private fun parse(s: String, complete: Boolean = true) = MiniCpmProtocol.parseCalls(s, tools, complete)
    @Test fun htmlCdataAndMultipleCallsPreserveValues() {
        val calls = parse("""<think>Plan</think><function name="write_file"><param name="path">a/index.html</param><param name="content"><![CDATA[<h1>A & B</h1>]]></param></function><function name="read_file"><param name="path">a/index.html</param><param name="limit">10</param></function>""")
        assertEquals(2, calls.size)
        assertEquals("<h1>A & B</h1>", Json.parseToJsonElement(calls[0].argumentsJson).jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(10, Json.parseToJsonElement(calls[1].argumentsJson).jsonObject["limit"]!!.jsonPrimitive.int)
    }
    @Test fun partialBatchNeverExecutesItsValidFirstCall() {
        assertTrue(runCatching { parse("""<function name="read_file"><param name="path">a</param></function><function name="write_file">""") }.isFailure)
    }
    @Test fun outputLimitNeverExecutesCalls() {
        assertTrue(runCatching { parse("""<function name="read_file"><param name="path">a</param></function>""", false) }.isFailure)
    }
    @Test fun declarationsAndExternalEntitiesAreRejected() {
        assertTrue(runCatching { parse("""<function name="read_file"><!DOCTYPE x [<!ENTITY a SYSTEM "file:///secret">]><param name="path">&a;</param></function>""") }.isFailure)
    }
    @Test fun unknownToolsAndDuplicateArgumentsAreRejected() {
        assertTrue(runCatching { parse("""<function name="bad"></function>""") }.isFailure)
        assertTrue(runCatching { parse("""<function name="read_file"><param name="path">a</param><param name="path">b</param></function>""") }.isFailure)
    }
    @Test fun wrongTypesAndMissingRequiredArgumentsAreRejected() {
        assertTrue(runCatching { parse("""<function name="read_file"><param name="path">a</param><param name="limit">"ten"</param></function>""") }.isFailure)
        assertTrue(runCatching { parse("""<function name="write_file"><param name="path">a</param></function>""") }.isFailure)
    }
    @Test fun thinkingDoesNotExecuteToolExamples() {
        assertTrue(parse("""<think><function name="bad"></function></think>Answer""").isEmpty())
    }
    @Test fun historyReplaysNativeXmlRoundTripIncludingCdataTerminator() {
        val calls = parse("""<function name="write_file"><param name="path">a</param><param name="content"><![CDATA[test ]]]]><![CDATA[> end]]></param></function>""")
        val encoded = MiniCpmProtocol.encodeAssistant(ChatMessage(Role.ASSISTANT, "", toolCalls = calls))
        assertEquals(Json.parseToJsonElement(calls[0].argumentsJson), Json.parseToJsonElement(parse(encoded.text)[0].argumentsJson))
        val history = LocalAgentProtocol.history("Work locally", listOf(ChatMessage(Role.USER, "Write a file"), encoded), tools, 4096, miniCpm = true)
        assertTrue(history.first().text.contains("<tools>")); assertFalse(history.first().text.contains("<ifm|tools>"))
    }
    @Test fun nativeThinkingAndXmlCallMarkersStreamAcrossChunks() {
        val stream = LocalResponseStream(false, xmlTools = true)
        val events = stream.append("<thi") + stream.append("nk>Plan</think>Answer<fun") + stream.append("ction name=\"read_file\">") + stream.finish()
        assertEquals("Plan", events.filterIsInstance<com.androidharness.app.llm.StreamEvent.ThinkingDelta>().joinToString("") { it.text })
        assertEquals("Answer", events.filterIsInstance<com.androidharness.app.llm.StreamEvent.TextDelta>().joinToString("") { it.text })
    }
}
