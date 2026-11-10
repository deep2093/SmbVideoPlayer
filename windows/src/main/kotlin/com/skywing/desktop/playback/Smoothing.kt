package com.skywing.desktop.playback

import java.util.prefs.Preferences
import kotlin.math.abs

enum class SmoothingMode(val label: String, val fps: Int? = null) {
    OFF("Off"), VRR("G-SYNC / FreeSync"), AUTO("Auto"), FPS_60("60 FPS", 60), FPS_75("75 FPS", 75),
    FPS_90("90 FPS", 90), FPS_120("120 FPS", 120);

    companion object {
        fun parse(value: String?): SmoothingMode = entries.firstOrNull { it.name == value } ?: OFF
    }
}

data class SmoothingCapabilities(
    val displayHz: Double? = null,
    val sourceFps: Double? = null,
    val gpuOutput: Boolean = false,
    val hardwareDecode: Boolean = false,
    val hdrVideo: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
) {
    val resolution: String get() = if (width != null && height != null && width > 0 && height > 0)
        "${width} × ${height}" else "Resolution unavailable"
    // A GPU VO alone can be a software rasterizer. Require hardware decoding as a conservative
    // first-phase gate; this is not a benchmark or a promise of sustained 4K performance.
    fun unavailable(mode: SmoothingMode): String? = when {
        mode == SmoothingMode.OFF || mode == SmoothingMode.VRR -> null
        hdrVideo -> "HDR smoothing awaits display validation; ordinary HDR playback is kept"
        !gpuOutput || !hardwareDecode -> "Play a hardware-decoded video with GPU output first"
        displayHz == null || !displayHz.isFinite() || displayHz < 59.0 -> "Display refresh unavailable or below 60 Hz"
        mode.fps != null && abs(displayHz - mode.fps) / mode.fps > 0.01 ->
            "Requires an active ${mode.fps} Hz display mode"
        else -> null
    }

    fun decision(mode: SmoothingMode): SmoothingDecision {
        if (mode == SmoothingMode.OFF) return SmoothingDecision(false, "Smoothing off")
        if (mode == SmoothingMode.VRR) return SmoothingDecision(false,
            "Original motion · G-SYNC / FreeSync timing selected (driver activation not verified)")
        unavailable(mode)?.let { return SmoothingDecision(false, it) }
        val fps = sourceFps?.takeIf { it.isFinite() && it > 0 }
            ?: return SmoothingDecision(false, "Waiting for source frame rate")
        val hz = checkNotNull(displayHz)
        if (fps >= hz * 0.99) return SmoothingDecision(false, "Source already meets or exceeds display cadence")
        return SmoothingDecision(true, "Frame blending · display %.2f Hz".format(java.util.Locale.ROOT, hz))
    }
}

data class SmoothingDecision(val enabled: Boolean, val detail: String)
data class SmoothingStatus(
    val capabilities: SmoothingCapabilities = SmoothingCapabilities(),
    val detail: String = "Smoothing off",
    val blendingActive: Boolean = false,
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

    fun sample(nowMillis: Long, drops: Long?, playing: Boolean): Boolean {
        if (tripped) return true
        val before = previous
        previous = drops
        if (!playing || drops == null || before == null || drops < before) {
            warmUntil = nowMillis + 8000
            strikes = 0
        } else if (nowMillis >= warmUntil) {
            strikes = if (drops - before >= 3) strikes + 1 else 0
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
