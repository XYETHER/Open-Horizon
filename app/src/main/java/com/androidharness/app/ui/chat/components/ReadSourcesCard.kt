package com.androidharness.app.ui.chat.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.local.ReadSource

@Composable
internal fun ReadSourcesCard(sources: List<ReadSource>) {
    var expanded by remember(sources.firstOrNull()?.url) { mutableStateOf(false) }
    val context=LocalContext.current
    Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) {
                Text("${sources.size} sources read ${if(expanded) "↑" else "↓"}")
            }
            Text("Pages opened during this task. Claims still need checking.",style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(expanded) sources.forEach { source ->
                TextButton(onClick={ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(source.url))) } },modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(source.host,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
