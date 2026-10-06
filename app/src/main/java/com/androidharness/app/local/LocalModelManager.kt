package com.androidharness.app.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocalModelManager(private val context: Context) {
    private val root = AppStorageDirectories.models(context.noBackupFilesDir)
    val storagePath get() = root.absolutePath
    fun storageBytes() = store.bytesUsed()
    fun storageInfo(id: String) = store.info(id)
    private val custom = CustomModelRegistry(root)
    private val _models = MutableStateFlow(LocalModelCatalog.models)
    val models: kotlinx.coroutines.flow.StateFlow<List<LocalModelSpec>> = _models.asStateFlow()
    val catalogError get() = custom.loadError
    fun find(id: String): LocalModelSpec? = models.value.firstOrNull { it.id == id } ?: LocalModelCatalog.projectors.firstOrNull { it.id == id }
    private val store = LocalModelStore(root, ::find)
    private val resolver = CustomModelResolver()

    suspend fun resolveLink(link: String) = withContext(Dispatchers.IO) { resolver.resolve(link) }
    suspend fun inspectCustom(model: LocalModelSpec) = withContext(Dispatchers.IO) { resolver.inspect(model) }
    suspend fun downloadCustom(model: LocalModelSpec) = withContext(Dispatchers.IO) {
        require(model.id == "k2-horizon-09b-q5" && model == LocalModelCatalog.models.single()) { "Only the built-in K2 Q5 model is supported" }
        lifecycle.withLock {
            // Once installed, never replace its spec with metadata from a later URL probe.
            if (find(model.id)?.let(store::installed) != true) {
                custom.add(model)
                _models.value = LocalModelCatalog.models
            }
            download(model.id)
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val lifecycle = Mutex()
    private val inference = Mutex()
    private val gate = Any()
    private val cancelled = AtomicBoolean(false)
    private var downloadCall: Call? = null
    private var handle = 0L
    private var activeId: String? = null
    private val blocked = mutableSetOf<String>()
    private val _installed = MutableStateFlow((models.value + LocalModelCatalog.projectors).filter(store::installed).map { it.id }.toSet())
    val installed = _installed.asStateFlow()
    private val _limitsRevision = MutableStateFlow(0L)
    val limitsRevision = _limitsRevision.asStateFlow()
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status = _status.asStateFlow()
    private val _running = MutableStateFlow<String?>(null)
    val running = _running.asStateFlow()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followSslRedirects(false).build()

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            lifecycle.withLock {
                (models.value + LocalModelCatalog.projectors).forEach { model ->
                    runCatching {
                        if (store.installed(model)) store.clearRequest(model.id)
                        else if (store.file(model.id).exists()) setStatus(model.id, "Saved model kept. Delete explicitly to replace this model version.")
                        else if (store.pending(model.id)) download(model.id)
                    }.onFailure { setStatus(model.id, it.message ?: "Storage cleanup failed") }
                }
                publishInstalled()
            }
        }
    }

    fun visionReady(id: String): Boolean = find(id)?.projectorId?.let { projector ->
        limits(id).visionEnabled && store.installed(requireNotNull(find(projector)))
    } ?: false

    fun device(): LocalDeviceProfile {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return LocalDeviceProfile(info.totalMem, info.availMem, StatFs(root.path).availableBytes,
            if (Process.is64Bit()) Build.SUPPORTED_ABIS.firstOrNull().orEmpty() else "32-bit",
            Runtime.getRuntime().availableProcessors(), info.lowMemory)
    }

    fun configs(ids: Set<String> = installed.value) = models.value.filter { it.id in ids }.map {
        ProviderConfig(LocalModelCatalog.PROVIDER_PREFIX + it.id, "Local · ${it.title}", ProviderType.OPENAI_COMPAT, "local://${it.id}", LocalModelCatalog.PROVIDER_PREFIX + it.id)
    }

    fun limits(id: String): LocalModelLimits {
        val model = requireNotNull(find(id))
        return runCatching {
            json.decodeFromString<LocalModelLimits>(File(root, "$id.json").readText()).also { it.validate(); require(it.context <= model.maxContext) }
        }.getOrElse {
            initialLocalLimits(model, device())
        }
    }

    suspend fun saveLimits(id: String, limits: LocalModelLimits) = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            val model = requireNotNull(find(id))
            limits.validate()
            require(limits.context <= model.maxContext) { "Context exceeds the model training limit." }
            require(!limits.visionEnabled || model.projectorId?.let { store.installed(requireNotNull(find(it))) } == true) { "Download the vision projector before enabling Vision." }
            require(device().canAttempt(model, limits.context)) { "Unsupported architecture or model context." }
            val target = android.util.AtomicFile(File(root, "$id.json"))
            val output = target.startWrite()
            try {
                output.write(json.encodeToString(LocalModelLimits.serializer(), limits).toByteArray())
                target.finishWrite(output)
                _limitsRevision.value += 1
            } catch (e: Exception) {
                target.failWrite(output)
                throw e
            }
        }
    }

    fun download(id: String) {
        require(find(id) != null)
        store.request(id)
        synchronized(gate) { blocked.remove(id) }
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocalModelDownloadWorker>().setInputData(workDataOf("modelId" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("local-model-download").addTag("model:$id").build())
    }

    internal suspend fun install(id: String, stopped: () -> Boolean, progress: suspend (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            lifecycle.withLock {
                val model = requireNotNull(find(id))
                if (store.installed(model)) { publishInstalled(); return@withLock }
                val settings = limits(id)
                check(device().canAttempt(model, settings.context)) { "Unsupported architecture or model context." }
                check(!store.file(id).exists()) { "Saved model kept. Delete it explicitly before replacing it." }
                val savedBytes = store.preparePartial(model)
                check(device().freeStorage >= model.bytes - savedBytes + 256L * 1024 * 1024) { "Not enough free storage to finish this download. Saved bytes kept." }
                synchronized(gate) {
                    check(id !in blocked && !stopped()) { "Download cancelled." }
                    cancelled.set(false)
                }
                setStatus(id, if (savedBytes > 0) "Resuming saved download" else "Connecting")
                synchronized(gate) { activeId = id }
                try {
                    var lastUpdate = 0L
                    ResumableModelDownloader(client, store).download(model, { cancelled.get() || stopped() }, { call ->
                        synchronized(gate) {
                            if (call != null) check(id !in blocked && !stopped() && !cancelled.get()) { "Download paused." }
                            downloadCall = call
                        }
                    }) { count ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 500 || count == model.bytes) {
                            lastUpdate = now
                            setStatus(id, if (count == model.bytes) "Verifying" else "Downloading ${count * 100 / model.bytes}% · saved automatically")
                            kotlinx.coroutines.runBlocking { progress(count, model.bytes) }
                        }
                    }
                    publishInstalled()
                    progress(model.bytes, model.bytes)
                    setStatus(id, "Installed · kept across app updates")
                } catch (e: Exception) {
                    setStatus(id, if (cancelled.get() || stopped()) "Stopped · downloaded bytes kept" else e.message ?: "Download interrupted; saved bytes kept")
                    throw e
                } finally {
                    synchronized(gate) { if (activeId == id) { downloadCall = null; activeId = null } }
                }
            }
        }

    internal fun downloadRetrying(id: String, message: String) = setStatus(id, "$message · saved bytes kept; automatic retry when connected")
    internal fun downloadFailed(id: String, message: String) {
        if (find(id) != null) store.pause(id)
        setStatus(id, message)
    }

    fun pauseDownload(id: String) {
        store.pause(id)
        cancelDownload(id)
        setStatus(id, "Paused · downloaded bytes kept. Tap Resume to continue.")
    }

    internal fun interruptDownload(id: String) {
        synchronized(gate) {
            if (activeId == id) { cancelled.set(true); downloadCall?.cancel() }
        }
    }

    fun cancelDownload(id: String) {
        synchronized(gate) {
            blocked.add(id)
            if (activeId == id) { cancelled.set(true); downloadCall?.cancel() }
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        require(find(id) != null)
        cancelDownload(id)
        val parent = models.value.firstOrNull { it.projectorId == id }
        if (parent != null) { stop(parent.id); saveLimits(parent.id, limits(parent.id).copy(visionEnabled = false)) }
        stop(id)
        setStatus(id, "Stopping and removing")
        lifecycle.withLock {
            inference.withLock {
                try {
                    store.remove(id)
                    if (find(id)?.custom == true) {
                        custom.remove(id)
                        _models.value = LocalModelCatalog.models
                    }
                    publishInstalled()
                    setStatus(id, "Removed")
                } catch (e: Exception) {
                    publishInstalled()
                    setStatus(id, e.message ?: "Removal failed")
                    throw e
                }
            }
        }
    }

    fun stop(id: String? = null, releaseSession: Boolean = true) {
        synchronized(gate) {
            if (handle != 0L && (id == null || running.value == id)) LocalNative.cancel(handle)
        }
        if(releaseSession) runCatching { LocalNative.evictSession() }
    }

    suspend fun generate(
        id: String, roles: Array<String>, contents: Array<ByteArray>, outputCap: Int,
        reasoningEffort: String,
        images: Array<ByteArray> = emptyArray(),
        emitBytes: suspend (ByteArray) -> Unit,
    ): IntArray = inference.withLock {
        val model = requireNotNull(find(id)) { "Unknown local model." }
        val limits = withContext(Dispatchers.IO) { limits(id) }
        check(images.isEmpty() || visionReady(id)) { "Enable Vision and download its projector in this model's context settings before sending images." }
        val projectorPath = if (images.isNotEmpty()) store.file(checkNotNull(model.projectorId)).absolutePath else ""
        limits.validate()
        synchronized(gate) {
            check(id !in blocked && store.installed(model)) { "Local model is not installed. Download it in Settings > Local models." }
            check(device().canAttempt(model, limits.context)) { "Unsupported architecture or model context." }
            handle = LocalNative.create()
            _running.value = id
        }
        try {
            coroutineScope {
                val bytes = Channel<ByteArray>(32)
                var result: IntArray? = null
                val worker = launch(Dispatchers.IO) {
                    try {
                        result = LocalNative.generate(handle, store.file(id).absolutePath.toByteArray(Charsets.UTF_8), roles, contents,
                            limits.context, limits.input, minOf(limits.output, outputCap.coerceAtLeast(1)),
                            minOf(limits.threads, device().cores.coerceAtLeast(1)), limits.kvCache.name, reasoningEffort, projectorPath.toByteArray(Charsets.UTF_8), images, object : LocalNative.Callback {
                                override fun onToken(data: ByteArray): Boolean = kotlinx.coroutines.runBlocking {
                                    try { bytes.send(data); true } catch (_: CancellationException) { false }
                                }
                            }, reuseSession=id=="k2-horizon-09b-q5")
                    } finally { bytes.close() }
                }
                try {
                    for (data in bytes) emitBytes(data)
                    worker.join()
                    checkNotNull(result)
                } finally {
                    stop(id, releaseSession=false)
                    bytes.cancel()
                    withContext(NonCancellable) { worker.join() }
                }
            }
        } finally {
            synchronized(gate) {
                LocalNative.destroy(handle)
                handle = 0
                _running.value = null
            }
        }
    }

    private fun publishInstalled() { _installed.value = (models.value + LocalModelCatalog.projectors).filter(store::installed).map { it.id }.toSet() }
    private fun setStatus(id: String, text: String) { _status.update { it + (id to text) } }
    private fun workName(id: String) = "local-model-$id"
}
