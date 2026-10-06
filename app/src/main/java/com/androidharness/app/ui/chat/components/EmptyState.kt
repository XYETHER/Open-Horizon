package com.androidharness.app.ui.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun EmptyState(hasProvider: Boolean, onSuggestion: (String) -> Unit, onAddProvider: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(550)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 34.dp)
        .graphicsLayer { alpha = reveal.value; translationY = (1f - reveal.value) * 24f },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(26.dp))
        Text("A little help.\nA bigger horizon.", fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 43.sp,
            letterSpacing = (-1).sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("Turn a question into an answer.\nAn idea into something real.", style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(30.dp))
        val suggestions = listOf(
            Triple(Icons.Outlined.Public, "Explore a topic", "/deep-research Research the latest OpenAI DevDay. Read official sources, cite their URLs, and save a short report."),
            Triple(Icons.Outlined.EditNote, "Draft an email", "/writing Write a concise sick leave email to my manager and save email.txt. Use name placeholders and do not invent symptoms."))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            suggestions.forEach { (icon, title, prompt) ->
                Surface(onClick = { onSuggestion(prompt) }, color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp)); Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.NorthEast, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!hasProvider) {
            Spacer(Modifier.height(24.dp))
            Surface(onClick = onAddProvider, color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Memory, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Set up your local model", style = MaterialTheme.typography.titleSmall)
                        Text("Choose a model for your phone", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
    }
}
