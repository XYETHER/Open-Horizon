package com.androidharness.app.local

import kotlinx.serialization.Serializable

const val GIB = 1_073_741_824L
const val MAX_LOCAL_CONTEXT = 262_144

@Serializable
enum class KvCacheQuantization(private val blockBytes: Long) {
    Q8_0(34),
    Q5_0(22);

    // Each block stores 32 values; the source estimate includes 64 bytes of FP16 values.
    fun bytesForFp16(fp16Bytes: Long): Long = (fp16Bytes * blockBytes + 63) / 64
}

@Serializable
data class LocalModelSpec(
    val id: String,
    val title: String,
    val repository: String,
    val revision: String,
    val filename: String,
    val bytes: Long,
    val sha256: String,
    val minimumRamGiB: Int,
    val kvBytesPerToken: Long,
    val description: String,
    val license: String = "Apache-2.0",
    val downloadUrl: String? = null,
    val sourcePage: String? = null,
    val custom: Boolean = false,
    val maxContext: Int = 8192,
    val projectorId: String? = null,
    val isProjector: Boolean = false,
) {
    val url get() = downloadUrl ?: "https://huggingface.co/$repository/resolve/$revision/$filename"
    val modelPage get() = sourcePage ?: "https://huggingface.co/$repository"
    val defaultContext get() = minOf(4096, maxContext)
    fun estimatedMemory(context: Int, kvCache: KvCacheQuantization = KvCacheQuantization.Q8_0, vision: Boolean = false): Long =
        bytes + 640L * 1024 * 1024 + context * kvCache.bytesForFp16(kvBytesPerToken) +
            if (vision && projectorId != null) (LocalModelCatalog.projectors.first { it.id == projectorId }.bytes + 384L * 1024 * 1024) else 0
}

object LocalModelCatalog {
    val projectors = emptyList<LocalModelSpec>()
    val models = listOf(
        LocalModelSpec("k2-horizon-09b-q5", "K2 Horizon 0.9B · Q5_K_M", "IFM/K2-Horizon-0.9B-GGUF",
            "7833762821bbeeaf6f0e7519af8b0599b7056807", "K2-Horizon-1B-Q5_K_M.gguf",
            773482880, "a2efdbbd55f8f6e2be983dcf570f406f0e4703d7027a4019b2388e686865e7fd",
            3, 57344, "K2 Horizon 0.9B with Q5_K_M GGUF weights. Local CPU inference with Q8 or Q5 KV cache. Start at 4K context.", maxContext = 131072),
    )
    fun find(id: String) = (models + projectors).firstOrNull { it.id == id }
    const val PROVIDER_PREFIX = "local-model:"
    fun isLocal(id: String) = id.startsWith(PROVIDER_PREFIX)
}

@Serializable
data class LocalModelLimits(
    val context: Int = 2048,
    val input: Int = 1536,
    val output: Int = 512,
    val threads: Int = 4,
    val kvCache: KvCacheQuantization = KvCacheQuantization.Q8_0,
    val visionEnabled: Boolean = false,
) {
    fun validate() {
        require(context in 512..MAX_LOCAL_CONTEXT) { "Context must be 512 to 262144 tokens." }
        require(input >= 128 && output in 16..4096) { "Input must be at least 128; output must be 16 to 4096 tokens." }
        require(input.toLong() + output <= context) { "Input plus output must fit inside context." }
        require(threads in 1..8) { "CPU threads must be 1 to 8." }
    }
}

data class LocalDeviceProfile(
    val totalRam: Long,
    val availableRam: Long,
    val freeStorage: Long,
    val abi: String,
    val cores: Int,
    val lowMemory: Boolean,
) {
    val supported get() = abi == "arm64-v8a" || abi == "x86_64"
    val rank get() = when {
        !supported -> "Unsupported architecture"
        totalRam < 3 * GIB -> "Limited memory"
        totalRam < 4 * GIB -> "Basic"
        totalRam < 6 * GIB -> "Balanced"
        else -> "Higher memory"
    }
    /** Memory is advisory: allow attempts on supported hardware within trained limits. */
    fun canAttempt(model: LocalModelSpec, context: Int = model.defaultContext): Boolean =
        supported && context in 512..model.maxContext

    fun fits(model: LocalModelSpec, context: Int = model.defaultContext,
        kvCache: KvCacheQuantization = KvCacheQuantization.Q8_0, vision: Boolean = false): Boolean =
        supported && context in 512..model.maxContext && totalRam >= model.minimumRamGiB * GIB * 9 / 10 &&
            model.estimatedMemory(context, kvCache, vision) <= totalRam * 55 / 100
    fun canLoad(model: LocalModelSpec, context: Int = model.defaultContext,
        kvCache: KvCacheQuantization = KvCacheQuantization.Q8_0, vision: Boolean = false): Boolean = fits(model, context, kvCache, vision) &&
        !lowMemory && availableRam >= model.estimatedMemory(context, kvCache, vision) + 256L * 1024 * 1024
}

internal fun initialLocalLimits(model: LocalModelSpec, profile: LocalDeviceProfile): LocalModelLimits {
    val context = listOf(model.defaultContext, 4096, 2048, 1024).distinct()
        .firstOrNull { it <= model.defaultContext && profile.fits(model, it) } ?: model.defaultContext
    return LocalModelLimits(context = context, input = context * 3 / 4,
        output = context / 4, threads = profile.cores.coerceIn(1, 4))
}

// Keep installed/downloading entries accessible even when available RAM changes.
fun visibleLocalModels(models: List<LocalModelSpec>, device: LocalDeviceProfile, showAll: Boolean,
    retained: Set<String>, contexts: Map<String, Int> = emptyMap(),
    cacheQuantizations: Map<String, KvCacheQuantization> = emptyMap()): List<LocalModelSpec> =
    models.filter { showAll || it.id in retained || device.canLoad(it, contexts[it.id] ?: it.defaultContext,
        cacheQuantizations[it.id] ?: KvCacheQuantization.Q8_0) }
