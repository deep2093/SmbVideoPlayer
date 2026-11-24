package com.skywing.desktop.playback

import java.util.prefs.Preferences
import kotlin.math.abs
import kotlin.math.roundToInt

/** Parameters verified against the pinned open-svpflow parsers. Output is never downscaled. */
enum class MotionQuality(val label: String, val superOptions: String, val analyse: String, val smooth: String) {
    FASTEST("Fastest", "{gpu:1,pel:1,scale:{up:0}}", "{block:{w:32,h:32,overlap:0},main:{search:{coarse:{distance:-4}}}}", "algo:2,mask:{area:0}"),
    FAST("Fast", "{gpu:1,pel:1}", "{block:{w:32,h:32,overlap:1}}", "algo:13,mask:{area:30}"),
    BALANCED("Balanced", "{gpu:1,pel:2}", "{block:{w:16,h:16,overlap:2}}", "algo:13,mask:{cover:80,area:30}"),
    BETTER("Better", "{gpu:1,pel:2}", "{block:{w:16,h:16,overlap:2},refine:[{thsad:200}]}", "algo:23,mask:{cover:80,area:30}"),
    // Half-block overlap stays even after refinement, including on the CPU renderer.
    BEST("Best", "{gpu:1,pel:4}", "{block:{w:8,h:8,overlap:3},refine:[{thsad:200}]}", "algo:23,mask:{cover:100,area:50}");

    companion object {
        /** Fast held 2× without drops on the laptop and both handhelds, also on battery (M0). */
        val DEFAULT = FAST
        /** A video type's quality until it saves its own (owner, 25 November): Balanced with a graphics card of its own, otherwise Fast. */
        fun startFor(dedicatedGpu: Boolean): MotionQuality = if (dedicatedGpu) BALANCED else DEFAULT
    }
}

/**
 * Plan 5.7 experiments, for measuring only. They are not in Settings and are never saved for a video type: only the
 * hidden benchmark option `--experiment` picks one. An experiment replaces the preset's three option sets, and the
 * reports name it.
 */
enum class MotionExperiment(val label: String, val superOptions: String, val analyse: String, val smooth: String) {
    /**
     * The owner's PotPlayer 2× script (MVTools) as near as open-svpflow allows. `docs/modernise/reports/potplayer-script.md`
     * keeps the script and maps each setting, with what open-svpflow can't express: `chroma=false`, the zero padding,
     * MRecalculate's one step from 32 to 8, MBlockFps and `Prefetch(1)`.
     */
    POTPLAYER_LIKE(
        "PotPlayer-like", "{gpu:1,pel:1}",
        "{block:{w:32,h:32,overlap:0},main:{search:{distance:1,coarse:{distance:3,satd:false,width:0}},penalty:{plevel:0}}," +
            "refine:[{thsad:200},{thsad:200}]}",
        "algo:2,mask:{area:0}",
    );

    companion object {
        fun find(label: String?): MotionExperiment? = entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
    }
}

data class MotionSettings(val stop: Int, val quality: MotionQuality = MotionQuality.DEFAULT, val experiment: MotionExperiment? = null) {
    /** The reports' quality: the preset, and an experiment that replaces it. */
    val qualityLabel: String get() =
        quality.label + (experiment?.let { "; experiment: ${it.label} (test only, replaces the preset)" } ?: "")
}

internal object MotionStops {
    val labels = listOf("2×", "2.5×", "3×", "4×")
    private val halves = listOf(4, 5, 6, 8)
    fun rate(source: MotionRate, stop: Int): MotionRate {
        val n = source.numerator * halves[stop.coerceIn(0, 3)]
        val d = source.denominator * 2
        val divisor = gcd(n.toLong(), d.toLong()).toInt()
        return MotionRate(n / divisor, d / divisor)
    }
    /** The refresh to plan for: the screen's current mode, else 60 Hz, never a guess above it (B30). */
    private fun refresh(hz: Double?): Double = hz?.takeIf { it.isFinite() && it > 0 } ?: 60.0
    /** The stops the screen can show; only 2× when the motion engine runs on the [processor] (plan 5.8 rule 4). */
    fun allowed(source: MotionRate, hz: Double?, processor: Boolean = false): List<Int> =
        (0..if (processor) 0 else 3).filter { rate(source, it).fps <= refresh(hz) + 0.01 }
    /**
     * A rate a fixed screen shows evenly: its refresh or a whole divisor of it, within the 1% mpv's display-resample
     * corrects (60 fps on 120 Hz, 48 or 60 fps on 240 Hz).
     */
    fun fits(fps: Double, hz: Double): Boolean {
        val frames = (hz / fps).roundToInt()
        return frames >= 1 && abs(hz / frames - fps) / fps < 0.01
    }
    /**
     * The first-use stop: 2× from 50 fps up, otherwise the stop closest to 60 fps. On a fixed screen ([vrr] false) a
     * stop that the screen shows evenly goes first when there is one (B31).
     */
    fun default(source: MotionRate, hz: Double? = null, vrr: Boolean = false): Int {
        if (source.fps >= 49.9) return 0
        val even = if (vrr) emptyList() else allowed(source, hz).filter { fits(rate(source, it).fps, refresh(hz)) }
        return even.ifEmpty { (0..3).toList() }.minBy { abs(rate(source, it).fps - 60.0) }
    }
    /** "May judder on this N Hz screen" for an allowed stop a fixed screen can't show evenly (B31); null otherwise. */
    fun note(source: MotionRate, stop: Int, hz: Double?, vrr: Boolean): String? {
        val screen = hz?.takeIf { it.isFinite() && it > 0 } ?: return null
        val fps = rate(source, stop).fps
        if (vrr || fps > screen + 0.01 || fits(fps, screen)) return null
        return "May judder on this ${screen.roundToInt()} Hz screen"
    }
    fun type(caps: SmoothingCapabilities): String {
        val short = minOf(caps.width ?: 0, caps.height ?: 0)
        val long = maxOf(caps.width ?: 0, caps.height ?: 0)
        val resolution = if (short <= 1080 && long <= 1920) "1080" else if (short <= 1440 && long <= 2560) "1440" else "4k"
        return resolution + if (caps.hdrVideo) "-hdr" else "-sdr"
    }
}

internal class MotionPreferences(private val prefs: Preferences, private val dedicatedGpu: () -> Boolean = { GpuAdapters.hasDedicated }) {
    fun load(key: String, defaultStop: Int): MotionSettings = MotionSettings(
        prefs.getInt("motion.$key.stop", defaultStop).coerceIn(0, 3),
        MotionQuality.entries.firstOrNull { it.name == prefs.get("motion.$key.quality", null) } ?: MotionQuality.startFor(dedicatedGpu()))
    fun save(key: String, settings: MotionSettings) {
        prefs.putInt("motion.$key.stop", settings.stop.coerceIn(0, 3))
        prefs.put("motion.$key.quality", settings.quality.name)
    }
    val vrrOverride: Boolean? get() = prefs.get("motion.vrr", null)?.toBooleanStrictOrNull()
    /**
     * "Let VRR control refresh (fullscreen)" (plan 5.4): until chosen, on where the screen can vary its refresh
     * ([VrrScreen]) and off on every other screen (owner, 25 November). A choice then holds on every screen; one saved
     * with the old "My screen uses G-SYNC / FreeSync" switch, under the same key, is kept.
     */
    fun vrr(screenCanVary: Boolean): Boolean = vrrOverride ?: screenCanVary
    fun chooseVrr(on: Boolean) = prefs.putBoolean("motion.vrr", on)
    /** "Smooth at screen size" (plan 5.7 item 4): off until the owner has compared it by eye. */
    var smoothAtScreenSize: Boolean
        get() = prefs.getBoolean("motion.screenSize", false)
        set(value) { prefs.putBoolean("motion.screenSize", value) }
    var lowerAutomatically: Boolean
        get() = prefs.getBoolean("motion.lowerAutomatically", false)
        set(value) { prefs.putBoolean("motion.lowerAutomatically", value) }
    /** Settings → Playback → "Graphics for video" (plan 5.3). */
    var gpuChoice: GpuChoice
        get() = GpuChoice.entries.firstOrNull { it.name == prefs.get("playback.gpu", null) } ?: GpuChoice.AUTOMATIC
        set(value) { prefs.put("playback.gpu", value.name) }
    /** Remembers "Use <dedicated GPU> for smoothing" for one video class ([MotionStops.type]). */
    fun useDedicatedFor(key: String) = prefs.putBoolean("motion.$key.dedicated", true)
    /** The choice for a video class: Settings' own, unless it is Automatic and the class keeps the dedicated GPU. */
    fun gpuChoiceFor(key: String): GpuChoice = gpuChoice.takeIf { it != GpuChoice.AUTOMATIC }
        ?: if (prefs.getBoolean("motion.$key.dedicated", false)) GpuChoice.PERFORMANCE else GpuChoice.AUTOMATIC
}

internal object MotionSettingsStore {
    val values by lazy { MotionPreferences(Preferences.userRoot().node("com/skywing/desktop/playback")) }
}
