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
    val format: String = "GGUF",
    val localFilename: String? = null,
    val artifacts: List<LocalModelArtifact> = emptyList(),
) {
    val url get() = downloadUrl ?: "https://huggingface.co/$repository/resolve/$revision/$filename"
    val modelPage get() = sourcePage ?: "https://huggingface.co/$repository"
    val defaultContext get() = minOf(when (id) { "k2-horizon-37b" -> 8192; "k2-horizon-09b", "sharp-minicpm5-2b", "qwen35-2b", "minicpm5-2b-mnn", "k2-horizon-09b-mnn" -> 4096; else -> 2048 }, maxContext)
    fun estimatedMemory(context: Int, kvCache: KvCacheQuantization = KvCacheQuantization.Q8_0, vision: Boolean = false): Long =
        bytes + 640L * 1024 * 1024 + context * (if (format == "MNN_BUNDLE") kvBytesPerToken else kvCache.bytesForFp16(kvBytesPerToken)) +
            if (vision && projectorId != null) (LocalModelCatalog.projectors.first { it.id == projectorId }.bytes + 384L * 1024 * 1024) else 0
}

@Serializable
data class LocalModelArtifact(val filename:String,val bytes:Long,val sha256:String)

object LocalModelCatalog {
    val mnnModel = LocalModelSpec("minicpm5-2b-mnn", "MiniCPM5 2B · MNN INT4", "taobao-mnn/MiniCPM5-2B-MNN",
        "241c6ec2860324ec1388cfb944cd935047b9c678", "config.json", 1438648161L, "", 4, 43008,
        "Official MNN bundle with 4-bit weights and embeddings. CPU/Adreno performance experiment; compare with the original Q6 Sharp model separately.",
        maxContext=131072, format="MNN_BUNDLE", artifacts=listOf(
            LocalModelArtifact("config.json",547L,"61ab788e51723f637945ff85d1f3436211e30c116760b80beae7a5476a2692e7"),
            LocalModelArtifact("embeddings_int4.bin",167116800L,"7b25c1061a3151ed0bae2aa250dd8aa0eace40d0e121ff7478de10addea53bd4"),
            LocalModelArtifact("llm.mnn",1412936L,"79eef0f9dc5fbf216bb5204e7b18df5abc098873626e447b91e276395aa8b8c2"),
            LocalModelArtifact("llm.mnn.weight",1266571614L,"00932d66cd486b26a0fed246a498738ce8cad49edda662ee35df2983f4e6c072"),
            LocalModelArtifact("llm_config.json",9864L,"3a7f64a693a0f9977b2273d55e7af6bfab98a7bd36facda841be042f02133c8d"),
            LocalModelArtifact("tokenizer.mtok",3536400L,"4044ddc3ff48ea11abe6cc814c823066b5b9249ecbc93e21e065c0392ca9fee8")
        ))

    val k2MnnModel = LocalModelSpec("k2-horizon-09b-mnn", "K2 Horizon 0.9B · MNN INT8", "XYETHER/K2-Horizon-0.9B-MNN",
        "d0c9d33caf992e399ff934b0d535933ce9d6bf9e", "config.json", 1241023014L, "", 3, 57344,
        "Converted K2 INT8 weights and BF16 embeddings, with original tokenizer and IFM tools. CPU recommended: the Adreno low-precision test had an arithmetic mismatch. Start at 4K context.",
        maxContext=131072, format="MNN_BUNDLE", artifacts=listOf(
            LocalModelArtifact("config.json",539L,"9b994f4ebc17851c4e089fa74084a067e7afaa540fd7efb18fa5f8c2893d289e"),
            LocalModelArtifact("llm_config.json",53303L,"b2170a8206c2e97cdab8a9f783d482c7b36d11ee9ac363096ff619132f99280f"),
            LocalModelArtifact("llm.mnn",1000032L,"c99384d3113798d48bc18187a28e46e5e8f5ba0df94e13404e4a5192f7d86e26"),
            LocalModelArtifact("llm.mnn.weight",1040771486L,"b859ea81e68f230308c1eee17f25c39e1dbe655692a0f0b63f23495da569c891"),
            LocalModelArtifact("embeddings_bf16.bin",197394432L,"352326b14f6eab7780ef08ce56b985a41c6312560d9ef3bebd1a6b0d5c945c0a"),
            LocalModelArtifact("tokenizer.mtok",1803222L,"51699a4f5bef6a47f6517a94ac1c17fd684493266bc9d65fbd27a5a9e31cb2b6")
        ))
    val mnnModels get() = listOf(k2MnnModel,mnnModel)

    val projectors = listOf(
        LocalModelSpec("qwen35-2b-vision", "Qwen3.5 2B vision projector", "unsloth/Qwen3.5-2B-GGUF",
            "f6d5376be1edb4d416d56da11e5397a961aca8ae", "mmproj-BF16.gguf", 671372992,
            "f17196c0d8fc756bc65be60075bd4a359917eee8a438505639511727c585d3c2", 1, 0,
            "Optional BF16 image encoder for Qwen3.5 2B.", isProjector = true))
    val models = listOf(
        LocalModelSpec("qwen35-2b", "Qwen3.5 2B · UD-Q5_K_XL", "unsloth/Qwen3.5-2B-GGUF",
            "f6d5376be1edb4d416d56da11e5397a961aca8ae", "Qwen3.5-2B-UD-Q5_K_XL.gguf", 1466687744,
            "67bff9774bc55e44af2eee3c448dc054478b45c0607858fc6df5b336b55f36f4", 4, 49152,
            "Text and optional image understanding. Vision needs a separate projector. Fast skips thinking; Balanced and Deep enable it. KV estimate conservatively counts every layer, including hybrid layers.",
            maxContext = MAX_LOCAL_CONTEXT, projectorId = "qwen35-2b-vision"),
        LocalModelSpec("k2-horizon-09b", "K2-Horizon 0.9B · Q6_K", "IFM/K2-Horizon-0.9B-GGUF",
            "7833762821bbeeaf6f0e7519af8b0599b7056807", "K2-Horizon-1B-Q6_K.gguf",
            887487872, "b2fee1579df3fccd3d1784f30262b8ce76dfdb2c585310bd418c7c5d2eed256c",
            3, 57344, "Suggested for phones with less RAM. Same K2 reasoning profiles, in a smaller model. Start at 4K context.", maxContext = 131072),
        LocalModelSpec("sharp-minicpm5-2b", "Sharp MiniCPM5 2B · Q6_K_XL", "peculiar-ragdoll/Sharp-MiniCPM5-2B-GGUF",
            "040713a6c4da5e58e7512e8e483d315caef41b73", "Sharp-MiniCPM5-2B-Q6_K_XL.gguf",
            2198478176, "f67c038e7f1fbfb54ffa4bc5e1197cd2748700580cd1d1fd076e5998ac923ef4",
            6, 43008, "An intermediate local option. Fast skips thinking; Balanced and Deep enable thinking with different depth guidance. Start at 4K context.", maxContext = 131072),
        LocalModelSpec("k2-horizon-37b", "K2-Horizon 3.7B · Q5_0", "IFM/K2-Horizon-3.7B-GGUF",
            "1751cb49e31823f4401fc1d195db8a2c61a79783", "K2-Horizon-4B-Q5_0.gguf",
            3574840704, "0fe07d750aec041257cf86f16eb489bba1c029773584d7b83385e84a580baf42",
            8, 147456, "First-test model for local chat, web search, writing and research. Uses the IFM runtime on CPU. Recommended: 8 GB RAM or more.", maxContext = MAX_LOCAL_CONTEXT),
        LocalModelSpec("qwen-05b", "Qwen 2.5 0.5B", "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            "9217f5db79a29953eb74d5343926648285ec7e67", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491400032, "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            3, 24576, "Small download, basic chat. Limited coding ability."),
        LocalModelSpec("qwen-15b", "Qwen 2.5 1.5B", "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            "91cad51170dc346986eccefdc2dd33a9da36ead9", "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1117320736, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            4, 28672, "Better general chat. CPU speed depends on your phone."),
        LocalModelSpec("qwen-coder-15b", "Qwen 2.5 Coder 1.5B", "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            "f86cb2c1fa58255f8052cc32aeede1b7482d4361", "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            1117320768, "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046",
            4, 28672, "Code explanations and small snippets. Tool reliability depends on the model."),
        LocalModelSpec("qwen-3b", "Qwen 2.5 3B", "Qwen/Qwen2.5-3B-Instruct-GGUF",
            "7dabda4d13d513e3e842b20f0d435c732f172cbe", "qwen2.5-3b-instruct-q4_k_m.gguf",
            2104932768, "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            6, 73728, "Higher quality, larger download and more heat.", "Qwen Research License"),
    )
    fun find(id: String) = (models + projectors + mnnModels).firstOrNull { it.id == id }
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
