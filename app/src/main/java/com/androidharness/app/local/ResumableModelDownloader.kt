package com.androidharness.app.local

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Transport is independent of Android scheduling so range/retry behavior can be tested. */
class ResumableModelDownloader(private val client: OkHttpClient, private val store: LocalModelStore) {
    fun download(model: LocalModelSpec, isStopped: () -> Boolean, onCall: (Call?) -> Unit = {},
        onProgress: (Long) -> Unit = {}) {
        var offset = store.preparePartial(model)
        if (offset == model.bytes) {
            store.install(model, byteArrayOf().inputStream(), isStopped, onProgress, offset)
            return
        }
        // Unhashed custom URLs need a server validator before combining bytes from different requests.
        if (offset > 0 && model.sha256.isEmpty() && store.validator(model.id) == null) {
            store.clearPartial(model.id); offset = store.preparePartial(model)
        }
        val request = Request.Builder().url(model.url).header("Accept-Encoding", "identity")
        if (offset > 0) {
            request.header("Range", "bytes=$offset-")
            store.validator(model.id)?.let { request.header("If-Range", it) }
        }
        val call = client.newCall(request.build())
        onCall(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw ModelDownloadException("Model server returned HTTP ${response.code}.",
                    response.code in setOf(408, 425, 429) || response.code >= 500)
                val body = response.body ?: throw ModelDownloadException("Empty model response.", true)
                var start = offset
                if (response.code == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                        ?: throw ModelDownloadException("Invalid resume response. Saved download kept.")
                    val (from, to, total) = range.destructured
                    if (from.toLong() != start || total.toLong() != model.bytes || to.toLong() !in start until model.bytes ||
                        body.contentLength() >= 0 && body.contentLength() != to.toLong() - start + 1)
                        throw ModelDownloadException("Resume range does not match the saved model. Saved download kept.")
                    val previous = store.validator(model.id)
                    val current = response.header("ETag")?.takeIf { !it.startsWith("W/") } ?: response.header("Last-Modified")
                    if (start > 0 && previous != null && current != null && previous != current)
                        throw ModelDownloadException("Model changed on the server. Delete the unfinished download before retrying.")
                } else if (response.code == 200) {
                    // Server ignored Range or If-Range detected changed content: a complete response starts at zero.
                    if (body.contentLength() >= 0 && body.contentLength() != model.bytes)
                        throw ModelDownloadException("Unexpected model size. Saved download kept.")
                    if (start > 0) { store.clearPartial(model.id); store.preparePartial(model); start = 0 }
                } else throw ModelDownloadException("Unsupported model response HTTP ${response.code}.")
                val validator = response.header("ETag")?.takeIf { !it.startsWith("W/") } ?: response.header("Last-Modified")
                store.saveValidator(model, validator)
                onProgress(start)
                body.byteStream().use { store.install(model, it, isStopped, onProgress, start) }
            }
        } finally { onCall(null) }
    }
}
