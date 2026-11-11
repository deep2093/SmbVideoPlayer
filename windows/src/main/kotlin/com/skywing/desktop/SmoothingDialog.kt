package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.skywing.desktop.playback.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.math.roundToInt

@Composable
internal fun SmoothingDialog(mode: SmoothingMode, status: SmoothingStatus,
    onMode: (SmoothingMode) -> Unit, onClose: () -> Unit) {
    DialogWindow(onCloseRequest = onClose, title = "Motion options",
        state = rememberDialogState(width = 620.dp, height = 600.dp)) {
        Column(Modifier.fillMaxSize().background(LocalSkyTheme.current.background)
            .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SmoothingOptions(mode, status, onMode)
            SkyButton("Done", onClose)
        }
    }
}

@Composable
internal fun SmoothingOptions(mode: SmoothingMode, status: SmoothingStatus, onMode: (SmoothingMode) -> Unit) {
    val theme = LocalSkyTheme.current
    val caps = status.capabilities
    val alreadySmooth = (caps.sourceFps ?: 0.0) > 60.001
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Motion smoothing", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Switch(checked = mode == SmoothingMode.MOTION && !alreadySmooth,
            onCheckedChange = { onMode(if (it) SmoothingMode.MOTION else SmoothingMode.OFF) }, enabled = !alreadySmooth)
    }
    if (alreadySmooth) { Text("Already smooth", color = theme.muted); return }
    if (mode == SmoothingMode.OFF) return
    val source = caps.sourceFps?.let(MotionRate::source)
    if (source != null) {
        val key = MotionStops.type(caps)
        val settings = status.settings ?: MotionSettingsStore.values.load(key, MotionStops.default(source))
        val allowed = MotionStops.allowed(source, caps.displayHz)
        var rate by remember(key, settings.stop) { mutableStateOf(settings.stop.toFloat()) }
        var quality by remember(key, settings.quality) { mutableStateOf(settings.quality.ordinal.toFloat()) }
        Text("Frame rate", color = theme.ink)
        Slider(value = rate, onValueChange = { value ->
            allowed.minByOrNull { kotlin.math.abs(it-value) }?.let { rate = it.toFloat() }
        }, valueRange = 0f..3f, steps = 2, enabled = allowed.isNotEmpty(),
            onValueChangeFinished = { MotionSettingsStore.values.save(key, MotionSettingsStore.values.load(key, settings.stop).copy(stop = rate.roundToInt())) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (0..3).forEach { stop ->
                Text(MotionStops.rate(source, stop).label + " fps (" + MotionStops.labels[stop] + ")",
                    color = if (stop in allowed) theme.ink else theme.muted.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Quality · " + MotionQuality.entries[quality.roundToInt()].label, color = theme.ink)
        Slider(value = quality, onValueChange = { quality = it }, valueRange = 0f..4f, steps = 3,
            onValueChangeFinished = { MotionSettingsStore.values.save(key,
                MotionSettingsStore.values.load(key, settings.stop).copy(quality = MotionQuality.entries[quality.roundToInt()])) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MotionQuality.entries.forEach { Text(it.label, color = theme.muted, style = MaterialTheme.typography.labelSmall) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status.detail, color = theme.ink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            status.nextLowerStop?.let { lower ->
                SkyButton("Try " + MotionStops.rate(source, lower).label, {
                    MotionSettingsStore.values.save(key, settings.copy(stop = lower))
                })
            }
        }
    } else Text(status.detail, color = theme.muted)
    var copied by remember { mutableStateOf(false) }
    SkyButton(if (copied) "Copied" else "Copy playback report", {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(status.report), null) }
            .onSuccess { copied = true }
    }, primary = false)
}

@Composable
internal fun MotionPlaybackSettings() {
    var vrr by remember { mutableStateOf(MotionSettingsStore.values.vrr) }
    var lower by remember { mutableStateOf(MotionSettingsStore.values.lowerAutomatically) }
    val theme = LocalSkyTheme.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("My screen uses G-SYNC / FreeSync", color = theme.ink)
        Switch(vrr, { vrr = it; MotionSettingsStore.values.vrr = it })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Lower automatically when needed", color = theme.ink)
        Switch(lower, { lower = it; MotionSettingsStore.values.lowerAutomatically = it })
    }
}
