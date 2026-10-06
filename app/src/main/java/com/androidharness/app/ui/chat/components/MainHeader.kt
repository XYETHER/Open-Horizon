package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.*

@Composable
internal fun MainHeader(contextUsed: Int = 0, contextMax: Int = 0, contextMeasured: Boolean = false, sessionTitle: String, busy: Boolean, pickerLabel: String, mode: AgentMode,
    dualPlanning: Boolean = false, thinkingLevel: ThinkingLevel, thinkingLevels: List<ThinkingLevel>,
    permissionMode: PermissionMode, canUndo: Boolean, onOpenDrawer: () -> Unit, onPickModel: () -> Unit,
    onOpenTerminal: () -> Unit, onSetThinking: (ThinkingLevel) -> Unit, onSetPermission: (PermissionMode) -> Unit,
    onSetMode: (AgentMode) -> Unit, onToggleDualPlanning: () -> Unit = {}, onOpenContext: () -> Unit,
    onOpenUndo: () -> Unit, onOpenFiles: () -> Unit, onOpenWebPreview: () -> Unit = {}, onNewTask: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 68.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Open navigation", Modifier.size(22.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text("horizon", style = MaterialTheme.typography.titleLarge)
            Text(pickerLabel.ifBlank { "Choose a local model" } + " ↓", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable(onClick = onPickModel).padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (contextMax > 0) TextButton(onClick = onOpenContext) {
            val percent = (contextUsed.toLong().coerceAtLeast(0) * 100 / contextMax).coerceAtMost(100)
            Column(horizontalAlignment = Alignment.End) {
                Text("Context ${if (contextMeasured) "" else "≈"}$percent%", style = MaterialTheme.typography.labelSmall)
                Text("${com.androidharness.app.ui.common.formatTokenCount(contextUsed.toLong())} / ${com.androidharness.app.ui.common.formatTokenCount(contextMax.toLong())}", style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(onClick = onNewTask) { Icon(Icons.Outlined.EditNote, "New task", Modifier.size(22.dp)) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "Task options") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("My files") }, onClick = { menu = false; onOpenFiles() }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) })
                DropdownMenuItem(text = { Text("Local model settings") }, onClick = { menu = false; onPickModel() }, leadingIcon = { Icon(Icons.Outlined.Memory, null) })
                if (canUndo) DropdownMenuItem(text = { Text("Undo file changes") }, onClick = { menu = false; onOpenUndo() })
            }
        }
    }
}
internal val FullAccessOrange = Color(0xFFF59E0B)
