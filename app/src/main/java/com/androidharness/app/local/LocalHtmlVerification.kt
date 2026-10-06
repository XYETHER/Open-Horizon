package com.androidharness.app.local

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.*

/** A small model must not skip checking the HTML it has just saved. */
internal object LocalHtmlVerification {
    private val required = setOf("browser_navigate", "browser_get_dom", "browser_get_logs")
    fun missingCheckPrompt(messages: List<ChatMessage>, available: Set<String>, requiresCreation:Boolean = false): String? {
        if (!available.containsAll(required)) return null
        val calls = messages.flatMap { it.toolCalls }.associateBy { it.id }
        fun completed(index: Int): ToolCallData? = messages[index].let { message ->
            if (message.role == Role.TOOL && !message.isError) calls[message.toolCallId] else null
        }
        fun path(call: ToolCallData, key: String): String? = runCatching {
            Json.parseToJsonElement(call.argumentsJson).jsonObject[key]?.jsonPrimitive?.content
        }.getOrNull()
        val writeIndex = messages.indices.lastOrNull { index ->
            val call = completed(index)
            call != null && call.name in setOf("write_file", "edit_file") &&
                path(call, "path")?.let { it.endsWith(".html", true) || it.endsWith(".htm", true) } == true
        } ?: return if(requiresCreation && "write_file" in available)
            "You were asked to create an HTML file. write_file is available: save complete compact HTML to single.html inside the agent folder, then read and render it with the browser tools. Do not refuse file creation or give only instructions when these tools are available. If a tool actually fails, report its error." else null
        val saved = path(completed(writeIndex)!!, "path")!!
        val navIndex = messages.indices.firstOrNull { index ->
            index > writeIndex && completed(index)?.let {
                it.name == "browser_navigate" && path(it,"url")?.removePrefix("./") == saved.removePrefix("./")
            } == true
        }
        val missing = if (navIndex == null) required else setOf("browser_get_dom", "browser_get_logs").filter { name ->
            messages.indices.none { it > navIndex && completed(it)?.name == name }
        }.toSet()
        return if (missing.isEmpty()) null else
            "Before finishing, complete these real HTML checks: ${missing.joinToString()}. " +
                "Saved HTML path: $saved. browser_navigate uses its relative path as url. " +
                "browser_get_dom and browser_get_logs use empty arguments, no url or path. " +
                "Call the missing tools now, inspect their results and report any errors. Do not claim an unperformed check."
    }
}
