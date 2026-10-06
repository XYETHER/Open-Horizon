package com.androidharness.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.ui.chat.*
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.files.*
import com.androidharness.app.ui.settings.SettingsScreen
import com.androidharness.app.ui.setup.SetupScreen
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** Horizon's only public destinations: conversations, created files and local settings. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val running by container.runManager.runningSessionIds.collectAsStateWithLifecycle()
    val current = settings ?: return
    var query by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<SessionEntity?>(null) }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    val entry by nav.currentBackStackEntryAsState()
    val sid = entry?.arguments?.getString("sessionId")
    val initial = remember {
        if (!current.onboardingDone) "setup" else current.lastActiveSessionId
            ?.takeIf { current.resumeLastChat }?.let { "chat/$it" } ?: "chat"
    }
    fun open(route: String) { scope.launch { drawer.close() }; nav.navigate(route) { launchSingleTop = true } }
    fun newChat() {
        scope.launch { container.settings.setLastActiveSessionId(null); drawer.close() }
        nav.navigate("chat") { popUpTo(nav.graph.id) { inclusive = false }; launchSingleTop = true }
    }
    LaunchedEffect(sid) { container.settings.setLastActiveSessionId(sid) }
    LaunchedEffect(Unit) { container.pendingSessionId.collect { open("chat/$it") } }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = entry?.destination?.route?.startsWith("viewer") != true,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.widthIn(max = 320.dp)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(28.dp))
                    Text("horizon", style = MaterialTheme.typography.headlineMedium)
                    Text("Ideas into action. On your device.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = ::newChat, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(16.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("New task")
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search your tasks") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    Spacer(Modifier.height(22.dp))
                    Text("YOUR TASKS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    val filtered = sessions.filter { it.id !in current.archivedSessions && it.title.contains(query, true) }
                        .sortedByDescending { it.id in current.pinnedSessions }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (filtered.isEmpty()) item { Text("Your conversations will appear here.",
                            Modifier.padding(vertical = 18.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(filtered, key = { it.id }) { task ->
                            Surface(color = if (sid == task.id) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().animateItem()
                                    .combinedClickable(onClick = { open("chat/${task.id}") }, onLongClick = { action = task })) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (task.id in running) Icons.Outlined.MoreHoriz else Icons.Outlined.ChatBubbleOutline,
                                        null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(12.dp))
                                    Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(onClick = { open("files") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("My files")
                    }
                    TextButton(onClick = { open("settings") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("Settings")
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }) {
        NavHost(nav, startDestination = initial,
            enterTransition = { fadeIn(tween(260)) + slideInHorizontally(tween(300)) { it / 12 } },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(240)) },
            popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 12 } }) {
            fun androidx.navigation.NavGraphBuilder.conversation(route: String, existing: Boolean) {
                composable(route, arguments = if (existing) listOf(navArgument("sessionId") { type = NavType.StringType }) else emptyList()) { back ->
                    val sessionId = back.arguments?.getString("sessionId")
                    val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(container, sessionId))
                    ChatScreen(vm, onOpenDrawer = { scope.launch { drawer.open() } },
                        onOpenFile = { path, line -> open("viewer/${encode(path)}?line=${line ?: 0}&session=${encode(vm.state.value.sessionId.orEmpty())}") },
                        onNewChat = ::newChat, onOpenTerminal = { open("settings") },
                        onOpenFiles = { open("files") }, onOpenSubagent = {},
                        onOpenSettings = { open("settings") }, onNavigateToSession = { open("chat/$it") })
                }
            }
            conversation("chat", false)
            conversation("chat/{sessionId}", true)
            composable("settings") { SettingsScreen(container, onBack = { nav.popBackStack() }) }
            composable("files") {
                FilesScreen(container, sessionId = sid, onBack = { nav.popBackStack() },
                    onOpenFile = { open("viewer/${encode(it)}?line=0&session=${encode(sid.orEmpty())}") },
                    onPreviewHtml = { open("preview/${encode(it)}") })
            }
            composable("preview/{path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { back ->
                val workspace by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
                com.androidharness.app.ui.chat.components.HtmlPreviewScreen(back.arguments?.getString("path").orEmpty(),workspace) { nav.popBackStack() }
            }
            composable("viewer/{path}?line={line}&session={session}", arguments = listOf(
                navArgument("path") { type = NavType.StringType }, navArgument("line") { type = NavType.IntType; defaultValue = 0 },
                navArgument("session") { type = NavType.StringType; defaultValue = "" })) { back ->
                CodeEditorScreen(container, path = back.arguments?.getString("path").orEmpty(),
                    initialLine = back.arguments?.getInt("line")?.takeIf { it > 0 },
                    sessionId = back.arguments?.getString("session")?.takeIf { it.isNotBlank() }, onBack = { nav.popBackStack() })
            }
            composable("setup") { SetupScreen(container) { nav.navigate("chat") { popUpTo("setup") { inclusive = true } } } }
        }
    }
    action?.let { task ->
        AlertDialog(onDismissRequest = { action = null }, title = { Text(task.title) }, text = {
            Column {
                TextButton(onClick = { renaming = task; action = null }) { Text("Rename task") }
                TextButton(onClick = { scope.launch { container.settings.setPinned(task.id, task.id !in current.pinnedSessions) }; action = null }) {
                    Text(if (task.id in current.pinnedSessions) "Unpin task" else "Pin task")
                }
                TextButton(onClick = { deleting = task; action = null }) { Text("Delete task", color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { action = null }) { Text("Close") } })
    }
    renaming?.let { task ->
        var title by remember(task.id) { mutableStateOf(task.title) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename task") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { container.sessions.renameSession(task.id, title.trim()) }; renaming = null }, enabled = title.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { task ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this task?") },
            text = { Text("Its conversation will be removed. Your model and created files will stay.") },
            confirmButton = { TextButton(onClick = { scope.launch { container.sessions.deleteSession(task) }; deleting = null; if (sid == task.id) newChat() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}
private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
