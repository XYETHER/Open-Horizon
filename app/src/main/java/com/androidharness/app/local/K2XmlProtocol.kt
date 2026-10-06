package com.androidharness.app.local

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.*
import com.androidharness.app.llm.ToolSchema

/** Publisher-default IFM argument tags, with the same atomic JSON/schema execution gate. */
internal object K2XmlProtocol {
    private const val KEY = "<ifm|arg_key>"
    private const val KEY_END = "</ifm|arg_key>"
    private const val VALUE = "<ifm|arg_value>"
    private const val VALUE_END = "</ifm|arg_value>"
    fun toolPrompt(system: String, tools: List<ToolSchema>): String = if (tools.isEmpty()) system else buildString {
        append("# Tools\nYou may call one or more tools to assist with the user query.\n\nAvailable tools are:\n\n<ifm|tools>")
        tools.forEach { schema -> append('\n').append(buildJsonObject { put("type","function"); putJsonObject("function") {
            put("name",schema.name);put("description",schema.description);put("parameters",schema.parametersJson)
        } }) }
        append("\n</ifm|tools>\n\nWhen calling tools, you MUST follow the tool-call format below:\n\n")
        append("Wrap all tool calls in a single <ifm|tool_calls></ifm|tool_calls> block. For each call, write the function name at the start of <ifm|tool_call>, followed by paired <ifm|arg_key> and <ifm|arg_value> tags for each argument:\n\n<ifm|tool_calls>\n<ifm|tool_call>\$FUNCTION_NAME\n<ifm|arg_key>\$PARAMETER_NAME</ifm|arg_key>\n<ifm|arg_value>\$PARAMETER_VALUE</ifm|arg_value>\n...\n</ifm|tool_call>\n</ifm|tool_calls>\n\nString and scalar parameters should be written as plain text. Array and object parameters should be written as JSON literals.")
        append("\n\n").append(system)
    }
    fun encodeAssistant(message: ChatMessage): ChatMessage = if(message.toolCalls.isEmpty()) message else message.copy(
        text=message.text + "\n" + LocalAgentProtocol.CALLS_OPEN + message.toolCalls.joinToString("") { call ->
            "\n" + LocalAgentProtocol.CALL_OPEN + call.name + "\n" + Json.parseToJsonElement(call.argumentsJson).jsonObject.entries.joinToString("") { (key,value) ->
                val text=if(value is JsonPrimitive && value.isString)value.content else value.toString()
                check(!text.contains("<ifm|") && !text.contains("</ifm|")) { "Tool content contains reserved IFM boundaries." }
                KEY+key+KEY_END+"\n"+VALUE+text+VALUE_END+"\n"
            } + LocalAgentProtocol.CALL_CLOSE
        } + "\n" + LocalAgentProtocol.CALLS_CLOSE, toolCalls=emptyList())
    fun parseCalls(raw: String, tools: List<ToolSchema>, complete: Boolean): List<ToolCallData> {
        require(raw.length <= LocalAgentProtocol.MAX_OUTPUT_CHARS)
        val close=if(LocalAgentProtocol.thinkingOpeners.any{raw.trimStart().startsWith(it)}) LocalAgentProtocol.thinkingClosers.mapNotNull{tag->raw.indexOf(tag).takeIf{it>=0}?.let{it+tag.length}}.minOrNull() ?: return emptyList() else 0
        val answer=raw.substring(close)
        val start=answer.indexOf(LocalAgentProtocol.CALLS_OPEN)
        if(start<0) return LocalAgentProtocol.parseCalls(raw,tools,false,complete)
        check(complete && !answer.substring(0,start).contains("```")) { "Incomplete or quoted IFM tool reply. No tools were executed." }
        val first=answer.indexOf(LocalAgentProtocol.CALL_OPEN,start)
        if(first>=0 && answer.substring(first+LocalAgentProtocol.CALL_OPEN.length).trimStart().startsWith("{")) return LocalAgentProtocol.parseCalls(raw,tools,false,complete)
        val schemas=tools.associateBy{it.name};val payloads=mutableListOf<JsonObject>();var at=start
        fun space(){while(at<answer.length && answer[at].isWhitespace())at++}
        fun expect(s: String){check(answer.startsWith(s,at)){"Malformed IFM argument tags. No tools were executed."};at+=s.length}
        fun tagged(open: String, end: String):String {expect(open);val finish=answer.indexOf(end,at);check(finish>=at){"Incomplete IFM argument."};val text=answer.substring(at,finish);at=finish+end.length;return text}
        expect(LocalAgentProtocol.CALLS_OPEN)
        while(true){
            space();if(answer.startsWith(LocalAgentProtocol.CALLS_CLOSE,at))break
            expect(LocalAgentProtocol.CALL_OPEN);space();val end=answer.indexOf('<',at);check(end>=at)
            val name=answer.substring(at,end).trim();val schema=checkNotNull(schemas[name]){"Unknown IFM function."};at=end
            val props=schema.parametersJson.jsonObject["properties"]?.jsonObject.orEmpty();val args=linkedMapOf<String,JsonElement>()
            while(true){
                space();if(answer.startsWith(LocalAgentProtocol.CALL_CLOSE,at))break
                val key=tagged(KEY,KEY_END).trim();check(key !in args){"Duplicate IFM argument."};space()
                val text=tagged(VALUE,VALUE_END);check(!text.contains("<ifm|") && !text.contains("</ifm|")){"Ambiguous IFM argument boundaries."}
                val type=props[key]?.jsonObject?.get("type")?.jsonPrimitive?.content ?: error("Unknown IFM argument $key for $name; allowed: ${props.keys.joinToString()}.")
                args[key]=if(type=="string")JsonPrimitive(text)else runCatching{Json.parseToJsonElement(text.trim())}.getOrElse{error("Invalid IFM argument type.")}
            }
            expect(LocalAgentProtocol.CALL_CLOSE)
            payloads+=buildJsonObject{put("name",name);put("arguments",JsonObject(args))};check(payloads.size<=4){"Too many IFM calls."}
        }
        expect(LocalAgentProtocol.CALLS_CLOSE);space();check(at==answer.length){"Trailing IFM tool text."}
        val normalized=LocalAgentProtocol.CALLS_OPEN+payloads.joinToString(""){LocalAgentProtocol.CALL_OPEN+it+LocalAgentProtocol.CALL_CLOSE}+LocalAgentProtocol.CALLS_CLOSE
        return LocalAgentProtocol.parseCalls(normalized,tools,false,true)
    }
}
