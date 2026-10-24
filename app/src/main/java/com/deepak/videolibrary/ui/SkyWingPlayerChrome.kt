package com.deepak.videolibrary.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Presentation only. Both existing engines own playback, tracks, auto-hide and lifecycle.
 * No capture/blur of Android video surfaces, so HDR and protected output stay untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkyWingPlayerChrome(
    title: String,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    canPlay: Boolean = true,
    canPrevious: Boolean = false,
    canNext: Boolean = false,
    queueLabel: String? = null,
    aspectLabel: String? = null,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onTracks: () -> Unit,
    onOptions: () -> Unit,
    onAspect: () -> Unit = {},
    onRotate: (() -> Unit)? = null,
    onPip: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    onInteraction: () -> Unit = {},
) {
    val theme = LocalLibraryTheme.current
    val aurora = theme.skyWingGlassFamily() == "AURORA"
    // White foreground always, even when the surrounding library uses a light theme.
    val ink = Color.White
    val accent = if (aurora) Color(0xFF8CD4FF) else Color(0xFFCEE6F7)
    val tint = if (aurora) Color(0xC0101940) else Color(0xC01A2736)
    val rim = Brush.linearGradient(listOf(Color(0x99FFFFFF), accent.copy(alpha = 0.15f), Color(0x55FFFFFF)))
    val pill = RoundedCornerShape(28.dp)
    fun Modifier.lens() = clip(pill).background(tint).border(1.dp, rim, pill)
    var scrubbing by remember(title) { mutableStateOf(false) }
    var fraction by remember(title) { mutableFloatStateOf(0f) }
    val length = duration.coerceAtLeast(0L)
    val shown = if (scrubbing) (fraction.toDouble() * length).toLong() else position.coerceIn(0L, length)

    val action: @Composable (androidx.compose.ui.graphics.vector.ImageVector, String, Boolean, () -> Unit) -> Unit = { icon, description, enabled, callback ->
        IconButton(onClick = { callback(); onInteraction() }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, description, tint = ink.copy(alpha = if (enabled) 1f else 0.35f))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val compact = maxHeight < 380.dp
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.lens()) { action(Icons.Filled.ArrowBack, "Back to library", true, onBack) }
            Column(Modifier.weight(1f).lens().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(title, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                queueLabel?.let { Text(it, color = ink.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall) }
            }
            Box(Modifier.lens()) { action(Icons.Filled.MoreHoriz, "Playback options", true, onOptions) }
        }
        // Keep the central transport clear of the timeline in short landscape windows.
        Row(
            Modifier.align(Alignment.Center).offset(y = if (compact) (-8).dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp),
        ) {
            Box(Modifier.lens()) { action(Icons.Filled.SkipPrevious, "Previous video", canPrevious, onPrevious) }
            Box(Modifier.lens()) { action(Icons.Filled.Replay10, "Rewind 10 seconds", length > 0) { onSeek((position - 10_000).coerceIn(0L, length)) } }
            IconButton(
                enabled = canPlay,
                onClick = { onTogglePlayback(); onInteraction() },
                modifier = Modifier.padding(horizontal = 4.dp).size(if (compact) 56.dp else 72.dp)
                    .background(Brush.linearGradient(listOf(Color(0xE6FFFFFF), accent.copy(alpha = 0.7f))), CircleShape)
                    .border(1.dp, rim, CircleShape),
            ) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "Pause" else "Play", tint = Color(0xFF14243A).copy(alpha = if (canPlay) 1f else 0.35f), modifier = Modifier.size(34.dp))
            }
            Box(Modifier.lens()) { action(Icons.Filled.Forward10, "Forward 10 seconds", length > 0) { onSeek((position + 10_000).coerceIn(0L, length)) } }
            Box(Modifier.lens()) { action(Icons.Filled.SkipNext, "Next video", canNext, onNext) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.fillMaxWidth().lens().padding(horizontal = 14.dp, vertical = 2.dp)) {
                Slider(
                    value = if (scrubbing) fraction else (position.toDouble() / length.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat(),
                    onValueChange = { scrubbing = true; fraction = it; onInteraction() },
                    onValueChangeFinished = {
                        if (scrubbing && length > 0) onSeek((fraction.toDouble() * length).toLong())
                        scrubbing = false
                        onInteraction()
                    },
                    enabled = length > 0,
                    thumb = { Box(Modifier.size(if (scrubbing) 20.dp else 14.dp).background(ink, CircleShape)) },
                    track = { state ->
                        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                            val y = size.height / 2
                            drawLine(ink.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
                            drawLine(accent, Offset(0f, y), Offset(size.width * state.value.coerceIn(0f, 1f), y), strokeWidth = size.height, cap = StrokeCap.Round)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Playback position" },
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(DateUtils.formatElapsedTime(shown / 1000), color = ink, style = MaterialTheme.typography.labelMedium)
                    Text(if (length > 0) DateUtils.formatElapsedTime(length / 1000) else "--:--", color = ink, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(
                Modifier.align(Alignment.CenterHorizontally).lens().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                action(Icons.Filled.Subtitles, "Audio and subtitles", true, onTracks)
                if (aspectLabel != null) action(Icons.Filled.AspectRatio, "Zoom and aspect ratio: $aspectLabel", true, onAspect)
                onRotate?.let { action(Icons.Filled.ScreenRotation, "Rotate screen", true, it) }
                onPip?.let { action(Icons.Filled.PictureInPictureAlt, "Picture in picture", true, it) }
                onQueue?.let { action(Icons.Filled.QueuePlayNext, "Playback queue", true, it) }
                // Top and bottom entry points have distinct labels for accessible navigation.
                action(Icons.Filled.Tune, "More playback settings", true, onOptions)
            }
        }
    }
}

/** Transient visual feedback driven by the existing gesture recognizer, never a second gesture layer. */
@Composable
internal fun BoxScope.SkyWingGestureFeedback(message: String) {
    val value = message.substringAfterLast(' ', "").removeSuffix("%").toFloatOrNull()
    val brightness = message.startsWith("Brightness ")
    val volume = message.startsWith("Volume ")
    if (value != null && (brightness || volume)) {
        Column(
            Modifier.align(if (brightness) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 24.dp)
                .background(Color(0xCC172638), RoundedCornerShape(24.dp)).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (brightness) Icons.Filled.Brightness6 else Icons.Filled.VolumeUp, message, tint = Color.White)
            Box(Modifier.size(8.dp, 100.dp).background(Color(0x44FFFFFF), CircleShape)) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight((value / 100).coerceIn(0f, 1f)).background(Color(0xFF9AD9FF), CircleShape))
            }
            Text("${value.toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    } else if (message.isNotBlank()) {
        Text(message, color = Color.White, modifier = Modifier.align(Alignment.Center)
            .background(Color(0xD9172638), RoundedCornerShape(20.dp)).padding(16.dp))
    }
}
