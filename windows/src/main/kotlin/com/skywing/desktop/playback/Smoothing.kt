package com.skywing.desktop.playback

import java.util.prefs.Preferences

enum class SmoothingMode(val label: String) {
    OFF("Off"), MOTION("On");
    companion object { fun parse(value: String?): SmoothingMode = if (value == "MOTION") MOTION else OFF }
}

data class SmoothingCapabilities(
    val displayHz: Double? = null, val sourceFps: Double? = null,
    val gpuOutput: Boolean = false, val hardwareDecode: Boolean = false,
    val hdrVideo: Boolean = false, val width: Int? = null, val height: Int? = null,
    /** The frame shown now is interlaced, or mpv is deinterlacing it (plan 5.8 rule 9); null until a frame is shown. */
    val interlaced: Boolean? = null,
    /** The PC has no OpenCL device, so the motion engine would run on the processor (plan 5.8 rule 4). */
    val processor: Boolean = false,
    /** mpv draws with Microsoft's software renderer: no graphics driver, or remote desktop (plan 5.8 rule 3). */
    val softwareRender: Boolean = false,
    /** The decoding methods that failed for this file, in order, before mpv tried the next one (plan 5.8 rule 2). */
    val decoderFailures: List<String> = emptyList(),
) {
    val resolution: String get() = if (width != null && height != null) "$width × $height" else "Resolution unavailable"
    fun unavailable(mode: SmoothingMode): String? = when {
        mode == SmoothingMode.OFF -> null
        sourceFps != null && sourceFps > 60.001 -> "Already smooth"
        hdrVideo -> "HDR smoothing is not available yet"
        // Fields are never smoothed as if they were frames; with deinterlace=auto mpv deinterlaces the video instead.
        interlaced == true -> "Interlaced video isn’t smoothed; it is deinterlaced instead"
        softwareRender -> "Install your graphics driver for smooth playback"
        gpuOutput && !hardwareDecode && decoderFailures.isNotEmpty() -> "Decoding fell back to software, so this video isn’t smoothed"
        !gpuOutput || !hardwareDecode -> "Play a hardware-decoded video first"
        // Some Intel and AMD drivers install OpenCL only with the full driver package. The processor keeps up only to 1080p.
        processor && !MotionStops.type(this).startsWith("1080") -> "Update your graphics driver: without OpenCL, smoothing works only up to 1080p at 2×"
        // Any reported rate from 1 fps can be smoothed, including a variable frame rate's average (MotionRate.source).
        // Slower video, such as a slideshow, is named rather than left waiting for a rate.
        sourceFps != null && sourceFps > 0 && sourceFps < MotionRate.LOWEST_FPS -> "Can’t smooth video below 1 fps"
        else -> null
    }
    fun decision(mode: SmoothingMode): SmoothingDecision = SmoothingDecision(false,
        unavailable(mode) ?: if (mode == SmoothingMode.OFF) "Smoothing off" else "Motion engine selected")
}
data class SmoothingDecision(val enabled: Boolean, val detail: String)

/**
 * Plan 5.8 rule 2. mpv already steps down the decoders itself (`hwdec=auto-safe` tries each method in turn, and
 * `hwdec-software-fallback` drops one after decoding errors mid-file), going on from the same frame. It says so in
 * one message, at warn once a hardware decoder was in use (`vd_lavc.c`); SkyWing reads that message to report it.
 */
internal object DecoderFallback {
    private const val STEP = "Attempting next decoding method after failure of "
    /** The method that failed, from one mpv log message, or null when the message is not that one. */
    fun failedMethod(prefix: String, text: String): String? =
        text.takeIf { prefix == "vd" && it.startsWith(STEP) }?.removePrefix(STEP)?.trim()?.removeSuffix(".")
}
data class SmoothingStatus(
    val capabilities: SmoothingCapabilities = SmoothingCapabilities(),
    val detail: String = "Smoothing off",
    val motionActive: Boolean = false,
    val report: String = "Play a video to collect a playback report.",
    /** "Copy test report" (plan 5.6): the build, GPU plan, screen, HDR and VRR state and the per-minute table. */
    val testReport: String = "Play a video with smoothing on to collect a test report.",
    val settings: MotionSettings? = null,
    val nextLowerStop: Int? = null,
    val requestOff: Boolean = false,
    /** "Use <dedicated GPU> for smoothing" when a hybrid PC's integrated GPU can't keep up at 2× (plan 5.3), else null. */
    val gpuOffer: String? = null,
)

/** Three consecutive ~1 s intervals with >=3 drops trip a per-file fallback after warmup.
 * Pause, seek, cache wait and counter resets restart warmup, so they cannot accumulate strikes.
 * Counts are player-reported drops, not an optical-flow quality score or output-FPS measurement.
 */
internal class SmoothingHealth {
    private var previous: Long? = null
    private var warmUntil = 0L
    private var strikes = 0
    private var healthy = 0
    var tripped = false
        private set

    fun reset() { previous = null; warmUntil = 0; strikes = 0; healthy = 0; tripped = false }

    fun sample(nowMillis: Long, drops: Long?, playing: Boolean, processingFps: Double? = null, targetFps: Double? = null): Boolean {
        val before = previous
        previous = drops
        if (!playing || drops == null || before == null || drops < before) {
            warmUntil = nowMillis + 8000
            strikes = 0
            healthy = 0
            tripped = false
        } else if (nowMillis >= warmUntil) {
            val dropping = drops - before >= 3
            strikes = if (dropping) strikes + 1 else 0
            healthy = if (dropping) 0 else healthy + 1
            if (strikes >= 3) tripped = true
            if (healthy >= 3) tripped = false
        }
        return tripped
    }
}

object SmoothingStore {
    private val prefs by lazy { Preferences.userRoot().node("com/skywing/desktop/playback") }
    fun load(): SmoothingMode = runCatching { SmoothingMode.parse(prefs.get("smoothing", null)) }.getOrDefault(SmoothingMode.OFF)
    fun save(mode: SmoothingMode) { prefs.put("smoothing", mode.name) }
}
