package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val PLAYER_INK = Color.White
private val PLAYER_MUTED = Color.White.copy(alpha = 0.7f)

/** Title line above the video: back arrow on the left, the title centred (as in the Android player). */
@Composable
internal fun PlayerTopBar(title: String, queueLabel: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF020307), Color(0xE6070B14))))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkyIconButton(SkyIcon.Back, "Back to the library", onBack, tint = PLAYER_INK)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title, color = PLAYER_INK, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
            )
            if (queueLabel != null) Text(queueLabel, color = PLAYER_MUTED, style = MaterialTheme.typography.labelSmall)
        }
        // Balances the back button so the title stays centred.
        Box(Modifier.size(40.dp))
    }
}

@Composable
private fun TextControl(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.size(width = 48.dp, height = 40.dp).clip(RoundedCornerShape(50)).skyClickable(onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) PLAYER_INK else PLAYER_INK.copy(alpha = 0.35f), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Controls under the video, in the Android layout: times, a slim seek bar, then one row with
 * subtitles on the left, previous / -10 / outlined play-pause / +10 / next in the middle, volume and
 * full screen on the right. (A native video window cannot be drawn over, so they sit beneath it.)
 */
@Composable
internal fun PlayerControls(
    position: Double,
    duration: Double,
    seekFraction: Float,
    paused: Boolean,
    muted: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    status: String,
    onSeekChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onBack10: () -> Unit,
    onForward10: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onSubtitlesOff: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onMute: () -> Unit,
    onFullscreen: () -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalSkyTheme.current
    val hasDuration = duration > 0
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xE6070B14), Color(0xFF020307))))
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(if (hasDuration) seekFraction / 100.0 * duration else position), color = PLAYER_INK, style = MaterialTheme.typography.labelLarge)
            Text(if (hasDuration) formatClock(duration) else "--:--", color = PLAYER_INK, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = seekFraction,
            onValueChange = onSeekChange,
            valueRange = 0f..100f,
            onValueChangeFinished = onSeekFinished,
            enabled = hasDuration,
            colors = SliderDefaults.colors(
                thumbColor = theme.accent,
                activeTrackColor = theme.accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                disabledThumbColor = Color.White.copy(alpha = 0.3f),
                disabledActiveTrackColor = Color.White.copy(alpha = 0.3f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                SkyIconButton(SkyIcon.Subtitles, "Next subtitle track", onSubtitles, tint = PLAYER_INK)
                TextControl("Off", onSubtitlesOff)
                TextControl("Audio", onAudio)
                SkyIconButton(SkyIcon.Folder, "Open a video from this PC", onOpenFile, tint = PLAYER_INK)
            }
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                SkyIconButton(SkyIcon.SkipPrevious, "Previous video", onPrevious, enabled = canPrevious, tint = PLAYER_INK)
                TextControl("-10", onBack10, enabled = hasDuration)
                SkyIconButton(
                    if (paused) SkyIcon.Play else SkyIcon.Pause,
                    if (paused) "Play" else "Pause",
                    onTogglePause,
                    modifier = Modifier.padding(horizontal = 6.dp),
                    tint = PLAYER_INK, box = 56.dp, glyph = 30.dp, outlined = true,
                )
                TextControl("+10", onForward10, enabled = hasDuration)
                SkyIconButton(SkyIcon.Stop, "Stop (S)", onStop, tint = PLAYER_INK)
                SkyIconButton(SkyIcon.SkipNext, "Next video", onNext, enabled = canNext, tint = PLAYER_INK)
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                TextControl("Vol -", onVolumeDown)
                SkyIconButton(if (muted) SkyIcon.VolumeOff else SkyIcon.VolumeUp, if (muted) "Unmute" else "Mute", onMute, tint = PLAYER_INK)
                TextControl("Vol +", onVolumeUp)
                SkyIconButton(SkyIcon.Fullscreen, "Full screen (F)", onFullscreen, tint = PLAYER_INK)
            }
        }
        if (status.isNotBlank()) {
            Text(
                status, color = PLAYER_MUTED, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}
