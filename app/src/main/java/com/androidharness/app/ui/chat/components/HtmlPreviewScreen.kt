package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.browser.HtmlArtifact
import com.androidharness.app.browser.HtmlArtifacts
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HtmlArtifactActions(
    messages: List<ChatMessage>,
    workspace: WorkspaceFs?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artifacts by produceState<List<HtmlArtifact>>(emptyList(), messages, workspace) {
        value = withContext(Dispatchers.IO) { HtmlArtifacts.existing(messages, workspace) }
    }
    if (artifacts.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        artifacts.forEach { artifact ->
            OutlinedButton(onClick = { onOpen(artifact.path) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Description, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("View HTML")
                Spacer(Modifier.width(12.dp))
                Text(
                    artifact.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Reuses the existing native viewer and its console, restricted to workspace assets. */
@Composable
fun HtmlPreviewScreen(path: String, workspace: WorkspaceFs?, onDismiss: () -> Unit) {
    key(path, workspace) {
        WebPreviewSheet(
            initialTarget = path,
            workspace = workspace,
            localFilesOnly = true,
            onDismiss = onDismiss,
        )
    }
}
