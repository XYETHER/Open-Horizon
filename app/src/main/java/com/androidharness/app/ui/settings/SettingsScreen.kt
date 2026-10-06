package com.androidharness.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.BuildConfig
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.ui.common.AppHeader
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Local-only settings. There are no provider accounts, cloud model pickers or Harness administration. */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenStats: () -> Unit = {},
    onRunSetup: () -> Unit = {}, onOpenSkills: () -> Unit = {}, onOpenProviders: () -> Unit = {}) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var models by rememberSaveable { mutableStateOf(false) }
    BackHandler(models) { models = false }
    LaunchedEffect(Unit) {
        container.pendingSettingsScroll.filterNotNull().collect { models = true; container.pendingSettingsScroll.value = null }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface, topBar = {
        AppHeader(if (models) "Local models" else "Settings", onBack = { if (models) models = false else onBack() })
    }) { padding ->
        AnimatedContent(models, transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) }, label = "settings-page") { local ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (local) {
                    Text("Make room for bigger ideas.", style = MaterialTheme.typography.headlineSmall)
                    Text("Manage downloads, context and memory for the model on your phone.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LocalModelsSection(container)
                } else {
                    Text("Your agent. Your device.", style = MaterialTheme.typography.headlineMedium)
                    Text("A little space to make Horizon yours.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Surface(onClick = { models = true }, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Memory, null); Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Local models", style = MaterialTheme.typography.titleMedium)
                                Text("Downloads, storage, context & KV cache", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Outlined.ChevronRight, null)
                        }
                    }
                    SettingsPanel {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("CONVERSATIONS", style = MaterialTheme.typography.labelSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("Pick up where you left off"); Text("Open your most recent task", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Switch(settings.resumeLastChat, { scope.launch { container.settings.setResumeLastChat(it) } })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("Keep working in the background"); Text("Finish tasks while the screen is off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Switch(settings.keepAlive, { scope.launch { container.settings.setKeepAlive(it) } })
                            }
                        }
                    }
                    SettingsPanel {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Agent folder", style = MaterialTheme.typography.titleMedium)
                            Text("Horizon can read and write files only in this folder. Shell, device administration and other folders are unavailable.", style = MaterialTheme.typography.bodyMedium)
                            Text(container.workspace.appPrivateRoot.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Coding supports file creation and local HTML checks. Web search and source reading use the internet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("Open Horizon", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
                    Text("${BuildConfig.VERSION_NAME} · Open source · MIT\nModels run locally. Web search uses the internet.\nYour model downloads stay across app updates.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
