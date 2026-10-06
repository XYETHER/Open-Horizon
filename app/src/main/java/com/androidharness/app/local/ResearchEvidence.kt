package com.androidharness.app.local

import com.androidharness.app.core.*
import kotlinx.serialization.json.*
import java.net.URI

internal data class ReadSource(val url: String, val title: String) {
    val host: String get() = runCatching { URI(url).host.removePrefix("www.") }.getOrDefault(url)
}

/** Derived from successful tool results, never from the assistant's claims. */
internal object ResearchEvidence {
    private fun pageBody(text: String): String = if(text.startsWith("Source URL: ")) text.substringAfter("\n\n", "") else text
    fun argument(call: ToolCallData, key: String): String? = runCatching {
        Json.parseToJsonElement(call.argumentsJson).jsonObject[key]?.jsonPrimitive?.content
    }.getOrNull()
    fun sources(messages: List<ChatMessage>): List<ReadSource> {
        val calls=messages.flatMap { it.toolCalls }.associateBy { it.id }
        return messages.mapNotNull { message ->
            val call=calls[message.toolCallId] ?: return@mapNotNull null
            if(message.role!=Role.TOOL || message.isError || call.name!="web_fetch" ||
                pageBody(message.text).length<=100 || message.text.startsWith("[binary content:")) return@mapNotNull null
            val requested=argument(call,"url") ?: return@mapNotNull null
            val actual=message.text.lineSequence().firstOrNull { it.startsWith("Source URL: ") }
                ?.removePrefix("Source URL: ") ?: requested
            val uri=runCatching { URI(actual) }.getOrNull() ?: return@mapNotNull null
            if(uri.scheme !in setOf("http","https") || uri.host.isNullOrBlank())return@mapNotNull null
            val title=message.text.lineSequence().take(4).firstOrNull { it.startsWith("Page title: ") }
                ?.removePrefix("Page title: ")?.take(120).orEmpty().ifBlank { uri.host.removePrefix("www.") }
            ReadSource(actual,title)
        }.distinctBy { it.url }
    }
}
