package com.androidharness.app.local
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class LocalProtocolsDeviceTest {
    @Test fun nativeVisionBridgeLoadsAndResolvesItsUpdatedSignature() {
        val handle = LocalNative.create()
        assertTrue(handle != 0L)
        try {
            // Deliberately invalid limits: reaches JNI without reading weights or using any GPU.
            val failure = runCatching { LocalNative.generate(handle, "not-a-model".toByteArray(), arrayOf("user"),
                arrayOf("Hello".toByteArray()), 0, 128, 16, 1, "Q8_0", "low", byteArrayOf(), emptyArray(),
                object : LocalNative.Callback { override fun onToken(bytes: ByteArray) = true }) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Invalid local model limits.", failure?.message)
            LocalNative.cancel(handle)
        } finally { LocalNative.destroy(handle) }
    }

    @Test fun androidParsersPreserveHtmlAndRejectPartialBatches() {
        val tools = listOf(ToolSchema("write_file", "Write", Json.parseToJsonElement("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"],"additionalProperties":false}""").jsonObject))
        val mini = """<function name="write_file"><param name="path">a.html</param><param name="content"><![CDATA[<h1>A & B</h1>]]></param></function>"""
        val qwen = "<tool_call>\n<function=write_file>\n<parameter=path>\na.html\n</parameter>\n<parameter=content>\n<h1>A & B</h1>\n</parameter>\n</function>\n</tool_call>"
        val a = MiniCpmProtocol.parseCalls(mini, tools, true).single()
        val b = Qwen35Protocol.parseCalls(qwen, tools, true).single()
        assertEquals(Json.parseToJsonElement(a.argumentsJson), Json.parseToJsonElement(b.argumentsJson))
        assertTrue(runCatching { MiniCpmProtocol.parseCalls(mini + "<function", tools, true) }.isFailure)
        assertTrue(runCatching { Qwen35Protocol.parseCalls(qwen + "<tool_call>", tools, true) }.isFailure)
    }
}
