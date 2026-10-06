package com.androidharness.app.ui.chat.components
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.chat.GroqRecordState
@Composable
internal fun MessageComposer(
    busy: Boolean,
    value: TextFieldValue,
    attachedSkill: String?,
    onValueChange: (TextFieldValue) -> Unit,
    onClearSkill: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachFile: () -> Unit,
    voiceEngine: String = AppSettings.VOICE_ENGINE_INBUILT,
    groqRecordState: GroqRecordState = GroqRecordState.IDLE,
    recordingDurationMs: Long = 0L,
    levels: List<Float> = emptyList(),
    cancelArmed: Boolean = false,
    onCancelArmedChange: (Boolean) -> Unit = {},
    onToggleInbuiltVoice: () -> Unit = {},
    isInbuiltListening: Boolean = false,
    onStartGroqRecord: (locked: Boolean) -> Unit = {},
    onLockGroqRecord: () -> Unit = {},
    onCancelGroqRecord: () -> Unit = {},
    onStopAndTranscribeGroq: () -> Unit = {},
    hasAttachments: Boolean = false,
    allowImages: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var attachmentMenu by remember { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).animateContentSize(),
        shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 18.dp, bottom = 8.dp)) {
            if (!attachedSkill.isNullOrBlank()) TextButton(onClick = onClearSkill) { Text("$attachedSkill ×") }
            BasicTextField(value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 160.dp).semantics { contentDescription = "Message Horizon" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { input -> Box { if (value.text.isEmpty()) Text("Give Horizon a task…", color = MaterialTheme.colorScheme.onSurfaceVariant); input() } })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { attachmentMenu = true }) { Icon(Icons.Outlined.Add, "Attach a file") }
                    DropdownMenu(attachmentMenu, { attachmentMenu = false }) {
                        if (allowImages) DropdownMenuItem(text = { Text("Attach image") }, onClick = { attachmentMenu = false; onAttachImage() })
                        DropdownMenuItem(text = { Text("Attach file") }, onClick = { attachmentMenu = false; onAttachFile() })
                    }
                }
                Text(if (busy) "Running locally" else "On device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                val sendable = value.text.isNotBlank() || hasAttachments
                FilledIconButton(onClick = if (busy) onStop else onSend, enabled = busy || sendable,
                    shape = CircleShape, modifier = Modifier.size(48.dp)) {
                    Icon(if (busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (busy) "Stop task" else "Send message", Modifier.size(22.dp))
                }
            }
        }
    }
}
@Composable
fun SendGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.115f
        val tip = Offset(w / 2f, h * 0.16f)

        drawLine(color, Offset(w / 2f, h * 0.86f), tip, weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.20f, h * 0.48f), tip, weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.80f, h * 0.48f), tip, weight, StrokeCap.Round)
    }
}

/**
 * Stop, drawn as a rounded outline like [SendGlyph] and [MicGlyph] rather than
 * dropped in as a filled Material icon, so the send/stop/mic states of the same
 * button read as one set instead of two.
 */
@Composable
fun StopGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.115f
        val inset = w * 0.22f

        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2f, h - inset * 2f),
            cornerRadius = CornerRadius(w * 0.16f),
            style = Stroke(width = weight, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun MicGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.115f
        val stroke = Stroke(width = weight, cap = StrokeCap.Round, join = StrokeJoin.Round)

        drawLine(
            color,
            Offset(w * 0.5f, h * 0.22f),
            Offset(w * 0.5f, h * 0.50f),
            w * 0.24f,
            StrokeCap.Round,
        )

        val cradle = Path().apply {
            moveTo(w * 0.24f, h * 0.48f)
            cubicTo(w * 0.24f, h * 0.74f, w * 0.76f, h * 0.74f, w * 0.76f, h * 0.48f)
        }
        drawPath(cradle, color, style = stroke)

        drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.86f), weight, StrokeCap.Round)
    }
}

@Composable
fun PlusGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.115f

        drawLine(color, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.78f), weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), weight, StrokeCap.Round)
    }
}

@Composable
fun TrashGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.11f

        drawLine(color, Offset(w * 0.20f, h * 0.28f), Offset(w * 0.80f, h * 0.28f), weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.40f, h * 0.16f), Offset(w * 0.60f, h * 0.16f), weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.30f, h * 0.40f), Offset(w * 0.35f, h * 0.82f), weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.70f, h * 0.40f), Offset(w * 0.65f, h * 0.82f), weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.35f, h * 0.82f), Offset(w * 0.65f, h * 0.82f), weight, StrokeCap.Round)
    }
}

@Composable
fun UpGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.12f
        val tip = Offset(w / 2f, h * 0.36f)

        drawLine(color, Offset(w * 0.24f, h * 0.60f), tip, weight, StrokeCap.Round)
        drawLine(color, tip, Offset(w * 0.76f, h * 0.60f), weight, StrokeCap.Round)
    }
}

@Composable
fun BackGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.115f
        val tip = Offset(w * 0.34f, h / 2f)

        drawLine(color, Offset(w * 0.84f, h / 2f), tip, weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.58f, h * 0.24f), tip, weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.58f, h * 0.76f), tip, weight, StrokeCap.Round)
    }
}

@Composable
fun LockGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    open: Float = 0f,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.11f
        val lift = h * 0.10f * open.coerceIn(0f, 1f)

        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.30f, h * 0.16f - lift),
            size = Size(w * 0.40f, h * 0.36f),
            style = Stroke(width = weight, cap = StrokeCap.Round),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.20f, h * 0.46f),
            size = Size(w * 0.60f, h * 0.38f),
            cornerRadius = CornerRadius(w * 0.13f),
        )
    }
}

@Composable
fun ImageGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.1f

        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.16f, h * 0.2f),
            size = Size(w * 0.68f, h * 0.6f),
            cornerRadius = CornerRadius(w * 0.1f, w * 0.1f),
            style = Stroke(width = weight, join = StrokeJoin.Round),
        )
        drawCircle(color, w * 0.06f, Offset(w * 0.36f, h * 0.38f))
        val ridge = Path().apply {
            moveTo(w * 0.2f, h * 0.72f)
            lineTo(w * 0.42f, h * 0.52f)
            lineTo(w * 0.56f, h * 0.62f)
            lineTo(w * 0.72f, h * 0.44f)
            lineTo(w * 0.8f, h * 0.52f)
        }
        drawPath(ridge, color, style = Stroke(width = weight, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun formatClipDuration(durationMs: Int): String {
    val totalSec = (durationMs / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
