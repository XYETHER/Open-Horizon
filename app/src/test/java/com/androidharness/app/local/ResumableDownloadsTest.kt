package com.androidharness.app.local

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ResumableDownloadsTest {
    private val data = "GGUF".toByteArray() + ByteArray(700_000) { (it % 251).toByte() }
    private fun fixture(url: String) = LocalModelCatalog.models.first().copy(bytes = data.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }, downloadUrl = url)
    private val client = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private fun response(bytes: ByteArray = data) = MockResponse().setBody(Buffer().write(bytes))

    @Test fun `dropped HTTP transfer survives recreation and resumes a verified range`() = server { root, http ->
        val attempts = AtomicInteger()
        var receivedRange: String? = null
        var receivedValidator: String? = null
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (attempts.getAndIncrement() == 0) return response().setHeader("ETag", "\"fixture-v1\"")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                receivedRange = request.getHeader("Range")
                receivedValidator = request.getHeader("If-Range")
                val offset = requireNotNull(receivedRange).substringAfter("bytes=").substringBefore('-').toInt()
                return response(data.copyOfRange(offset, data.size)).setResponseCode(206)
                    .setHeader("Content-Range", "bytes $offset-${data.lastIndex}/${data.size}")
                    .setHeader("ETag", "\"fixture-v1\"")
            }
        }
        val model = fixture(http.url("/model").toString())
        val first = LocalModelStore(root)
        first.request(model.id)
        assertThrows(IOException::class.java) { ResumableModelDownloader(client, first).download(model, { false }) }
        val saved = first.partial(model.id).length()
        assertTrue("An interrupted body must be kept", saved in 1 until model.bytes)
        val reopened = LocalModelStore(root)
        ResumableModelDownloader(client, reopened).download(model, { false })
        assertEquals("bytes=$saved-", receivedRange)
        assertEquals("\"fixture-v1\"", receivedValidator)
        assertArrayEquals(data, reopened.file(model.id).readBytes())
        assertTrue(reopened.installed(model))
        assertFalse(reopened.pending(model.id))
        assertEquals(0L, reopened.info(model.id).partialBytes)
    }

    @Test fun `server ignoring Range safely restarts without appending duplicate bytes`() = server { root, http ->
        http.enqueue(response())
        val model = fixture(http.url("/model").toString())
        val store = LocalModelStore(root)
        assertThrows(ModelDownloadException::class.java) { store.install(model, data.copyOf(1000).inputStream(), { false }, {}) }
        ResumableModelDownloader(client, store).download(model, { false })
        assertArrayEquals(data, store.file(model.id).readBytes())
    }

    @Test fun `incorrect range is refused without losing saved bytes`() = server { root, http ->
        http.enqueue(response().setResponseCode(206).setHeader("Content-Range", "bytes 0-${data.lastIndex}/${data.size}"))
        val model = fixture(http.url("/model").toString())
        val store = LocalModelStore(root)
        val saved = data.copyOf(1000)
        assertThrows(ModelDownloadException::class.java) { store.install(model, saved.inputStream(), { false }, {}) }
        assertThrows(ModelDownloadException::class.java) { ResumableModelDownloader(client, store).download(model, { false }) }
        assertArrayEquals(saved, store.partial(model.id).readBytes())
        assertFalse(store.file(model.id).exists())
    }

    @Test fun `temporary server failure permits automatic retry and retains progress`() = server { root, http ->
        http.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        val model = fixture(http.url("/model").toString())
        val store = LocalModelStore(root)
        assertThrows(ModelDownloadException::class.java) { store.install(model, data.copyOf(1000).inputStream(), { false }, {}) }
        val error = assertThrows(ModelDownloadException::class.java) { ResumableModelDownloader(client, store).download(model, { false }) }
        assertTrue(error.retryable)
        assertEquals(1000L, store.info(model.id).partialBytes)
    }

    @Test fun `pause intent and installed models survive reopen while delete releases files`() = server { root, _ ->
        val model = fixture("https://example.org/model.gguf")
        val store = LocalModelStore(root)
        store.request(model.id)
        assertThrows(ModelDownloadException::class.java) { store.install(model, data.copyOf(1000).inputStream(), { false }, {}) }
        store.pause(model.id)
        val reopened = LocalModelStore(root)
        assertFalse(reopened.pending(model.id))
        assertTrue(reopened.info(model.id).paused)
        reopened.request(model.id)
        assertTrue(reopened.pending(model.id))
        assertFalse(reopened.info(model.id).paused)
        reopened.install(model, data.copyOfRange(1000, data.size).inputStream(), { false }, {}, 1000)
        val afterUpdate = LocalModelStore(root)
        assertTrue(afterUpdate.installed(model.copy(revision = "f".repeat(40))))
        assertFalse(afterUpdate.installed(model.copy(sha256 = "0".repeat(64))))
        assertArrayEquals(data, afterUpdate.file(model.id).readBytes())
        afterUpdate.remove(model.id)
        assertEquals(0L, afterUpdate.bytesUsed())
    }

    @Test fun `model migration preserves files while existing workspace paths stay stable`() = server { root, _ ->
        val legacyModels = File(root, "local-models").apply { mkdirs() }
        File(legacyModels, "model.gguf").writeText("installed")
        File(legacyModels, "model.gguf.part").writeText("partial")
        val models = AppStorageDirectories.models(root)
        assertEquals(File(root, "HorizonMNN/models"), models)
        assertEquals("installed", File(models, "model.gguf").readText())
        assertEquals("partial", File(AppStorageDirectories.models(root), "model.gguf.part").readText())
        File(root, "workspace").apply { mkdirs(); File(this, "index.html").writeText("page") }
        assertEquals(File(root, "workspace"), AppStorageDirectories.workspace(root))
        assertEquals("page", File(AppStorageDirectories.workspace(root), "index.html").readText())
    }

    @Test fun `conflicting migration keeps both existing folders without deleting data`() = server { root, _ ->
        File(root, "local-models").apply { mkdirs(); File(this, "old.gguf").writeText("old") }
        File(root, "HorizonMNN/models").apply { mkdirs(); File(this, "new.gguf").writeText("new") }
        val storage = AppStorageDirectories.models(root)
        assertEquals(File(root, "local-models"), storage)
        assertEquals("old", File(storage, "old.gguf").readText())
        assertEquals("new", File(root, "HorizonMNN/models/new.gguf").readText())
    }

    @Test fun `custom catalog keeps supported large context when restored after updates`() = server { root, _ ->
        val model = fixture("https://example.org/model.gguf").copy(id = "custom-" + "a".repeat(32),
            custom = true, maxContext = MAX_LOCAL_CONTEXT)
        CustomModelRegistry(root).add(model)
        val restored = CustomModelRegistry(root)
        assertNull(restored.loadError)
        assertEquals(model, restored.models.single())
    }
    private fun server(test: (File, MockWebServer) -> Unit) {
        val root = Files.createTempDirectory("model-resume").toFile()
        val http = MockWebServer()
        http.start()
        try { test(root, http) } finally { http.shutdown(); root.deleteRecursively() }
    }
}

