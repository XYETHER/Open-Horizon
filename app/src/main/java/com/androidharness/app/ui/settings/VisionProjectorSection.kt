package com.androidharness.app.ui.settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.androidharness.app.local.LocalModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun VisionProjectorSection(manager: LocalModelManager, id: String, installed: Set<String>, work: List<WorkInfo>,
    statuses: Map<String, String>, onDownload: () -> Unit, onDelete: () -> Unit) {
    val spec = requireNotNull(manager.find(id))
    val stored by produceState(manager.storageInfo(id), installed, work) {
        while (true) { value = withContext(Dispatchers.IO) { manager.storageInfo(id) }; delay(1000) }
    }
    val downloading = work.any { !it.state.isFinished && "model:$id" in it.tags }
    fun mb(bytes: Long) = String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    HorizontalDivider()
    Text("Optional vision download", style = MaterialTheme.typography.titleSmall)
    Text("BF16 projector · ${mb(spec.bytes)} · ${mb(stored.totalBytes)} saved", style = MaterialTheme.typography.bodySmall)
    statuses[id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (downloading) LinearProgressIndicator(progress = { (stored.partialBytes.toDouble() / spec.bytes).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (downloading) OutlinedButton(onClick = { manager.pauseDownload(id) }) { Text("Pause vision") }
        else if (id !in installed) OutlinedButton(onClick = onDownload) { Text(if (stored.partialBytes > 0) "Resume vision" else "Download vision") }
        else Text("Vision projector installed", style = MaterialTheme.typography.bodySmall)
        if (stored.totalBytes > 0 || downloading) TextButton(onClick = onDelete) { Text("Delete vision", color = MaterialTheme.colorScheme.error) }
    }
}
