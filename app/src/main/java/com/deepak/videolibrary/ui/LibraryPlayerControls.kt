package com.deepak.videolibrary.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Player overlay (redesigned 14 Nov 2026). Soft dark gradients at the top and bottom instead of solid bars.
 *
 * - Top: back, the title, then the things you set once per video (audio and subtitles, picture in picture, options).
 * - Middle: one row for moving through the video: previous, back 10 s, play/pause, forward 10 s, next.
 * - Bottom: elapsed time, a slim seek bar, total (tap it for time left); under it a row of small buttons for the
 *   rest (60 fps, speed, rotate, zoom, queue, lock).
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
    /** "1×", "1.5×"...: shown on the speed button, which only appears with [onSpeed]. */
    speedLabel: String? = null,
    onSpeed: (() -> Unit)? = null,
    /** Locks the screen against touches (the player shows its own unlock button). */
    onLock: (() -> Unit)? = null,
    onInteraction: () -> Unit = {},
) {
    var scrubbing by remember(title) { mutableStateOf(false) }
    var fraction by remember(title) { mutableFloatStateOf(0f) }
    // Quick taps on -10 / +10 add up and the player jumps once, after the last tap (4 Nov 2026): every jump restarts
    // smoothing, and many in a row could stall playback for a few seconds.
    var pendingJump by remember(title) { mutableStateOf<Long?>(null) }
    val jumpScope = rememberCoroutineScope()
    var jumpJob by remember(title) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun jump(by: Long) {
        if (duration <= 0) return
        val target = ((pendingJump ?: position) + by).coerceIn(0L, duration)
        pendingJump = target
        jumpJob?.cancel()
        jumpJob = jumpScope.launch {
            kotlinx.coroutines.delay(JUMP_SETTLE_MS)
            pendingJump = null
            onSeek(target)
        }
    }
    val shownPosition = pendingJump ?: if (scrubbing) (fraction.toDouble() * duration).toLong() else position
    var showRemaining by remember { mutableStateOf(false) }
    val ink = Color.White
    val muted = Color.White.copy(alpha = 0.72f)
    val disabled = Color.White.copy(alpha = 0.35f)
    val accent = MaterialTheme.colorScheme.tertiary

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 560.dp
        // Soft scrims instead of solid bars, so the picture stays visible behind the controls.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(132.dp)
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(210.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))),
        )

        // Middle: everything for moving through the video, within reach of either thumb.
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (wide) 26.dp else 10.dp),
        ) {
            IconButton(enabled = canPrevious, onClick = { onPrevious(); onInteraction() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Previous video", tint = if (canPrevious) ink else disabled, modifier = Modifier.size(30.dp))
            }
            IconButton(enabled = duration > 0, onClick = { jump(-10_000L); onInteraction() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Replay10, "Rewind 10 seconds", tint = if (duration > 0) ink else disabled, modifier = Modifier.size(36.dp))
            }
            IconButton(
                enabled = canPlay,
                onClick = { onTogglePlayback(); onInteraction() },
                modifier = Modifier.size(76.dp).background(Color(0x73000000), CircleShape),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    tint = if (canPlay) ink else disabled,
                    modifier = Modifier.size(46.dp),
                )
            }
            IconButton(enabled = duration > 0, onClick = { jump(10_000L); onInteraction() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = if (duration > 0) ink else disabled, modifier = Modifier.size(36.dp))
            }
            IconButton(enabled = canNext, onClick = { onNext(); onInteraction() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipNext, "Next video", tint = if (canNext) ink else disabled, modifier = Modifier.size(30.dp))
            }
        }

        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top: back, title, then audio/subtitles, picture in picture and options.
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back to library", tint = ink) }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = ink,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    queueLabel?.let { Text(it, color = muted, style = MaterialTheme.typography.labelSmall) }
                }
                IconButton(onClick = { onTracks(); onInteraction() }) { Icon(Icons.Filled.Subtitles, "Audio and subtitles", tint = ink) }
                onPip?.let { callback ->
                    IconButton(onClick = { callback(); onInteraction() }) { Icon(Icons.Filled.PictureInPictureAlt, "Picture in picture", tint = ink) }
                }
                IconButton(onClick = { onOptions(); onInteraction() }) { Icon(Icons.Filled.MoreVert, "Playback options", tint = ink) }
            }

            // Bottom: time, slim seek bar, time; then the small buttons.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        DateUtils.formatElapsedTime(shownPosition.coerceAtLeast(0) / 1000),
                        color = ink,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = if (scrubbing) fraction else ((pendingJump ?: position).toDouble() / duration.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat(),
                        onValueChange = { scrubbing = true; fraction = it; onInteraction() },
                        onValueChangeFinished = {
                            if (duration > 0) onSeek((fraction.toDouble() * duration).toLong())
                            scrubbing = false
                            onInteraction()
                        },
                        enabled = duration > 0,
                        thumb = {
                            Box(Modifier.size(if (scrubbing) 24.dp else 16.dp).background(accent, CircleShape))
                        },
                        track = { state ->
                            // A slim line: played part in the accent colour, the rest translucent white.
                            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                                val y = size.height / 2
                                val played = size.width * state.value.coerceIn(0f, 1f)
                                drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
                                drawLine(accent, Offset(0f, y), Offset(played, y), strokeWidth = size.height, cap = StrokeCap.Round)
                            }
                        },
                        // A tall touch zone (56 dp) around the slim line, so it is easy to grab.
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp).heightIn(min = 56.dp)
                            .semantics { contentDescription = "Playback position" },
                    )
                    // Total length; tap to see the time left instead.
                    Text(
                        when {
                            duration <= 0 -> "--:--"
                            showRemaining -> "-" + DateUtils.formatElapsedTime((duration - shownPosition).coerceAtLeast(0) / 1000)
                            else -> DateUtils.formatElapsedTime(duration / 1000)
                        },
                        color = ink,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(onClickLabel = "Switch between total time and time left") { showRemaining = !showRemaining; onInteraction() }
                            .padding(vertical = 6.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    onSmooth?.let { callback ->
                        ControlChip("60 fps", Icons.Filled.Animation, if (smoothOn) "60 fps smoothing is on" else "Turn on 60 fps smoothing", active = smoothOn) { callback(); onInteraction() }
                    }
                    if (onSpeed != null && speedLabel != null) {
                        ControlChip(speedLabel, Icons.Filled.Speed, "Playback speed: $speedLabel") { onSpeed(); onInteraction() }
                    }
                    if (onRotate != null) {
                        ControlChip("Rotate", Icons.Filled.ScreenRotation, "Rotate screen") { onRotate(); onInteraction() }
                    }
                    if (aspectLabel != null) {
                        ControlChip(aspectLabel, Icons.Filled.AspectRatio, "Zoom and aspect ratio: $aspectLabel") { onAspect(); onInteraction() }
                    }
                    onQueue?.let { callback ->
                        ControlChip("Queue", Icons.Filled.QueuePlayNext, "Playback queue") { callback(); onInteraction() }
                    }
                    onLock?.let { callback ->
                        ControlChip("Lock", Icons.Filled.Lock, "Lock the screen against touches") { callback() }
                    }
                }
            }
        }
    }
}

/** A small rounded button for the row under the seek bar: white when [active], see-through otherwise. */
@Composable
private fun ControlChip(label: String, icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    val tint = if (active) Color.Black else Color.White
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50))
            .background(if (active) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** How long after the last -10 / +10 tap the player jumps. */
private const val JUMP_SETTLE_MS = 450L
