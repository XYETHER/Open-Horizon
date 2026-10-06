package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.androidharness.app.AppContainer
import com.androidharness.app.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
internal fun LocalModelsSection(container: AppContainer) {
    val manager = container.localModels
    val catalog by manager.models.collectAsStateWithLifecycle()
    val installed by manager.installed.collectAsStateWithLifecycle()
    val statuses by manager.status.collectAsStateWithLifecycle()
    val running by manager.running.collectAsStateWithLifecycle()
    val compute by manager.mnnSettings.mode.collectAsStateWithLifecycle()
    val work by remember { WorkManager.getInstance(container.appContext).getWorkInfosByTagFlow("local-model-download") }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var device by remember { mutableStateOf(manager.device()) }
    var customDialog by remember { mutableStateOf(false) }
    var contexts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var cacheQuantizations by remember { mutableStateOf<Map<String, KvCacheQuantization>>(emptyMap()) }
    var storageInfos by remember { mutableStateOf<Map<String, ModelStorageInfo>>(emptyMap()) }
    var storageBytes by remember { mutableLongStateOf(0L) }
    var showStoragePaths by remember { mutableStateOf(false) }
    var modelLimits by remember { mutableStateOf<Map<String, LocalModelLimits>>(emptyMap()) }
    var showAll by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<LocalModelSpec?>(null) }
    var removing by remember { mutableStateOf<LocalModelSpec?>(null) }
    var editing by remember { mutableStateOf<LocalModelSpec?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    suspend fun refreshLimits() {
        val savedLimits = withContext(Dispatchers.IO) {
            catalog.associate { it.id to runCatching { manager.limits(it.id) }.getOrNull() }
        }
        contexts = catalog.associate { it.id to (savedLimits[it.id]?.context ?: it.defaultContext) }
        cacheQuantizations = savedLimits.mapValues { (_, limits) -> limits?.kvCache ?: KvCacheQuantization.Q8_0 }
        modelLimits = savedLimits.mapNotNull { (id, limits) -> limits?.let { id to it } }.toMap()
        val stored = withContext(Dispatchers.IO) { (catalog + LocalModelCatalog.projectors).associate { it.id to manager.storageInfo(it.id) } to manager.storageBytes() }
        storageInfos = stored.first
        storageBytes = stored.second
    }
    LaunchedEffect(catalog, installed) { while (true) {
        refreshLimits()
        device = manager.device()
        delay(3000)
    } }
    val retained = installed + storageInfos.filterValues { it.totalBytes > 0 }.keys + work.filter { !it.state.isFinished }.flatMap { it.tags }
        .filter { it.startsWith("model:") }.map { it.removePrefix("model:") } + setOf("k2-horizon-37b", "k2-horizon-09b", "sharp-minicpm5-2b", "qwen35-2b")
    val models = catalog.filterNot { it.custom }
    val hiddenCount = if (showAll) 0 else catalog.size - models.size
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${size(device.totalRam)} RAM · ${size(device.availableRam)} available", style = MaterialTheme.typography.titleSmall)
            Text("Model storage · ${size(storageBytes)} used · ${size(device.freeStorage)} free", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("HorizonMNN folders keep models and files across app updates. Uninstalling or clearing app data removes them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showStoragePaths = !showStoragePaths }) { Text(if (showStoragePaths) "Hide storage folders" else "Show storage folders") }
            if (showStoragePaths) {
                Text("Models: ${manager.storagePath}\nFiles: ${container.workspace.appPrivateRoot.absolutePath}", style = MaterialTheme.typography.bodySmall)
            }
            if (!device.supported) Text("Local inference requires arm64-v8a or x86_64.", color = MaterialTheme.colorScheme.error)
        }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    MnnControls(manager.mnnSettings)
    models.forEach { model ->
        val isK2 = model.format == "MNN_BUNDLE" || model.id in setOf("k2-horizon-37b", "k2-horizon-09b", "sharp-minicpm5-2b", "qwen35-2b")
        val hasModel = model.id in installed
        val downloading = work.any { info -> !info.state.isFinished && info.tags.contains("model:${model.id}") }
        val failed = work.firstOrNull { it.tags.contains("model:${model.id}") && it.state == WorkInfo.State.FAILED }
            ?.outputData?.getString("error")
        val context = contexts[model.id] ?: model.defaultContext
        val kvCache = cacheQuantizations[model.id] ?: KvCacheQuantization.Q8_0
        val fit = device.canLoad(model, context, kvCache, modelLimits[model.id]?.visionEnabled == true)
        val canDownload = device.canAttempt(model, context)
        val stored = storageInfos[model.id] ?: ModelStorageInfo(0, 0, false)
        val remaining = (model.bytes - stored.totalBytes).coerceAtLeast(0)
        val savedModel = model.artifacts.isEmpty() && stored.installedBytes > 0
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.title, style = MaterialTheme.typography.titleMedium)
                Text("${if (model.format == "MNN_BUNDLE") if(model.id == "k2-horizon-09b-mnn") "MNN INT8 weights" else "MNN INT4 weights" else if (model.custom) "Custom GGUF" else if (model.filename.contains("Q5_K_XL", ignoreCase = true)) "UD-Q5_K_XL weights" else if (model.filename.contains("Q6_K_XL", ignoreCase = true)) "Q6_K_XL weights" else if (model.filename.contains("Q6_K", ignoreCase = true)) "Q6_K weights" else if (model.filename.contains("Q5_0", ignoreCase = true)) "Q5_0 weights" else "Q4_K_M weights"} · ${size(model.bytes)}", style = MaterialTheme.typography.labelMedium)
                if (!isK2) Text(if (fit) "Fits available RAM · about ${size(model.estimatedMemory(context, kvCache) + 256L * 1024 * 1024)} at $context context"
                    else if (!device.fits(model, context, kvCache)) "Above estimated physical RAM · attempt allowed"
                    else "Low available RAM · attempt allowed",
                    color = if (fit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Text(model.description, style = MaterialTheme.typography.bodyMedium)
                if (isK2) {
                    modelLimits[model.id]?.let { limits ->
                        LocalInferenceControls(model, limits, device, enabled = busy == null, visionInstalled = model.projectorId in installed, onApply = { value ->
                            busy = model.id
                            scope.launch {
                                runCatching { manager.saveLimits(model.id, value) }
                                    .onSuccess {
                                        refreshLimits()
                                        device = manager.device()
                                        message = "Saved ${contextLabel(value.context)} context · MNN cache profile. Applies to the next reply."
                                    }
                                    .onFailure { message = it.message ?: "Could not save inference settings." }
                                busy = null
                            }
                        })
                    } ?: Text("Loading inference settings…", style = MaterialTheme.typography.bodySmall)
                }
                model.projectorId?.let { projectorId ->
                    VisionProjectorSection(manager, projectorId, installed, work, statuses, onDownload = { download = manager.find(projectorId) }, onDelete = { removing = manager.find(projectorId) })
                }
                Text(when {
                    hasModel -> "Installed · ${size(stored.installedBytes)} stored"
                    savedModel -> "Saved model · ${size(stored.installedBytes)} kept; delete explicitly to replace"
                    stored.totalBytes > 0 -> "${size(stored.totalBytes)} / ${size(model.bytes)} saved · ${size(remaining)} remaining"
                    else -> "Not downloaded"
                }, style = MaterialTheme.typography.bodySmall)
                if (!hasModel && device.freeStorage < remaining + 256L * 1024 * 1024) {
                    Text("Free at least ${size(remaining + 256L * 1024 * 1024)} to finish downloading. Saved bytes stay on device.", color = MaterialTheme.colorScheme.error)
                }
                statuses[model.id]?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                if (failed != null && !hasModel && !downloading) Text(failed, color = MaterialTheme.colorScheme.error)
                if (downloading) {
                    if (stored.totalBytes > 0) LinearProgressIndicator(progress = { (stored.totalBytes.toDouble() / model.bytes).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Interrupted downloads resume automatically when connected. Pause keeps saved bytes.", style = MaterialTheme.typography.bodySmall)
                }
                if (running == model.id) Text("Running on ${compute.label}", color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasModel) {
                        Button(onClick = {
                            scope.launch {
                                container.settings.setActiveModel(null)
                                container.settings.setActiveProvider(LocalModelCatalog.PROVIDER_PREFIX + model.id)
                                message = "${model.title} selected. Your next reply will use this local model."
                            }
                        }, enabled = device.canAttempt(model, context) && busy == null) { Text("Use") }
                        OutlinedButton(onClick = { editing = model }, enabled = busy == null) { Text(if (isK2) "Advanced" else "Limits") }
                    } else if (downloading) {
                        OutlinedButton(onClick = { manager.pauseDownload(model.id) }, enabled = busy == null) { Text("Pause") }
                    } else {
                        Button(onClick = { download = model }, enabled = canDownload && !savedModel && busy == null && device.freeStorage >= remaining + 256L * 1024 * 1024) {
                            Text(if (stored.totalBytes > 0 || stored.paused) "Resume" else "Download")
                        }
                        if (isK2) OutlinedButton(onClick = { editing = model }, enabled = busy == null) { Text("Advanced") }
                    }
                }
                if (hasModel || stored.totalBytes > 0 || downloading || stored.paused) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running == model.id) OutlinedButton(onClick = { manager.stop(model.id) }) { Text("Stop") }
                    OutlinedButton(onClick = { removing = model }, enabled = busy == null) {
                        Text(if (busy == model.id) "Deleting…" else "Delete model", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = { uri.openUri(model.modelPage) }) { Text("${model.license} · Source") }
            }
        }
    }
    Text("Inference stays on device. Web tools use internet.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { uri.openUri("https://github.com/alibaba/MNN/tree/024a946b0b8fcf87c8a418229fadd4cd7858ffba") }) { Text("MNN runtime source · Apache 2.0") }
    download?.let { model ->
        AlertDialog(onDismissRequest = { download = null }, title = { Text(if ((storageInfos[model.id]?.totalBytes ?: 0) > 0) "Resume ${model.title}?" else "Download ${model.title}?") },
            text = { Text("${size(storageInfos[model.id]?.totalBytes ?: 0)} already saved. Download ${size((model.bytes - (storageInfos[model.id]?.totalBytes ?: 0)).coerceAtLeast(0))} remaining from ${model.url.substringAfter("https://").substringBefore('/')} using your current connection. License: ${model.license}. " +
                if (model.artifacts.isNotEmpty()) "Every component is checked against its pinned SHA-256 checksum before installation." else if (model.sha256.isNotEmpty()) "The source SHA-256 checksum is verified before installation."
                else "File size and GGUF header are checked. This source does not provide a checksum.") },
            confirmButton = { TextButton(enabled = busy == null && device.canAttempt(model, contexts[model.id] ?: model.defaultContext) && device.freeStorage >= model.bytes - (storageInfos[model.id]?.totalBytes ?: 0) + 256L * 1024 * 1024, onClick = {
                busy = model.id
                scope.launch {
                    runCatching { if (model.custom) manager.downloadCustom(model) else manager.download(model.id) }
                        .onSuccess { download = null }.onFailure { message = it.message; download = null }
                    busy = null
                }
            }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { download = null }) { Text("Cancel") } })
    }

    removing?.let { model ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Delete ${model.title}?") },
            text = { Text("Stops generation/downloads and permanently deletes the model, unfinished download and saved model limits. Releases ${size(storageInfos[model.id]?.totalBytes ?: 0)}. Your chats and created files stay. Download again only if you want to use this model later.") },
            confirmButton = { TextButton(onClick = {
                removing = null
                busy = model.id
                scope.launch {
                    runCatching { manager.remove(model.id) }
                        .onSuccess { message = "${model.title} deleted. Model storage released." }
                        .onFailure { message = it.message }
                    busy = null
                }
            }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } })
    }
    editing?.let { model -> LocalLimitsDialog(model, manager, onDismiss = { editing = null; scope.launch { refreshLimits() } }) }
}

@Composable
private fun LocalModelLibraryControls(showAll: Boolean, onShowAll: (Boolean) -> Unit, onAdd: () -> Unit, hiddenCount: Int) {

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("Show all models", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = showAll, onCheckedChange = onShowAll)
    }
    if (hiddenCount > 0) Text("$hiddenCount more models exceed available RAM.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val MIN_SLIDER_CONTEXT = 3072

private fun contextPosition(context: Int): Float =
    (ln(context.coerceIn(MIN_SLIDER_CONTEXT, MAX_LOCAL_CONTEXT).toDouble() / MIN_SLIDER_CONTEXT) /
        ln(MAX_LOCAL_CONTEXT.toDouble() / MIN_SLIDER_CONTEXT)).toFloat()

private fun contextAtPosition(position: Float): Int =
    ((MIN_SLIDER_CONTEXT * exp(ln(MAX_LOCAL_CONTEXT.toDouble() / MIN_SLIDER_CONTEXT) * position.coerceIn(0f, 1f))) /
        1024).roundToInt().times(1024).coerceIn(MIN_SLIDER_CONTEXT, MAX_LOCAL_CONTEXT)

private fun contextLabel(tokens: Int): String = if (tokens % 1024 == 0) "${tokens / 1024}K" else
    String.format(Locale.US, "%,d tokens", tokens)

@Composable
private fun LocalInferenceControls(model: LocalModelSpec, saved: LocalModelLimits, device: LocalDeviceProfile,
    enabled: Boolean, visionInstalled: Boolean = false, onApply: (LocalModelLimits) -> Unit) {
    var draft by remember(model.id, saved) {
        val context = saved.context.coerceIn(MIN_SLIDER_CONTEXT, MAX_LOCAL_CONTEXT)
        val output = saved.output.coerceAtMost(context - 128)
        mutableStateOf(if (context == saved.context) saved else saved.copy(context = context, input = context - output, output = output))
    }
    val withinModel = draft.context <= model.maxContext
    val capacityFit = device.fits(model, draft.context, draft.kvCache, draft.visionEnabled)
    val availableFit = device.canLoad(model, draft.context, draft.kvCache, draft.visionEnabled)
    val status = when {
        !withinModel -> "Above model context limit"
        availableFit -> "Fits estimated RAM"
        capacityFit -> "Low available RAM · attempt allowed"
        else -> "Above estimated physical RAM · attempt allowed"
    }
    val changed = draft != saved
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Context window", style = MaterialTheme.typography.titleSmall)
            Text(contextLabel(draft.context), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = contextPosition(draft.context), onValueChange = { position ->
            val context = contextAtPosition(position)
            if (context != draft.context) {
                val output = if (context < draft.context) draft.output.coerceAtMost((context / 4).coerceAtLeast(16)) else draft.output
                draft = draft.copy(context = context, input = context - output, output = output)
            }
        }, enabled = enabled, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Text("3K", Modifier.align(Alignment.CenterStart), style = MaterialTheme.typography.labelSmall)
            Text("8K", Modifier.offset(x = maxWidth * contextPosition(8192) - 8.dp), style = MaterialTheme.typography.labelSmall)
            Text("32K", Modifier.offset(x = maxWidth * contextPosition(32768) - 12.dp), style = MaterialTheme.typography.labelSmall)
            Text("256K", Modifier.align(Alignment.CenterEnd), style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("KV cache", style = MaterialTheme.typography.labelLarge)
            listOf(KvCacheQuantization.Q8_0).forEach { choice ->
                FilterChip(selected = draft.kvCache == choice, onClick = { draft = draft.copy(kvCache = choice) },
                    enabled = enabled, label = { Text("MNN cache controls above") })
            }
        }
        if (model.projectorId != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = draft.visionEnabled, onCheckedChange = { draft = draft.copy(visionEnabled = it) }, enabled = enabled)
                Column {
                    Text("Vision", style = MaterialTheme.typography.titleSmall)
                    Text(if (visionInstalled) "Understand attached images on device" else "Download the optional projector below first", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Off uses text only. Turning Vision off keeps the projector; Delete vision removes it. Up to two images per request.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("MNN uses INT8 CPU cache or FP16 GPU cache. Cache grows with the conversation; context is its upper limit.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Estimated RAM ceiling · ${size(model.estimatedMemory(draft.context, draft.kvCache, draft.visionEnabled) + 256L * 1024 * 1024)}",
            style = MaterialTheme.typography.bodyMedium)
        Text(status, style = MaterialTheme.typography.labelMedium,
            color = if (availableFit) MaterialTheme.colorScheme.primary else if (capacityFit) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Saved: ${contextLabel(saved.context)} · MNN cache",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onApply(draft) }, enabled = enabled && changed && withinModel && device.supported && (!draft.visionEnabled || visionInstalled)) { Text("Apply") }
        }
        Text("RAM estimates are advisory. Attempts are allowed with RAM Plus; allocation can still fail. Changes apply to the next reply.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LocalLimitsDialog(model: LocalModelSpec, manager: LocalModelManager, onDismiss: () -> Unit) {
    var saved by remember { mutableStateOf<LocalModelLimits?>(null) }
    LaunchedEffect(model.id) { saved = withContext(Dispatchers.IO) { manager.limits(model.id) } }
    val limits = saved ?: return
    val advancedOnly = model.id in setOf("k2-horizon-37b", "k2-horizon-09b", "sharp-minicpm5-2b", "qwen35-2b")
    var context by remember { mutableStateOf(limits.context.toString()) }
    var input by remember { mutableStateOf(limits.input.toString()) }
    var output by remember { mutableStateOf(limits.output.toString()) }
    var threads by remember { mutableStateOf(limits.threads.toString()) }
    var kvCache by remember { mutableStateOf(limits.kvCache) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsedContext = context.toIntOrNull()
    val contextFits = parsedContext != null && parsedContext in 512..MAX_LOCAL_CONTEXT &&
        manager.device().canAttempt(model, parsedContext)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (advancedOnly) "Advanced inference settings" else "${model.title} limits") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Input includes instructions and chat history. Input + output must fit context. Changes apply to the next reply.")
            if (advancedOnly) Text("${contextLabel(limits.context)} context · MNN cache. Adjust these in the model card.", style = MaterialTheme.typography.bodySmall)
            ((if (advancedOnly) emptyList() else listOf(Triple("Context tokens", context, { v: String -> context = v }))) +
                listOf(Triple("Input tokens", input, { v: String -> input = v }),
                Triple("Output tokens", output, { v: String -> output = v }),
                Triple("CPU threads", threads, { v: String -> threads = v }))).forEach { (label, value, change) ->
                OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
            if (!advancedOnly) {
                Text("KV cache", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(KvCacheQuantization.Q8_0).forEach { choice ->
                        FilterChip(selected = kvCache == choice, onClick = { kvCache = choice },
                            enabled = !saving, label = { Text("MNN cache controls above") })
                    }
                }
                Text("CPU cache and GPU FP16 behavior are shown in MNN performance settings. Q5 is unsupported.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Estimated RAM ceiling: ${size(model.estimatedMemory(context.toIntOrNull()?.coerceIn(512, MAX_LOCAL_CONTEXT) ?: 2048, kvCache))}")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !saving && contextFits, onClick = {
        scope.launch {
            saving = true
            runCatching {
                val value = limits.copy(context = context.toIntOrNull() ?: 0, input = input.toIntOrNull() ?: 0,
                    output = output.toIntOrNull() ?: 0, threads = threads.toIntOrNull() ?: 0, kvCache = kvCache)
                manager.saveLimits(model.id, value)
            }.onSuccess { onDismiss() }.onFailure { error = it.message }
            saving = false
        }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun size(bytes: Long): String = when {
    bytes == 0L -> "0 B"
    bytes < 1_000_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    bytes < 1_000_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
}
private fun cacheLabel(choice: KvCacheQuantization) = if (choice == KvCacheQuantization.Q8_0) "Q8" else "Q5"

@Composable
internal fun CustomModelDialog(manager: LocalModelManager, onDismiss: () -> Unit, onDownload: (LocalModelSpec) -> Unit) {
    CustomModelLinkDialog(resolve = manager::resolveLink, inspect = manager::inspectCustom,
        device = manager::device, onDismiss = onDismiss, onDownload = onDownload)
}

@Composable
internal fun CustomModelLinkDialog(resolve: suspend (String) -> List<LocalModelSpec>,
    inspect: suspend (LocalModelSpec) -> LocalModelSpec, device: () -> LocalDeviceProfile,
    onDismiss: () -> Unit, onDownload: (LocalModelSpec) -> Unit) {
    var link by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<LocalModelSpec>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add custom model") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paste a public Hugging Face model page, GGUF file page, or direct HTTPS .gguf link. Choose an Instruct/chat model. Safetensors, split models and vision projectors are unsupported.")
            OutlinedTextField(link, { link = it; candidates = emptyList(); error = null },
                label = { Text("Model link") }, enabled = !loading, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking model files…") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            candidates.forEach { model ->
                Text(model.filename.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
                Text(size(model.bytes))
                OutlinedButton(enabled = !loading, onClick = {
                    loading = true; error = null
                    scope.launch {
                        try {
                            val checked = inspect(model)
                            val profile = device()
                            when {
                                !profile.canAttempt(checked) -> error = "This model needs about ${size(checked.estimatedMemory(checked.defaultContext) + 256L * 1024 * 1024)} of available RAM and a compatible 64-bit device. Choose a smaller file or close other apps."
                                profile.freeStorage < checked.bytes + 256L * 1024 * 1024 -> error = "Not enough storage. Free at least ${size(checked.bytes + 256L * 1024 * 1024)}."
                                else -> onDownload(checked)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not check model." }
                        finally { loading = false }
                    }
                }) { Text("Choose file") }
            }
        }
    }, confirmButton = { TextButton(enabled = link.isNotBlank() && !loading, onClick = {
        loading = true; error = null; candidates = emptyList()
        scope.launch {
            try { candidates = resolve(link) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not find model files." }
            finally { loading = false }
        }
    }) { Text("Find model files") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
