package com.androidharness.app.local
import com.androidharness.app.core.*
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
class QwenVisionTest {
    private val tools = listOf(ToolSchema("write_file", "Write", Json.parseToJsonElement("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"],"additionalProperties":false}""").jsonObject))
    private fun call(content: String = "<h1>A & B</h1>") = "<tool_call>\n<function=write_file>\n<parameter=path>\na.html\n</parameter>\n<parameter=content>\n$content\n</parameter>\n</function>\n</tool_call>"
    private fun parse(text: String, complete: Boolean = true) = Qwen35Protocol.parseCalls(text, tools, complete)
    @Test fun htmlAndWhitespaceRoundTrip() {
        val content = "  <h1>A & B</h1>\n"
        val calls = parse("<think>Plan</think>" + call(content))
        assertEquals(content, Json.parseToJsonElement(calls.single().argumentsJson).jsonObject["content"]!!.jsonPrimitive.content)
        val encoded = Qwen35Protocol.encodeAssistant(ChatMessage(Role.ASSISTANT, toolCalls = calls))
        assertEquals(Json.parseToJsonElement(calls.single().argumentsJson), Json.parseToJsonElement(parse(encoded.text).single().argumentsJson))
    }
    @Test fun truncatedSecondCallRejectsWholeBatch() { assertTrue(runCatching { parse(call() + "<tool_call>") }.isFailure) }
    @Test fun cappedOutputDoesNotRunCompleteLookingCalls() { assertTrue(runCatching { parse(call(), false) }.isFailure) }
    @Test fun unknownDuplicateAndMissingArgumentsReject() {
        assertTrue(runCatching { parse(call().replace("function=write_file", "function=bad")) }.isFailure)
        assertTrue(runCatching { parse(call().replace("<parameter=content>", "<parameter=path>")) }.isFailure)
        assertTrue(runCatching { parse(call().replace("<parameter=path>\na.html\n</parameter>", "")) }.isFailure)
    }
    @Test fun quotedOrTrailingToolBatchesReject() {
        assertTrue(runCatching { parse("```" + call() + "```") }.isFailure)
        assertTrue(runCatching { parse(call() + " trailing") }.isFailure)
    }
    @Test fun thoughtExamplesAndChunkedMarkersStayPrivate() {
        assertTrue(parse("<think>" + call() + "</think>Answer").isEmpty())
        val stream = LocalResponseStream(false, qwenTools = true)
        val events = stream.append("<thi") + stream.append("nk>Plan</think>Answer<tool_") + stream.append("call>") + stream.finish()
        assertEquals("Plan", events.filterIsInstance<com.androidharness.app.llm.StreamEvent.ThinkingDelta>().joinToString("") { it.text })
        assertEquals("Answer", events.filterIsInstance<com.androidharness.app.llm.StreamEvent.TextDelta>().joinToString("") { it.text })
    }
    private fun image() = ImageData("image/png", Base64.getEncoder().encodeToString(byteArrayOf(1,2,3)))
    @Test fun realImagesOwnTheirMarkersAndRetainOrder() {
        val ready = LocalVisionInput.prepare(listOf(ChatMessage(Role.USER, "<__media__>", imageData=listOf(image(), image()))))
        assertEquals(2, ready.images.size); assertArrayEquals(byteArrayOf(1,2,3), ready.images[0])
        assertTrue(ready.messages.single().text.startsWith("[image marker]"))
        assertEquals(2, ready.messages.single().text.split(LocalVisionInput.MARKER).size - 1)
    }
    @Test fun missingStoredImageCannotSilentlyBecomeTextOnly() {
        assertTrue(runCatching { LocalVisionInput.prepare(listOf(ChatMessage(Role.USER, images=listOf(ImageRef("lost", "image/png"))))) }.isFailure)
    }
    @Test fun tooManyImagesAndNonUserImagesReject() {
        assertTrue(runCatching { LocalVisionInput.prepare(listOf(ChatMessage(Role.USER, imageData=List(3){image()}))) }.isFailure)
        assertTrue(runCatching { LocalVisionInput.prepare(listOf(ChatMessage(Role.TOOL, imageData=listOf(image())))) }.isFailure)
    }
    @Test fun wrongMimeAndInvalidBase64Reject() {
        listOf(ImageData("text/plain", "AQ=="), ImageData("image/png", "!"), ImageData("image/png", "")).forEach { data ->
            assertTrue(runCatching { LocalVisionInput.prepare(listOf(ChatMessage(Role.USER, imageData=listOf(data)))) }.isFailure)
        }
    }
    @Test fun visionRamIncludesProjectorAndCanChangeFit() {
        val model = LocalModelCatalog.find("qwen35-2b")!!
        assertFalse(LocalModelCatalog.models.any { it.isProjector })
        assertEquals("qwen35-2b-vision", model.projectorId)
        val phone = LocalDeviceProfile(8 * GIB, 3 * GIB, 10 * GIB, "arm64-v8a", 8, false)
        assertTrue(phone.canLoad(model, 4096)); assertFalse(phone.canLoad(model, 4096, vision=true))
        assertEquals(LocalModelCatalog.find(model.projectorId!!)!!.bytes + 384L*1024*1024,
            model.estimatedMemory(4096, vision=true) - model.estimatedMemory(4096))
    }
    @Test fun oldLimitsDefaultVisionOffAndNewLimitsRoundTrip() {
        assertFalse(Json.decodeFromString<LocalModelLimits>("""{"context":4096,"input":3072,"output":1024,"threads":4}""").visionEnabled)
        val limits = LocalModelLimits(context=4096,input=3072,output=1024,threads=4,visionEnabled=true)
        assertTrue(Json.decodeFromString<LocalModelLimits>(Json.encodeToString(limits)).visionEnabled)
    }
}
