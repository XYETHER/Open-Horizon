package com.androidharness.app.local

import androidx.annotation.Keep

@Keep
object LocalNative {
    init { System.loadLibrary("harness_local") }
    external fun configureVulkanCompatibility(disableF16: Boolean)
    external fun evictSession()
    external fun runtimeInfo(): String
    external fun create(): Long
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
    external fun generate(
        handle: Long,
        path: ByteArray,
        roles: Array<String>,
        contents: Array<ByteArray>,
        context: Int,
        input: Int,
        output: Int,
        threads: Int,
        kvCache: String,
        reasoningEffort: String,
        projector: ByteArray,
        images: Array<ByteArray>,
        callback: Callback,
        gpuLayers: Int = 0,
        fastCpu: Boolean = false,
        reuseSession: Boolean = false,
        compute: String = "cpu",
        precision: String = "low",
        memory: String = "low",
        attentionMode: Int = 10,
        preparedTokens: IntArray? = null,
    ): IntArray

    @Keep
    interface Callback {
        fun onToken(bytes: ByteArray): Boolean
        fun onTokenId(id: Int): Boolean = error("Token-ID callback requires an original tokenizer")
    }
}
