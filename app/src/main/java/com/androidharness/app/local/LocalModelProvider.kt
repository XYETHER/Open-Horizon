package com.androidharness.app.local

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.agent.k2ReasoningEffort
import com.androidharness.app.llm.LlmProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.llm.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

class LocalModelProvider(private val manager: LocalModelManager, private val responseDiagnostic: ((String) -> Unit)? = null) : LlmProvider {
    companion object { const val INVALID_TOOL_CALL_PREFIX = "Invalid local tool call: " }
    private class InvalidToolCall(cause: Exception) : Exception(cause.message, cause)
    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
        messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
        try {
            val id = config.id.removePrefix(LocalModelCatalog.PROVIDER_PREFIX)
            check(config.model == config.id) { "Choose this provider's installed model. Other model IDs cannot run locally." }
            val hasImages = messages.any { it.images.isNotEmpty() || it.imageData.isNotEmpty() }
            check(!hasImages || manager.visionReady(id)) { "Images need Qwen3.5 with Vision enabled and its projector downloaded. Open Local models > Qwen3.5 > Vision." }
            val limits = manager.limits(id)
            val k2Xml = id == "k2-horizon-09b" || id == "k2-horizon-37b" || id == "k2-horizon-09b-mnn"
            val miniCpm = id == "sharp-minicpm5-2b" || id == "minicpm5-2b-mnn"
            val qwen = id == "qwen35-2b"
            val profileGuidance = if ((miniCpm || qwen) && options.thinking.k2ReasoningEffort() == "high") "\nCheck alternatives and verify your reasoning carefully." else ""
            val history = LocalAgentProtocol.history(systemPrompt + profileGuidance, messages, tools, limits.input, miniCpm, qwen, k2Xml)
            val vision = LocalVisionInput.prepare(history)
            val decoder = Utf8TokenDecoder()
            // Native emits the K2 reasoning prefix explicitly, based on GGUF architecture,
            // so renamed/custom files follow the same protocol without filename guessing.
            val responseStream = LocalResponseStream(initialThinking = false, xmlTools = miniCpm, qwenTools = qwen)
            val raw = StringBuilder()
            suspend fun accept(text: String) {
                check(raw.length + text.length <= LocalAgentProtocol.MAX_OUTPUT_CHARS) { "Local model output exceeded the response limit. No tools were executed." }
                raw.append(text)
                responseStream.appendWithActivity(text).forEach { emit(it) }
            }
            val counts = manager.generate(id, vision.messages.map { it.role.name.lowercase() }.toTypedArray(),
                vision.messages.map { it.text.toByteArray(Charsets.UTF_8) }.toTypedArray(), minOf(options.maxOutputTokens, limits.output),
                options.thinking.k2ReasoningEffort(), vision.images) { bytes ->
                accept(decoder.append(bytes))
            }
            accept(decoder.finish())
            responseStream.finish().forEach { emit(it) }
            emit(StreamEvent.Usage(counts[0], counts[1], cachedInputTokens=counts.getOrElse(3){0}, cacheReported=counts.getOrElse(3){0}>0))
            responseDiagnostic?.invoke(raw.toString())
            val calls = try { if (k2Xml) K2XmlProtocol.parseCalls(raw.toString(), tools, complete = counts[2] == 0) else if (miniCpm) MiniCpmProtocol.parseCalls(raw.toString(), tools, complete = counts[2] == 0) else if (qwen) Qwen35Protocol.parseCalls(raw.toString(), tools, complete = counts[2] == 0) else LocalAgentProtocol.parseCalls(raw.toString(), tools, initialThinking = false, complete = counts[2] == 0) } catch (e: Exception) { throw InvalidToolCall(e) }
            if (calls.isNotEmpty()) emit(StreamEvent.ToolCallBatch(calls))
            emit(StreamEvent.Done(if (calls.isNotEmpty()) "tool_calls" else if (counts[2] == 0) "stop" else "length"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(StreamEvent.Failure(if (e is InvalidToolCall) INVALID_TOOL_CALL_PREFIX + (e.message ?: "Invalid format") else e.message ?: "Local inference failed"))
        } catch (_: UnsatisfiedLinkError) {
            emit(StreamEvent.Failure("Local inference is unavailable for this device architecture."))
        }
    }
}

internal class Utf8TokenDecoder {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)
    fun append(bytes: ByteArray): String = decode(bytes, false)
    fun finish(): String = decode(ByteArray(0), true)
    private fun decode(bytes: ByteArray, end: Boolean): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, output, end)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        if (end) decoder.flush(output)
        output.flip()
        return output.toString()
    }
}
