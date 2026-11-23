package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
        SkySwitch(checked = mode == SmoothingMode.MOTION && !alreadySmooth,
            onCheckedChange = { onMode(if (it) SmoothingMode.MOTION else SmoothingMode.OFF) }, enabled = !alreadySmooth,
            name = "Motion smoothing")
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
            modifier = Modifier.accessibleName("Frame rate").semantics {
                val stop = rate.roundToInt()
                stateDescription = MotionStops.rate(source, stop).label + " fps (" + MotionStops.labels[stop] + ")"
            },
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
            modifier = Modifier.accessibleName("Quality").semantics {
                stateDescription = MotionQuality.entries[quality.roundToInt()].label
            },
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
            // Remembered for this kind of video; the player moves it to that GPU on its next check.
            status.gpuOffer?.let { offer -> SkyButton(offer, { MotionSettingsStore.values.useDedicatedFor(key) }) }
        }
    } else Text(status.detail, color = theme.muted)
    var copied by remember { mutableStateOf(false) }
    SkyButton(if (copied) "Copied" else "Copy playback report", {
        val report = PerfMonitor.reportLine()?.let { status.report.trimEnd() + "\n" + it + "\n" } ?: status.report
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(report), null) }
            .onSuccess { copied = true }
    }, primary = false)
}

/** Settings → Playback → "Graphics for video" (plan 5.3). A PC with one GPU has nothing to choose. */
@Composable
internal fun GraphicsForVideoSetting() {
    val theme = LocalSkyTheme.current
    val adapters = remember { runCatching { GpuAdapters.list() }.getOrDefault(emptyList()) }
    var choice by remember { mutableStateOf(MotionSettingsStore.values.gpuChoice) }
    Text("Graphics for video", color = theme.ink, style = MaterialTheme.typography.titleMedium)
    if (adapters.size < 2) {
        Text(adapters.singleOrNull()?.let { "This PC has one GPU: " + it.name + "." } ?: "Windows chooses the graphics.",
            color = theme.muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    SkyChipBar(GpuChoice.entries.map { it.label }, choice.ordinal, { index ->
        choice = GpuChoice.entries[index]
        MotionSettingsStore.values.gpuChoice = choice
    })
    Text(when (choice) {
        GpuChoice.AUTOMATIC -> "The GPU that drives the screen draws and smooths the video, unless Windows' graphics setting for SkyWing says otherwise."
        GpuChoice.PERFORMANCE -> "The graphics card draws and smooths the video. It keeps up better and uses more power."
        GpuChoice.POWER_SAVING -> "The processor's graphics draw and smooth the video. It uses less power; smoothing may not keep up."
    } + " Starts with the next video, or now while motion smoothing is on.", color = theme.muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun MotionPlaybackSettings() {
    var vrrChoice by remember { mutableStateOf(MotionSettingsStore.values.vrrOverride) }
    val capability = DisplayVrr.current
    val vrr = vrrChoice ?: (capability.supported == true)
    var lower by remember { mutableStateOf(MotionSettingsStore.values.lowerAutomatically) }
    val theme = LocalSkyTheme.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("My screen uses G-SYNC / FreeSync", color = theme.ink)
        SkySwitch(vrr, { vrrChoice = it; MotionSettingsStore.values.vrr = it }, "My screen uses G-SYNC / FreeSync")
    }
    Text(if (vrrChoice != null) "Your saved choice takes priority." else when (capability.supported) {
        true -> "On by default: the display reports VRR support."
        false -> "Off by default: the display reports no VRR support."
        null -> "VRR capability unavailable. Turn this on if your screen uses G-SYNC / FreeSync."
    }, color = theme.muted, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Lower automatically when needed", color = theme.ink)
        SkySwitch(lower, { lower = it; MotionSettingsStore.values.lowerAutomatically = it }, "Lower automatically when needed")
    }
}
