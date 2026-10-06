package com.androidharness.app.local

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ModelDownloadException(message: String, val retryable: Boolean = false) : IOException(message)

data class ModelStorageInfo(val installedBytes: Long, val partialBytes: Long, val paused: Boolean) {
    val totalBytes get() = installedBytes + partialBytes
}

/** Durable app-owned model files. APK updates never clear installed or unfinished data. */
class LocalModelStore(private val root: File, private val find: (String) -> LocalModelSpec? = LocalModelCatalog::find) {
    @Serializable private data class PartialState(val identity: String, val validator: String? = null)
    private val json = Json { ignoreUnknownKeys = true }
    init { check(root.isDirectory || root.mkdirs()) { "Cannot create local model storage." } }
    fun file(id: String): File {
        require(id.matches(Regex("[a-z0-9-]{1,80}")) && find(id) != null) { "Unknown local model." }
        return File(root, "$id.gguf")
    }
    fun partial(id: String) = File(root, "${file(id).name}.part")
    private fun stateFile(id: String) = File(root, "${file(id).name}.part.json")
    private fun requestFile(id: String) = File(root, "${file(id).name}.download")
    private fun pausedFile(id: String) = File(root, "${file(id).name}.paused")
    private fun installedIdentity(id: String) = File(root, "${file(id).name}.identity")
    private fun identity(model: LocalModelSpec) = "${model.bytes}:${model.sha256.ifEmpty { model.url }}"
    fun installed(model: LocalModelSpec) = file(model.id).let { target ->
        target.isFile && target.length() == model.bytes &&
            (!installedIdentity(model.id).exists() || runCatching { installedIdentity(model.id).readText() }.getOrDefault("") == identity(model))
    }
    fun info(id: String) = ModelStorageInfo(file(id).takeIf { it.isFile }?.length() ?: 0,
        partial(id).takeIf { it.isFile }?.length() ?: 0, pausedFile(id).exists())
    fun bytesUsed(): Long = root.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    fun request(id: String) {
        atomicWrite(requestFile(id), "requested")
        removeFile(pausedFile(id))
    }
    fun pause(id: String) { atomicWrite(pausedFile(id), "paused") }
    fun pending(id: String) = requestFile(id).exists() && !pausedFile(id).exists()
    fun clearPartial(id: String) { removeFile(partial(id)); removeFile(stateFile(id)); removeFile(File(root, stateFile(id).name + ".new")) }
    fun clearRequest(id: String) { listOf(requestFile(id), pausedFile(id)).forEach { removeFile(it); removeFile(File(root, it.name + ".new")) } }
    fun remove(id: String) {
        clearPartial(id); clearRequest(id)
        removeFile(file(id)); removeFile(installedIdentity(id)); removeFile(File(root, installedIdentity(id).name + ".new"))
        listOf(".json", ".json.bak", ".json.new").forEach { removeFile(File(root, id + it)) }
    }
    private fun removeFile(file: File) {
        check(!file.exists() || file.delete()) { "Could not delete ${file.name}. Storage has not been released." }
    }
    private fun state(id: String): PartialState? = runCatching {
        json.decodeFromString<PartialState>(stateFile(id).readText())
    }.getOrNull()
    fun preparePartial(model: LocalModelSpec): Long {
        val target = partial(model.id)
        if (state(model.id)?.identity != identity(model) || target.length() > model.bytes) clearPartial(model.id)
        if (!stateFile(model.id).exists()) atomicWrite(stateFile(model.id), json.encodeToString(PartialState(identity(model))))
        return target.length()
    }
    fun validator(id: String): String? = state(id)?.validator
    fun saveValidator(model: LocalModelSpec, validator: String?) =
        atomicWrite(stateFile(model.id), json.encodeToString(PartialState(identity(model), validator)))

    /** Appends only a validated HTTP range; interrupted transfers remain on disk for the next attempt. */
    fun install(model: LocalModelSpec, source: InputStream, isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit, offset: Long = 0L) {
        check(!file(model.id).exists()) { "A saved model already exists. Delete it explicitly before replacing it." }
        val target = partial(model.id)
        if (offset == 0L) { if (target.length() > 0) clearPartial(model.id); preparePartial(model) }
        check(offset in 0..model.bytes && target.length() == offset) { "Saved download changed; retry." }
        var count = offset
        var lastSync = offset
        try {
            FileOutputStream(target, offset > 0).use { output ->
                try {
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (isCancelled()) throw ModelDownloadException("Download paused; saved bytes kept.", true)
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (count + read > model.bytes) throw ModelDownloadException("Download exceeds expected size.")
                        output.write(buffer, 0, read)
                        count += read
                        if (count - lastSync >= 4L * 1024 * 1024) { output.fd.sync(); lastSync = count }
                        onProgress(count)
                    }
                } finally { output.flush(); output.fd.sync() }
            }
            if (isCancelled()) throw ModelDownloadException("Download paused; saved bytes kept.", true)
            if (count != model.bytes) throw ModelDownloadException("Connection interrupted; $count bytes saved for automatic resume.", true)
            verifyAndCommit(model, isCancelled)
        } catch (e: ModelDownloadException) {
            if (!e.retryable) { clearPartial(model.id); clearRequest(model.id) }
            throw e
        }
    }
    private fun verifyAndCommit(model: LocalModelSpec, isCancelled: () -> Boolean) {
        val target = partial(model.id)
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || !magic.contentEquals(byteArrayOf(71, 71, 85, 70))) throw ModelDownloadException("Not a GGUF model.")
            digest.update(magic)
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                if (isCancelled()) throw ModelDownloadException("Verification paused; completed download kept.", true)
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!(model.custom && model.sha256.isEmpty()) && hash != model.sha256) throw ModelDownloadException("Model checksum mismatch. Invalid download deleted; retry a fresh download.")
        if (model.custom) try {
            GgufMetadata.inspect(target.inputStream().use { readPrefix(it, GgufMetadata.PROBE_BYTES) })
        } catch (e: IllegalArgumentException) { throw ModelDownloadException(e.message ?: "Invalid GGUF header.") }
        if (isCancelled()) throw ModelDownloadException("Verification paused; saved bytes kept.", true)
        check(target.renameTo(file(model.id))) { "Cannot finalize model download. Saved bytes kept." }
        atomicWrite(installedIdentity(model.id), identity(model))
        removeFile(stateFile(model.id)); clearRequest(model.id)
    }
    private fun atomicWrite(target: File, text: String) {
        val temporary = File(root, target.name + ".new")
        temporary.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
