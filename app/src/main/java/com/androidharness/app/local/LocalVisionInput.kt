package com.androidharness.app.local
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import java.util.Base64

/** Markers are inserted only for real retained image bytes, never user-supplied placeholders. */
internal object LocalVisionInput {
    const val MARKER = "<__media__>"
    data class Prepared(val messages: List<ChatMessage>, val images: Array<ByteArray>)
    fun prepare(history: List<ChatMessage>): Prepared {
        val bytes = mutableListOf<ByteArray>()
        val messages = history.map { message ->
            check(message.images.size <= message.imageData.size) { "An attached image is missing from local storage. Attach it again." }
            check(message.imageData.isEmpty() || message.role == Role.USER) { "Only user images can be sent to local vision." }
            var content = message.text.replace(MARKER, "[image marker]")
            message.imageData.forEach { image ->
                require(image.mime.startsWith("image/")) { "Only image attachments are supported." }
                require(image.base64.length <= 24 * 1024 * 1024) { "Image is too large. Choose a smaller image." }
                val decoded = Base64.getDecoder().decode(image.base64)
                require(decoded.isNotEmpty() && decoded.size <= 16 * 1024 * 1024) { "Image is empty or too large." }
                bytes += decoded
                check(bytes.size <= 2) { "Use up to two images per request, or start a new chat to clear earlier images." }
                content += "\n$MARKER\n"
            }
            message.copy(text = content)
        }
        return Prepared(messages, bytes.toTypedArray())
    }
}
