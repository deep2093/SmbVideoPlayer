package com.deepak.videolibrary.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared presentation only: ExoPlayer and VLC retain their own playback and lifecycle logic. */
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
    onInteraction: () -> Unit = {},
) {
    var scrubbing by remember(title) { mutableStateOf(false) }
    var fraction by remember(title) { mutableFloatStateOf(0f) }
    val shownPosition = if (scrubbing) (fraction.toDouble() * duration).toLong() else position
    val ink = Color.White
    val muted = Color(0xFFB8B8B8)
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xE6101010)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back to library", tint = ink) }
            Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onTracks) { Icon(Icons.Filled.Subtitles, "Audio and subtitles", tint = ink) }
            IconButton(onClick = onOptions) { Icon(Icons.Filled.MoreVert, "Playback options", tint = ink) }
        }
        Row(
            Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(enabled = canPrevious, onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, "Previous video", tint = if (canPrevious) ink else muted.copy(alpha = 0.5f))
            }
            IconButton(onClick = { onSeek((position - 10_000L).coerceAtLeast(0)); onInteraction() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Replay10, "Rewind 10 seconds", tint = ink, modifier = Modifier.size(30.dp))
            }
            FilledIconButton(
                enabled = canPlay,
                onClick = { onTogglePlayback(); onInteraction() },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = ink, contentColor = Color.Black),
            ) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) "Pause" else "Play", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { onSeek((position + 10_000L).coerceAtMost(duration.coerceAtLeast(0))); onInteraction() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = ink, modifier = Modifier.size(30.dp))
            }
            IconButton(enabled = canNext, onClick = onNext) {
                Icon(Icons.Filled.SkipNext, "Next video", tint = if (canNext) ink else muted.copy(alpha = 0.5f))
            }
        }
        Column(Modifier.fillMaxWidth().background(Color(0xF2101010)).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(DateUtils.formatElapsedTime(shownPosition.coerceAtLeast(0) / 1000), color = ink, style = MaterialTheme.typography.labelMedium)
                Text(DateUtils.formatElapsedTime(duration.coerceAtLeast(0) / 1000), color = muted, style = MaterialTheme.typography.labelMedium)
            }
            Slider(
                value = if (scrubbing) fraction else (position.toDouble() / duration.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat(),
                onValueChange = { scrubbing = true; fraction = it; onInteraction() },
                onValueChangeFinished = {
                    onSeek((fraction.toDouble() * duration.coerceAtLeast(0)).toLong())
                    scrubbing = false
                    onInteraction()
                },
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = ink,
                    activeTrackColor = MaterialTheme.colorScheme.tertiary,
                    inactiveTrackColor = Color(0xFF555555),
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Playback position" },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                queueLabel?.let { Text(it, color = muted, style = MaterialTheme.typography.labelSmall) }
                Spacer(Modifier.weight(1f))
                if (aspectLabel != null) TextButton(onClick = onAspect) { Text("Aspect: " + aspectLabel, color = ink) }
                TextButton(onClick = onOptions) { Text("Options", color = ink) }
            }
        }
    }
}
