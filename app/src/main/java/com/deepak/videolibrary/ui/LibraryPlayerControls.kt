package com.deepak.videolibrary.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Player overlay in the style of VLC: no solid bars, only soft dark gradients at the top and bottom over the
 * video; the title centred at the top; elapsed and total time above a slim seek bar with a round thumb; and
 * one row of icons with an outlined play/pause button in the middle.
 *
 * Shared presentation only: ExoPlayer and VLC retain their own playback and lifecycle logic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryPlayerControls(
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
    smoothOn: Boolean = false,
    onSmooth: (() -> Unit)? = null,
    onInteraction: () -> Unit = {},
) {
    var scrubbing by remember(title) { mutableStateOf(false) }
    var fraction by remember(title) { mutableFloatStateOf(0f) }
    val shownPosition = if (scrubbing) (fraction.toDouble() * duration).toLong() else position
    val ink = Color.White
    val muted = Color.White.copy(alpha = 0.7f)
    val disabled = Color.White.copy(alpha = 0.35f)
    val accent = MaterialTheme.colorScheme.tertiary

    val transport: @Composable () -> Unit = {
        IconButton(enabled = canPrevious, onClick = { onPrevious(); onInteraction() }) {
            Icon(Icons.Filled.SkipPrevious, "Previous video", tint = if (canPrevious) ink else disabled)
        }
        IconButton(enabled = duration > 0, onClick = { onSeek((position - 10_000L).coerceAtLeast(0)); onInteraction() }) {
            Icon(Icons.Filled.Replay10, "Rewind 10 seconds", tint = if (duration > 0) ink else disabled)
        }
        // Outlined round play/pause, as in VLC.
        IconButton(
            enabled = canPlay,
            onClick = { onTogglePlayback(); onInteraction() },
            modifier = Modifier.padding(horizontal = 6.dp).size(58.dp).border(2.dp, if (canPlay) ink else disabled, CircleShape),
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (isPlaying) "Pause" else "Play",
                tint = if (canPlay) ink else disabled,
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(enabled = duration > 0, onClick = { onSeek((position + 10_000L).coerceAtMost(duration)); onInteraction() }) {
            Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = if (duration > 0) ink else disabled)
        }
        IconButton(enabled = canNext, onClick = { onNext(); onInteraction() }) {
            Icon(Icons.Filled.SkipNext, "Next video", tint = if (canNext) ink else disabled)
        }
    }
    val leading: @Composable () -> Unit = {
        onSmooth?.let { callback ->
            TextButton(onClick = { callback(); onInteraction() }) {
                Text(
                    if (smoothOn) "60 fps ON" else "60 fps",
                    color = if (smoothOn) MaterialTheme.colorScheme.primary else ink,
                    fontWeight = if (smoothOn) androidx.compose.ui.text.font.FontWeight.Bold else null,
                )
            }
        }
        IconButton(onClick = { onTracks(); onInteraction() }) { Icon(Icons.Filled.Subtitles, "Audio and subtitles", tint = ink) }
        if (onRotate != null) {
            IconButton(onClick = { onRotate(); onInteraction() }) { Icon(Icons.Filled.ScreenRotation, "Rotate screen", tint = ink) }
        }
    }
    val trailing: @Composable () -> Unit = {
        onQueue?.let { callback ->
            IconButton(onClick = { callback(); onInteraction() }) { Icon(Icons.Filled.QueuePlayNext, "Playback queue", tint = ink) }
        }
        onPip?.let { callback ->
            IconButton(onClick = { callback(); onInteraction() }) { Icon(Icons.Filled.PictureInPictureAlt, "Picture in picture", tint = ink) }
        }
        if (aspectLabel != null) {
            IconButton(onClick = { onAspect(); onInteraction() }) {
                Icon(Icons.Filled.AspectRatio, "Zoom and aspect ratio: $aspectLabel", tint = ink)
            }
        }
        IconButton(onClick = { onOptions(); onInteraction() }) { Icon(Icons.Filled.MoreHoriz, "Playback options", tint = ink) }
    }

    Box(Modifier.fillMaxSize()) {
        // Soft scrims instead of solid bars, so the picture stays visible behind the controls.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(140.dp)
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))),
        )
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top: back on the left, the title centred.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back to library", tint = ink) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = ink,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    queueLabel?.let { Text(it, color = muted, style = MaterialTheme.typography.labelSmall) }
                }
                Spacer(Modifier.width(48.dp)) // Balances the back button so the title stays centred.
            }

            // Bottom: times, slim seek bar, icon row.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 560.dp
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(DateUtils.formatElapsedTime(shownPosition.coerceAtLeast(0) / 1000), color = ink, style = MaterialTheme.typography.labelLarge)
                        Text(if (duration > 0) DateUtils.formatElapsedTime(duration / 1000) else "--:--", color = ink, style = MaterialTheme.typography.labelLarge)
                    }
                    Slider(
                        value = if (scrubbing) fraction else (position.toDouble() / duration.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat(),
                        onValueChange = { scrubbing = true; fraction = it; onInteraction() },
                        onValueChangeFinished = {
                            if (duration > 0) onSeek((fraction.toDouble() * duration).toLong())
                            scrubbing = false
                            onInteraction()
                        },
                        enabled = duration > 0,
                        thumb = {
                            Box(Modifier.size(if (scrubbing) 28.dp else 22.dp).background(accent, CircleShape))
                        },
                        track = { state ->
                            // A thicker line (8 dp): played part in the accent colour, the rest translucent white.
                            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                                val y = size.height / 2
                                val played = size.width * state.value.coerceIn(0f, 1f)
                                drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
                                drawLine(accent, Offset(0f, y), Offset(played, y), strokeWidth = size.height, cap = StrokeCap.Round)
                            }
                        },
                        // A taller touch zone than before: 10 dp more above and 10 dp more below (72 dp in all), still inside the bar's own row so it never covers the buttons.
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { contentDescription = "Playback position" },
                    )
                    if (wide) {
                        // Landscape and tablets: one row, play/pause in the middle.
                        Box(Modifier.fillMaxWidth()) {
                            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) { leading() }
                            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) { transport() }
                            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) { trailing() }
                        }
                    } else {
                        // Portrait phones: transport centred, the other icons on a row below.
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) { transport() }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            leading()
                            Spacer(Modifier.weight(1f))
                            trailing()
                        }
                    }
                }
            }
        }
    }
}
