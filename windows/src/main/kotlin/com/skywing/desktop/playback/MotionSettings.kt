package com.skywing.desktop.playback

import java.util.prefs.Preferences
import kotlin.math.abs

/** Parameters verified against the pinned open-svpflow parsers. Output is never downscaled. */
enum class MotionQuality(val label: String, val superOptions: String, val analyse: String, val smooth: String) {
    FASTEST("Fastest", "{gpu:1,pel:1,scale:{up:0}}", "{block:{w:32,h:32,overlap:0},main:{search:{coarse:{distance:-4}}}}", "algo:2,mask:{area:0}"),
    FAST("Fast", "{gpu:1,pel:1}", "{block:{w:32,h:32,overlap:1}}", "algo:13,mask:{area:30}"),
    BALANCED("Balanced", "{gpu:1,pel:2}", "{block:{w:16,h:16,overlap:2}}", "algo:13,mask:{cover:80,area:30}"),
    BETTER("Better", "{gpu:1,pel:2}", "{block:{w:16,h:16,overlap:2},refine:[{thsad:200}]}", "algo:23,mask:{cover:80,area:30}"),
    // Half-block overlap stays even after refinement, including on the CPU renderer.
    BEST("Best", "{gpu:1,pel:4}", "{block:{w:8,h:8,overlap:3},refine:[{thsad:200}]}", "algo:23,mask:{cover:100,area:50}");
}

data class MotionSettings(val stop: Int, val quality: MotionQuality = MotionQuality.BALANCED)

internal object MotionStops {
    val labels = listOf("2×", "2.5×", "3×", "4×")
    private val halves = listOf(4, 5, 6, 8)
    fun rate(source: MotionRate, stop: Int): MotionRate {
        val n = source.numerator * halves[stop.coerceIn(0, 3)]
        val d = source.denominator * 2
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val divisor = gcd(n, d)
        return MotionRate(n / divisor, d / divisor)
    }
    fun allowed(source: MotionRate, hz: Double?): List<Int> = (0..3).filter {
        rate(source, it).fps <= (hz?.takeIf { v -> v.isFinite() && v > 0 } ?: 240.0) + 0.01
    }
    fun default(source: MotionRate): Int = if (source.fps >= 49.9) 0 else
        (0..3).minBy { abs(rate(source, it).fps - 60.0) }
    fun type(caps: SmoothingCapabilities): String {
        val short = minOf(caps.width ?: 0, caps.height ?: 0)
        val long = maxOf(caps.width ?: 0, caps.height ?: 0)
        val resolution = if (short <= 1080 && long <= 1920) "1080" else if (short <= 1440 && long <= 2560) "1440" else "4k"
        return resolution + if (caps.hdrVideo) "-hdr" else "-sdr"
    }
}

internal class MotionPreferences(private val prefs: Preferences, private val detectedVrr: () -> Boolean? = { null }) {
    fun load(key: String, defaultStop: Int): MotionSettings = MotionSettings(
        prefs.getInt("motion.$key.stop", defaultStop).coerceIn(0, 3),
        MotionQuality.entries.firstOrNull { it.name == prefs.get("motion.$key.quality", "BALANCED") } ?: MotionQuality.BALANCED)
    fun save(key: String, settings: MotionSettings) {
        prefs.putInt("motion.$key.stop", settings.stop.coerceIn(0, 3))
        prefs.put("motion.$key.quality", settings.quality.name)
    }
    val vrrOverride: Boolean? get() = prefs.get("motion.vrr", null)?.toBooleanStrictOrNull()
    var vrr: Boolean
        get() = vrrOverride ?: (detectedVrr() == true)
        set(value) { prefs.putBoolean("motion.vrr", value) }
    var lowerAutomatically: Boolean
        get() = prefs.getBoolean("motion.lowerAutomatically", false)
        set(value) { prefs.putBoolean("motion.lowerAutomatically", value) }
}

internal object MotionSettingsStore {
    val values by lazy { MotionPreferences(Preferences.userRoot().node("com/skywing/desktop/playback")) { DisplayVrr.current.supported } }
}
