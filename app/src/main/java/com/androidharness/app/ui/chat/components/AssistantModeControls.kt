package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.AssistantMode
import com.androidharness.app.agent.ThinkingLevel
import kotlin.math.roundToInt

internal enum class ThinkingProfile(val title: String, val level: ThinkingLevel) {
    FAST("Fast", ThinkingLevel.LOW),
    BALANCED("Balanced", ThinkingLevel.MEDIUM),
    DEEP("Deep", ThinkingLevel.HIGH),
}

internal fun thinkingProfile(level: ThinkingLevel): ThinkingProfile = when {
    level.rank <= ThinkingLevel.LOW.rank -> ThinkingProfile.FAST
    level == ThinkingLevel.MEDIUM -> ThinkingProfile.BALANCED
    else -> ThinkingProfile.DEEP
}

/** Task mode and reasoning profile stay visible without adding another settings page. */
@Composable
internal fun AssistantModeControls(
    assistantMode: AssistantMode,
    thinkingLevel: ThinkingLevel,
    onSetMode: (AssistantMode) -> Unit,
    onSetThinking: (ThinkingLevel) -> Unit,
    enabled: Boolean = true,
) {

    var thinkingMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AssistantMode.entries.forEach { mode ->
                val color by androidx.compose.animation.animateColorAsState(
                    if (mode == assistantMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    animationSpec = androidx.compose.animation.core.tween(220), label = "mode-color")
                androidx.compose.material3.Surface(onClick = { onSetMode(mode) }, enabled = enabled,
                    color = color, contentColor = if (mode == assistantMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp)) {
                    Text(mode.title, Modifier.padding(horizontal = 13.dp, vertical = 12.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Box {
            TextButton(onClick = { thinkingMenu = true }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Thinking: ${thinkingProfile(thinkingLevel).title}" }) {
                Text(thinkingProfile(thinkingLevel).title + " ↓", style = MaterialTheme.typography.labelMedium)
            }
            DropdownMenu(thinkingMenu, onDismissRequest = { thinkingMenu = false }) {
                ThinkingProfileSlider(thinkingLevel, onSetThinking, enabled)
            }
        }
    }
}

@Composable
internal fun ThinkingProfileSlider(
    level: ThinkingLevel,
    onSetThinking: (ThinkingLevel) -> Unit,
    enabled: Boolean = true,
) {
    var position by remember(level) { mutableFloatStateOf(thinkingProfile(level).ordinal.toFloat()) }
    val selected = ThinkingProfile.entries[position.roundToInt().coerceIn(0, 2)]
    Column(
        modifier = Modifier.widthIn(min = 280.dp, max = 320.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("Thinking · ${selected.title}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onSetThinking(ThinkingProfile.entries[position.roundToInt().coerceIn(0, 2)].level) },
            valueRange = 0f..2f,
            steps = 1,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Thinking profile: Fast, Balanced, Deep" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ThinkingProfile.entries.forEach { profile ->
                TextButton(onClick = { position = profile.ordinal.toFloat(); onSetThinking(profile.level) }, enabled = enabled) {
                    Text(profile.title, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("Applies to the next reply. More thinking can take longer.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
