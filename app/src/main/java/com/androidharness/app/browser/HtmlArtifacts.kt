package com.androidharness.app.browser

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.workspace.WorkspaceFs
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class HtmlArtifact(val path: String, val toolCallId: String, val turnId: String?)

/** Derives artifacts from completed tool calls, never from model prose or links. */
object HtmlArtifacts {
    fun successfulWrites(messages: List<ChatMessage>, workspaceRoot: String? = null): List<HtmlArtifact> {
        val pending = mutableMapOf<String, Pair<ToolCallData, String?>>()
        val artifacts = linkedMapOf<String, HtmlArtifact>()
        for (message in messages) {
            if (message.role == Role.ASSISTANT) {
                message.toolCalls.filter { it.name == "write_file" || it.name == "edit_file" }
                    .forEach { pending[it.id] = it to message.turnId }
            } else if (message.role == Role.TOOL) {
                val callId = message.toolCallId ?: continue
                val (call, turnId) = pending.remove(callId) ?: continue
                if (message.isError || message.toolName != call.name || message.turnId != turnId) continue
                val args = runCatching { Json.parseToJsonElement(call.argumentsJson) as? JsonObject }.getOrNull()
                val value = args?.get("path") as? JsonPrimitive ?: continue
                if (!value.isString) continue
                val path = HtmlPreviewPolicy.htmlPath(value.content, workspaceRoot) ?: continue
                artifacts[path] = HtmlArtifact(path, call.id, turnId)
            }
        }
        return artifacts.values.toList()
    }

    /** WorkspaceFs supports both app-private files and Android document trees. Call off the UI thread. */
    fun existing(messages: List<ChatMessage>, workspace: WorkspaceFs?): List<HtmlArtifact> {
        if (workspace == null) return emptyList()
        return successfulWrites(messages, workspace.shellRoot?.absolutePath).filter { artifact ->
            runCatching { workspace.resolve(artifact.path).let { it.exists && it.isFile } }.getOrDefault(false)
        }
    }
}

/** Local preview URLs use the same origin as the agent browser, with no remote fallback. */
object HtmlPreviewPolicy {
    fun htmlPath(path: String, workspaceRoot: String? = null): String? {
        var relative = path
        val root = workspaceRoot?.trimEnd('/')
        if (root != null && relative.startsWith("$root/")) relative = relative.removePrefix("$root/")
        if (relative.startsWith('/') || relative.contains('\\') || relative.contains(':') ||
            relative.any { it.code < 32 || it.code == 127 }) return null
        val parts = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || ".." in parts) return null
        relative = parts.joinToString("/")
        if (!relative.endsWith(".html", true) && !relative.endsWith(".htm", true)) return null
        return relative
    }

    fun localFileUrl(path: String): String {
        val encoded = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "https://${WorkspacePathHandler.HOST}${WorkspacePathHandler.PATH_PREFIX}$encoded"
    }

    /** Validates each decoded URL segment before the shared workspace asset loader sees it. */
    fun workspaceRequestPath(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || !uri.host.equals(WorkspacePathHandler.HOST, true) ||
            uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
        val parts = mutableListOf<String>()
        for (raw in (uri.rawPath ?: "").split('/')) {
            val part = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
            if (part == ".." || part.contains('/') || part.contains('\\') || part.contains(':') ||
                part.any { it.code < 32 || it.code == 127 }) return null
            if (part.isNotEmpty() && part != ".") parts += part
        }
        if (parts.firstOrNull() == "ws") parts.removeAt(0)
        return parts.joinToString("/").ifEmpty { "index.html" }
    }
}
