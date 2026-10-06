package com.androidharness.app.local
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.*

/** Qwen3.5's function= / parameter= format; validates a complete batch before exposing any call. */
internal object Qwen35Protocol {
    fun toolPrompt(system: String, tools: List<ToolSchema>): String = if (tools.isEmpty()) system else buildString {
        append("# Tools\n<tools>\n")
        tools.forEach { append(buildJsonObject { put("type", "function"); putJsonObject("function") { put("name", it.name); put("description", it.description); put("parameters", it.parametersJson) } }).append('\n') }
        append("</tools>\nCall only listed functions in this format, no markdown and no text after the calls:\n")
        append("<tool_call>\n<function=tool_name>\n<parameter=parameter_name>\nvalue\n</parameter>\n</function>\n</tool_call>\n")
        append("Wait for tool results before continuing.\n\n").append(system)
    }
    fun encodeAssistant(message: ChatMessage): ChatMessage = if (message.toolCalls.isEmpty()) message else message.copy(
        text = message.text + "\n" + message.toolCalls.joinToString("\n") { call ->
            "<tool_call>\n<function=${call.name}>\n" + Json.parseToJsonElement(call.argumentsJson).jsonObject.entries.joinToString("\n") { (key, value) ->
                val text = if (value is JsonPrimitive && value.isString) value.content else value.toString()
                check(!text.contains("</parameter>") && !text.contains("<tool_call>")) { "Tool content contains reserved Qwen boundaries. Read a smaller range." }
                "<parameter=$key>\n$text\n</parameter>"
            } + "\n</function>\n</tool_call>"
        }, toolCalls = emptyList())
    fun parseCalls(raw: String, tools: List<ToolSchema>, complete: Boolean): List<ToolCallData> {
        require(raw.length <= LocalAgentProtocol.MAX_OUTPUT_CHARS)
        val answer = if (raw.trimStart().startsWith("<think>")) {
            val end = raw.indexOf("</think>"); if (end < 0) return emptyList(); raw.substring(end + 8)
        } else raw
        val start = answer.indexOf("<tool_call>")
        if (start < 0) { check(!answer.contains("<tool_call") && !answer.contains("</tool_call")) { "Incomplete Qwen tool reply." }; return emptyList() }
        check(complete && !answer.substring(0, start).contains("```")) { "Incomplete or quoted Qwen tool reply. No tools were executed." }
        val schemas = tools.associateBy { it.name }; val payloads = mutableListOf<JsonObject>(); var at = start
        fun space() { while (at < answer.length && answer[at].isWhitespace()) at++ }
        fun expect(value: String) { check(answer.startsWith(value, at)) { "Malformed Qwen tool reply. No tools were executed." }; at += value.length }
        fun name(prefix: String): String { expect(prefix); val end = answer.indexOf('>', at); check(end >= at); val n = answer.substring(at, end); check(n.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,79}"))); at = end + 1; return n }
        while (at < answer.length) {
            space(); if (at == answer.length) break
            expect("<tool_call>"); space(); val toolName = name("<function=")
            val props = checkNotNull(schemas[toolName]) { "Unknown Qwen tool." }.parametersJson.jsonObject["properties"]?.jsonObject.orEmpty()
            val args = linkedMapOf<String, JsonElement>()
            while (true) {
                space(); if (answer.startsWith("</function>", at)) break
                val key = name("<parameter="); check(key !in args) { "Duplicate Qwen parameter." }
                // The model's template frames each value with one newline; preserve the actual payload's whitespace.
                if (answer.startsWith("\n", at)) at++
                val end = answer.indexOf("</parameter>", at); check(end >= at) { "Incomplete Qwen parameter." }
                val text = answer.substring(at, end).removeSuffix("\n"); check(!text.contains("<tool_call>") && !text.contains("<parameter=")) { "Ambiguous Qwen parameter boundaries." }
                val type = props[key]?.jsonObject?.get("type")?.jsonPrimitive?.content ?: error("Unknown Qwen parameter.")
                args[key] = if (type == "string") JsonPrimitive(text) else runCatching { Json.parseToJsonElement(text.trim()) }.getOrElse { error("Wrong Qwen parameter type.") }
                at = end + "</parameter>".length
            }
            expect("</function>"); space(); expect("</tool_call>")
            payloads += buildJsonObject { put("name", toolName); put("arguments", JsonObject(args)) }
            check(payloads.size <= 8) { "Too many Qwen tool calls." }
        }
        val normalized = LocalAgentProtocol.CALLS_OPEN + payloads.joinToString("") { LocalAgentProtocol.CALL_OPEN + it + LocalAgentProtocol.CALL_CLOSE } + LocalAgentProtocol.CALLS_CLOSE
        return LocalAgentProtocol.parseCalls(normalized, tools, false, true)
    }
}
