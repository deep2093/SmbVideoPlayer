package com.skywing.desktop.playback

/** Settings → Playback → "Graphics for video" (plan 5.3). */
internal enum class GpuChoice(val label: String) {
    AUTOMATIC("Automatic"),
    PERFORMANCE("Performance"),
    POWER_SAVING("Power saving"),
}

/**
 * Which GPU draws the video and runs the motion engine. One GPU does both: drawing on one and smoothing on another
 * cost 398 drops in the M0 laptop run.
 */
internal data class GpuPlan(
    /** The GPU, or null when SkyWing knows none and Windows chooses. */
    val adapter: GpuAdapters.Adapter?,
    val mode: GpuMode,
    /** The PC's label, such as "Hybrid · external GPU". */
    val label: String,
    val reason: String,
    /** Text for mpv's `d3d11-adapter`: the adapter's exact name, or empty to let Windows choose. */
    val mpvAdapter: String,
    /** Another GPU shares this one's maker, so the motion engine picks its OpenCL device by LUID, not by maker. */
    val matchByLuid: Boolean,
) {
    fun describe(onBattery: Boolean?): String = listOf(label, "drawing and motion on " + (adapter?.name ?: "Windows' choice"),
        when (onBattery) { true -> "on battery"; false -> "plugged in"; null -> "power unknown" }, reason).joinToString(" · ")
}

/** The GPU policy (plan 5.3). Pure: everything it reads is passed in. */
internal object GpuPolicy {
    fun plan(inventory: GpuInventory, monitor: GpuAdapters.Adapter?, choice: GpuChoice, forced: GpuVendor? = null): GpuPlan {
        val adapters = inventory.adapters
        val (adapter, reason) = when {
            adapters.isEmpty() -> null to "Using Windows' choice of graphics"
            forced != null && adapters.any { it.gpuVendor == forced } ->
                (monitor?.takeIf { it.gpuVendor == forced } ?: adapters.first { it.gpuVendor == forced }) to "Forced by SKYWING_GPU"
            adapters.size == 1 -> adapters.single() to "Only one GPU"
            choice == GpuChoice.PERFORMANCE -> performance(adapters, monitor) to "Performance, chosen in Settings"
            choice == GpuChoice.POWER_SAVING -> powerSaving(adapters, monitor) to "Power saving, chosen in Settings"
            inventory.windowsPreference == WindowsGpuPreference.HIGH_PERFORMANCE ->
                performance(adapters, monitor) to "Following Windows' graphics setting"
            inventory.windowsPreference == WindowsGpuPreference.POWER_SAVING ->
                powerSaving(adapters, monitor) to "Following Windows' graphics setting"
            monitor != null && monitor in adapters -> monitor to "The screen is on this GPU"
            // Before M2 SkyWing asked mpv for "NVIDIA" when it couldn't tell the screen's GPU.
            else -> performance(adapters, null) to "The screen's GPU is unknown"
        }
        val index = adapter?.let(adapters::indexOf) ?: -1
        // mpv takes the first adapter whose name starts with the text it is given (d3d11_helpers.c), so a GPU listed
        // earlier whose name starts with this one's would be opened instead.
        val shadowed = adapter != null && adapters.take(index).any { it.name.startsWith(adapter.name, ignoreCase = true) }
        return GpuPlan(
            adapter = adapter,
            mode = inventory.mode(monitor),
            label = inventory.label(monitor),
            reason = if (shadowed) "$reason · Windows chooses: another GPU's name starts the same" else reason,
            mpvAdapter = if (adapter == null || shadowed) "" else adapter.name,
            matchByLuid = adapter != null && adapters.count { it.gpuVendor == adapter.gpuVendor } > 1,
        )
    }

    /** On a hybrid PC in Automatic, the dedicated GPU to offer when the integrated one can't keep up at 2×. */
    fun smoothingOffer(inventory: GpuInventory, plan: GpuPlan, choice: GpuChoice): GpuAdapters.Adapter? {
        val current = plan.adapter ?: return null
        if (choice != GpuChoice.AUTOMATIC || current.dedicated) return null
        return inventory.adapters.filter { it.dedicated }.maxByOrNull { it.dedicatedBytes }
    }

    fun offerLabel(adapter: GpuAdapters.Adapter): String = "Use " + when (adapter.gpuVendor) {
        GpuVendor.NVIDIA -> "NVIDIA"
        GpuVendor.AMD -> "AMD Radeon"
        GpuVendor.INTEL -> "Intel Arc"
        GpuVendor.OTHER -> adapter.name
    } + " for smoothing"

    /** The dedicated GPU: the screen's own when it is one, else the one with the most video memory. */
    private fun performance(adapters: List<GpuAdapters.Adapter>, monitor: GpuAdapters.Adapter?) =
        monitor?.takeIf { it.dedicated && it in adapters }
            ?: adapters.filter { it.dedicated }.maxByOrNull { it.dedicatedBytes }
            ?: monitor?.takeIf { it in adapters } ?: adapters.first()

    /** The integrated GPU: the screen's own when it is one, else the first. */
    private fun powerSaving(adapters: List<GpuAdapters.Adapter>, monitor: GpuAdapters.Adapter?) =
        monitor?.takeIf { !it.dedicated && it in adapters }
            ?: adapters.firstOrNull { !it.dedicated }
            ?: monitor?.takeIf { it in adapters } ?: adapters.first()
}

/**
 * Plan 5.8 rule 8: the graphics device was lost while a video played (a driver reset or update, the GPU removed or
 * hung). libplacebo then logs "Device lost!" and refuses every frame after it; mpv skips those frames without a word
 * and never re-creates its output by itself. SkyWing re-creates it, one step lower for each loss within a minute, and
 * stops after the third so a GPU that keeps failing can't keep it busy.
 */
internal class DeviceLoss(private val windowMillis: Long = 60_000) {
    enum class Recovery(val label: String) {
        REPLAN("video output re-created on a fresh GPU plan"),
        WINDOWS_CHOICE("video output re-created on Windows' choice of graphics"),
        GIVE_UP("not re-created: lost 3 times in a minute, so the video was paused"),
    }

    private val recent = ArrayDeque<Long>()
    var count = 0
        private set
    private var last: Recovery? = null

    /** The recovery for a loss at [nowMillis]: the n-th loss within [windowMillis] gets the n-th step. */
    fun lost(nowMillis: Long): Recovery {
        while (recent.isNotEmpty() && nowMillis - recent.first() >= windowMillis) recent.removeFirst()
        recent.addLast(nowMillis)
        count++
        return Recovery.entries[(recent.size - 1).coerceAtMost(Recovery.entries.lastIndex)].also { last = it }
    }

    fun summary(): String = last?.let { "$count time" + (if (count == 1) "" else "s") + "; last: " + it.label } ?: "never"

    companion object {
        /**
         * True for the message libplacebo logs at PL_ERR when D3D11 reports the device removed, reset or hung
         * (`pl_d3d11_check_device_removed`). "Failed presenting frame!" is not one: mpv logs it for any frame not shown.
         */
        fun message(level: String, text: String): Boolean = level in setOf("error", "fatal") && text.trim() == "Device lost!"
    }
}

/**
 * Takes a new value only once it has held for [holdMillis] (plan 5.3: re-plan after 5 s, never on every small
 * change). The first value is taken at once.
 */
internal class Debounce<T : Any>(private val holdMillis: Long) {
    private var taken: T? = null
    private var candidate: T? = null
    private var since = 0L

    /** The value to act on now, or null when nothing changed for long enough. */
    fun offer(value: T, nowMillis: Long): T? {
        if (taken == null) { taken = value; return value }
        if (value == taken) { candidate = null; return null }
        if (value != candidate) { candidate = value; since = nowMillis; return null }
        if (nowMillis - since < holdMillis) return null
        taken = value
        candidate = null
        return value
    }
}
