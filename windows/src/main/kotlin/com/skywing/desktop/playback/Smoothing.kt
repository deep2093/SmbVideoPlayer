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
) {
    val resolution: String get() = if (width != null && height != null) "$width × $height" else "Resolution unavailable"
    fun unavailable(mode: SmoothingMode): String? = when {
        mode == SmoothingMode.OFF -> null
        sourceFps != null && sourceFps > 60.001 -> "Already smooth"
        hdrVideo -> "HDR smoothing is not available yet"
        !gpuOutput || !hardwareDecode -> "Play a hardware-decoded video first"
        else -> null
    }
    fun decision(mode: SmoothingMode): SmoothingDecision = SmoothingDecision(false,
        unavailable(mode) ?: if (mode == SmoothingMode.OFF) "Smoothing off" else "Motion engine selected")
}
data class SmoothingDecision(val enabled: Boolean, val detail: String)
data class SmoothingStatus(
    val capabilities: SmoothingCapabilities = SmoothingCapabilities(),
    val detail: String = "Smoothing off",
    val motionActive: Boolean = false,
    val report: String = "Play a video to collect a playback report.",
    val settings: MotionSettings? = null,
    val nextLowerStop: Int? = null,
    val requestOff: Boolean = false,
)

/** Three consecutive ~1 s intervals with >=3 drops trip a per-file fallback after warmup.
 * Pause, seek, cache wait and counter resets restart warmup, so they cannot accumulate strikes.
 * Counts are player-reported drops, not an optical-flow quality score or output-FPS measurement.
 */
internal class SmoothingHealth {
    private var previous: Long? = null
    private var warmUntil = 0L
    private var strikes = 0
    var tripped = false
        private set

    fun reset() { previous = null; warmUntil = 0; strikes = 0; tripped = false }

    fun sample(nowMillis: Long, drops: Long?, playing: Boolean, processingFps: Double? = null, targetFps: Double? = null): Boolean {
        if (tripped) return true
        val before = previous
        previous = drops
        if (!playing || drops == null || before == null || drops < before) {
            warmUntil = nowMillis + 8000
            strikes = 0
        } else if (nowMillis >= warmUntil) {
            val slow = processingFps != null && targetFps != null && processingFps < targetFps * 0.95
            strikes = if (drops - before >= 3 || slow) strikes + 1 else 0
            if (strikes >= 3) tripped = true
        }
        return tripped
    }
}

object SmoothingStore {
    private val prefs by lazy { Preferences.userRoot().node("com/skywing/desktop/playback") }
    fun load(): SmoothingMode = runCatching { SmoothingMode.parse(prefs.get("smoothing", null)) }.getOrDefault(SmoothingMode.OFF)
    fun save(mode: SmoothingMode) { prefs.put("smoothing", mode.name) }
}
