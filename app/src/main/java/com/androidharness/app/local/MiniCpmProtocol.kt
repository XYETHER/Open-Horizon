package com.androidharness.app.local

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** MiniCPM5's native XML convention, validated atomically by the existing tool schema gate. */
internal object MiniCpmProtocol {
    fun toolPrompt(system: String, tools: List<ToolSchema>): String = if (tools.isEmpty()) system else buildString {
        append("# Tools\n<tools>\n")
        tools.forEach { tool -> append(buildJsonObject {
            put("name", tool.name); put("description", tool.description); put("parameters", tool.parametersJson)
        }).append('\n') }
        append("</tools>\nCall only listed tools with <function name=\"tool-name\"><param name=\"parameter\">value</param></function>. ")
        append("Wrap multiline values, HTML, < or & in CDATA. Example: <param name=\"content\"><![CDATA[<html>...</html>]]></param>. ")
        append("No markdown around calls; no text after them. Wait for tool results before continuing.\n\n")
        append(system)
    }

    private fun attribute(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    private fun cdata(text: String) = "<![CDATA[" + text.replace("]]>", "]]]]><![CDATA[>") + "]]>"
    fun encodeAssistant(message: ChatMessage): ChatMessage = if (message.toolCalls.isEmpty()) message else message.copy(
        text = message.text + "\n" + message.toolCalls.joinToString("\n") { call ->
            val args = Json.parseToJsonElement(call.argumentsJson).jsonObject
            "<function name=\"${attribute(call.name)}\">" + args.entries.joinToString("") { (name, value) ->
                val text = if (value is JsonPrimitive && value.isString) value.content else value.toString()
                "<param name=\"${attribute(name)}\">${cdata(text)}</param>"
            } + "</function>"
        }, toolCalls = emptyList())

    fun parseCalls(raw: String, tools: List<ToolSchema>, complete: Boolean): List<ToolCallData> {
        require(raw.length <= LocalAgentProtocol.MAX_OUTPUT_CHARS) { "Local response is too large." }
        val answer = if (raw.trimStart().startsWith("<think>")) {
            val end = raw.indexOf("</think>")
            if (end < 0) return emptyList()
            raw.substring(end + "</think>".length)
        } else raw
        val start = answer.indexOf("<function")
        if (start < 0) {
            check(!answer.contains("</function")) { "Malformed MiniCPM tool call. No tools were executed." }
            return emptyList()
        }
        check(complete) { "MiniCPM hit its output limit during a tool reply. No tools were executed." }
        check(!answer.substring(0, start).contains("```")) { "MiniCPM printed a tool example. No tools were executed." }
        val xml = answer.substring(start)
        check(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true) && !xml.contains("<?")) { "XML declarations are forbidden in tool calls." }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External entities are forbidden.") }
        val root = runCatching { builder.parse(InputSource(StringReader("<calls>$xml</calls>"))).documentElement }
            .getOrElse { throw IllegalStateException("Malformed MiniCPM XML. No tools were executed.") }
        val byName = tools.associateBy { it.name }
        val payloads = mutableListOf<JsonObject>()
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i)
            if (node.nodeType == Node.TEXT_NODE && node.textContent.isBlank()) continue
            check(node is Element && node.tagName == "function" && node.attributes.length == 1 && node.hasAttribute("name")) { "Invalid MiniCPM function element." }
            val name = node.getAttribute("name")
            val tool = checkNotNull(byName[name]) { "Unknown MiniCPM tool: $name" }
            val properties = tool.parametersJson.jsonObject["properties"]?.jsonObject.orEmpty()
            val args = linkedMapOf<String, JsonElement>()
            for (j in 0 until node.childNodes.length) {
                val param = node.childNodes.item(j)
                if (param.nodeType == Node.TEXT_NODE && param.textContent.isBlank()) continue
                check(param is Element && param.tagName == "param" && param.attributes.length == 1 && param.hasAttribute("name")) { "Invalid MiniCPM parameter." }
                val key = param.getAttribute("name")
                check(key !in args) { "Duplicate MiniCPM parameter." }
                check((0 until param.childNodes.length).all { param.childNodes.item(it).nodeType in listOf(Node.TEXT_NODE, Node.CDATA_SECTION_NODE) }) { "Use CDATA for nested content." }
                val schema = properties[key]?.jsonObject ?: error("Unknown MiniCPM parameter: $key")
                val type = schema["type"]?.jsonPrimitive?.content
                val text = param.textContent
                args[key] = if (type == "string") JsonPrimitive(text) else runCatching { Json.parseToJsonElement(text.trim()) }
                    .getOrElse { error("Invalid MiniCPM parameter type: $key") }
            }
            payloads += buildJsonObject { put("name", name); put("arguments", JsonObject(args)) }
            check(payloads.size <= 8) { "Too many MiniCPM tool calls." }
        }
        check(payloads.isNotEmpty()) { "Empty MiniCPM tool reply." }
        val normalized = LocalAgentProtocol.CALLS_OPEN + payloads.joinToString("") {
            LocalAgentProtocol.CALL_OPEN + it.toString() + LocalAgentProtocol.CALL_CLOSE
        } + LocalAgentProtocol.CALLS_CLOSE
        return LocalAgentProtocol.parseCalls(normalized, tools, initialThinking = false, complete = true)
    }
}
