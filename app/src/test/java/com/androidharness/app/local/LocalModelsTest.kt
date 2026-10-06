package com.androidharness.app.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class LocalModelsTest {
    @Test fun `K2 Q5 weights with Q8 cache fit S24 Ultra and reduce context for eight GB`() {
        val model = LocalModelCatalog.find("k2-horizon-37b")!!
        val phone = LocalDeviceProfile(12 * GIB, 7 * GIB, 10 * GIB, "arm64-v8a", 8, false)
        val limits = initialLocalLimits(model, phone)
        limits.validate()
        assertEquals(8192, limits.context)
        assertEquals(KvCacheQuantization.Q8_0, limits.kvCache)
        assertTrue(phone.canLoad(model, limits.context))
        val smaller = phone.copy(totalRam = 8 * GIB, availableRam = 5 * GIB)
        val reduced = initialLocalLimits(model, smaller)
        assertEquals(4096, reduced.context)
        assertTrue(smaller.canLoad(model, reduced.context))
        assertFalse(phone.copy(availableRam = 2 * GIB).canLoad(model, limits.context))
    }

    @Test fun `cache format persists and old settings default to Q8`() {
        val oldSettings = """{"context":4096,"input":3072,"output":1024,"threads":4}"""
        val migrated = Json.decodeFromString(LocalModelLimits.serializer(), oldSettings)
        assertEquals(KvCacheQuantization.Q8_0, migrated.kvCache)
        val q5 = migrated.copy(kvCache = KvCacheQuantization.Q5_0)
        val saved = Json.encodeToString(LocalModelLimits.serializer(), q5)
        assertEquals(q5, Json.decodeFromString(LocalModelLimits.serializer(), saved))
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(LocalModelLimits.serializer(), """{"kvCache":"Q4_0"}""")
        }
    }

    @Test fun `Q5 reduces real block storage and fit checks use the selected cache`() {
        val model = LocalModelCatalog.find("k2-horizon-37b")!!
        val base = model.bytes + 640L * 1024 * 1024
        assertEquals(612L * 1024 * 1024, model.estimatedMemory(8192, KvCacheQuantization.Q8_0) - base)
        assertEquals(396L * 1024 * 1024, model.estimatedMemory(8192, KvCacheQuantization.Q5_0) - base)
        val available = model.estimatedMemory(8192, KvCacheQuantization.Q5_0) + 256L * 1024 * 1024
        val phone = LocalDeviceProfile(12 * GIB, available, 10 * GIB, "arm64-v8a", 8, false)
        assertTrue(phone.canLoad(model, 8192, KvCacheQuantization.Q5_0))
        assertFalse(phone.canLoad(model, 8192, KvCacheQuantization.Q8_0))
        assertTrue(visibleLocalModels(listOf(model), phone, false, emptySet()).isEmpty())
        assertEquals(listOf(model), visibleLocalModels(listOf(model), phone, false, emptySet(),
            cacheQuantizations = mapOf(model.id to KvCacheQuantization.Q5_0)))
    }
    @Test fun `catalog uses pinned revisions and checksums`() {
        assertEquals(LocalModelCatalog.models.size, LocalModelCatalog.models.map { it.id }.distinct().size)
        LocalModelCatalog.models.forEach {
            assertTrue(it.revision.matches(Regex("[a-f0-9]{40}")))
            assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue(it.url.startsWith("https://huggingface.co/"))
            assertTrue(it.filename.endsWith(".gguf"))
        }
    }

    @Test fun `limits reserve output space and reject overflow`() {
        LocalModelLimits().validate()
        listOf(LocalModelLimits(context = 0), LocalModelLimits(input = Int.MAX_VALUE),
            LocalModelLimits(output = 0), LocalModelLimits(input = 2048, output = 512),
            LocalModelLimits(threads = 9), LocalModelLimits(context = MAX_LOCAL_CONTEXT + 1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
    }

    @Test fun `large model context is valid but a twelve GB phone refuses excessive caches`() {
        val model = LocalModelCatalog.find("k2-horizon-37b")!!
        val phone = LocalDeviceProfile(12 * GIB, 7 * GIB, 10 * GIB, "arm64-v8a", 8, false)
        LocalModelLimits(context = MAX_LOCAL_CONTEXT, input = MAX_LOCAL_CONTEXT - 2048, output = 2048).validate()
        assertEquals(MAX_LOCAL_CONTEXT, model.maxContext)
        assertTrue(phone.canLoad(model, 16 * 1024, KvCacheQuantization.Q8_0))
        assertTrue(phone.canLoad(model, 32 * 1024, KvCacheQuantization.Q8_0))
        for (cache in KvCacheQuantization.entries) {
            assertFalse(phone.fits(model, MAX_LOCAL_CONTEXT, cache))
            assertFalse(phone.canLoad(model, MAX_LOCAL_CONTEXT, cache))
        }
        val capacityOnly = phone.copy(availableRam = 2 * GIB)
        assertTrue(capacityOnly.fits(model, 16 * 1024))
        assertFalse(capacityOnly.canLoad(model, 16 * 1024))
    }

    @Test fun `recommendations distinguish architecture memory and live availability`() {
        val small = LocalModelCatalog.find("qwen-05b")!!
        val profile = LocalDeviceProfile(4 * GIB, 3 * GIB, 8 * GIB, "arm64-v8a", 8, false)
        assertTrue(profile.fits(small))
        assertTrue(profile.canLoad(small, 2048))
        assertFalse(profile.copy(abi = "armeabi-v7a").fits(small))
        assertFalse(profile.copy(totalRam = 2 * GIB).fits(small))
        assertFalse(profile.copy(availableRam = GIB).canLoad(small, 2048))
        assertFalse(profile.copy(lowMemory = true).canLoad(small, 2048))
        assertFalse(profile.fits(LocalModelCatalog.models.last()))
        assertTrue(small.estimatedMemory(8192) > small.estimatedMemory(2048))
    }

    @Test fun `verified install commits atomically and remove deletes real files`() = temporary { root ->
        val data = "GGUFfixture".toByteArray()
        val model = fixture(data)
        val store = LocalModelStore(root)
        store.install(model, data.inputStream(), { false }, {})
        assertTrue(store.installed(model))
        assertArrayEquals(data, store.file(model.id).readBytes())
        assertFalse(store.partial(model.id).exists())
        File(root, "${model.id}.json").writeText("settings")
        store.remove(model.id)
        assertFalse(store.file(model.id).exists())
        assertFalse(File(root, "${model.id}.json").exists())
        store.remove(model.id)
    }

    @Test fun `integrity failures and oversize leave no model files`() = temporary { root ->
        val data = "GGUFfixture".toByteArray()
        val model = fixture(data)
        val store = LocalModelStore(root)
        for ((spec, body) in listOf(model.copy(sha256 = "0".repeat(64)) to data,
            model to (data + 1), fixture("HTMLfixture".toByteArray()) to "HTMLfixture".toByteArray())) {
            assertThrows(ModelDownloadException::class.java) { store.install(spec, body.inputStream(), { false }, {}) }
            assertFalse(store.file(model.id).exists())
            assertFalse(store.partial(model.id).exists())
        }
    }

    @Test fun `cancellation keeps partial bytes and a new store resumes them`() = temporary { root ->
        val data = "GGUFfixture".toByteArray()
        val model = fixture(data)
        val store = LocalModelStore(root)
        var cancelled = false
        assertThrows(ModelDownloadException::class.java) {
            store.install(model, data.inputStream(), { cancelled }, { cancelled = true })
        }
        assertFalse(store.file(model.id).exists())
        assertArrayEquals(data, store.partial(model.id).readBytes())
        val reopened = LocalModelStore(root)
        reopened.install(model, byteArrayOf().inputStream(), { false }, {}, reopened.preparePartial(model))
        assertTrue(reopened.installed(model))
        assertArrayEquals(data, reopened.file(model.id).readBytes())
    }

    @Test fun `store rejects path traversal and preserves installed files`() = temporary { root ->
        val store = LocalModelStore(root)
        assertThrows(IllegalArgumentException::class.java) { store.file("../escape") }
        val data = "GGUFfixture".toByteArray()
        val model = fixture(data)
        store.install(model, data.inputStream(), { false }, {})
        assertThrows(IllegalStateException::class.java) { store.install(model, data.inputStream(), { false }, {}) }
        assertArrayEquals(data, store.file(model.id).readBytes())
    }

    @Test fun `local inference reports zero API cost without changing cloud pricing`() {
        val local = com.androidharness.app.llm.ModelPrices.costFor("local-model:qwen-05b")
        assertEquals(0.0, local.input, 0.0)
        assertEquals(0.0, local.output, 0.0)
        assertTrue(com.androidharness.app.llm.ModelPrices.costFor("qwen-05b").input > 0)
    }

    @Test fun `UTF8 decoding keeps split multibyte tokens intact`() {
        val text = "Hello 世界 😀 café"
        val decoder = Utf8TokenDecoder()
        val result = buildString {
            text.toByteArray().forEach { append(decoder.append(byteArrayOf(it))) }
            append(decoder.finish())
        }
        assertEquals(text, result)
    }

    private fun fixture(bytes: ByteArray) = LocalModelCatalog.models.first().copy(bytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("local-model-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
