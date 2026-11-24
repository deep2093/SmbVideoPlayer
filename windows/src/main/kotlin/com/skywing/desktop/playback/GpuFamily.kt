package com.skywing.desktop.playback

/**
 * Plan 5.8's GPU families, kept short (owner, 25 November): the note Motion options shows for the GPU that smooths.
 * Told by the name Windows lists, with no device-ID tables to keep up to date:
 * - graphics cards and the newest built-in chips: no note;
 * - older cards and other built-in graphics: "may struggle" above 1080p;
 * - other makers and virtual machines: "not tested".
 * Smoothing starts off on every PC, so the note is advice only; it changes no setting.
 */
internal object GpuFamily {
    /** The note for smoothing the video [caps] describes on [gpu], or null. */
    fun note(gpu: GpuAdapters.Adapter?, caps: SmoothingCapabilities): String? = when {
        // With Microsoft's software renderer drawing, "Install your graphics driver" says it; [gpu] is not in use.
        gpu == null || caps.softwareRender -> null
        gpu.gpuVendor == GpuVendor.OTHER -> "Not tested on this graphics chip"
        !MotionStops.type(caps).startsWith("1080") && mayStruggle(gpu) -> "May struggle on this graphics chip above 1080p"
        else -> null
    }

    private fun mayStruggle(gpu: GpuAdapters.Adapter): Boolean =
        if (gpu.dedicated) OLDER_CARD.containsMatchIn(gpu.name) else !NEWEST_BUILT_IN.containsMatchIn(gpu.name)

    // GeForce GTX 10 and older, GT, MX and 9xxM; Radeon RX 400/500, RX 5000, RX Vega, R5/R7/R9 and HD.
    private val OLDER_CARD = Regex(
        """GeForce\s+(GTX\s+(10|[2-9])\d\d(?!\d)|GT\s+\d|MX\s*\d|\d{3}MX?\b)|Radeon(\(TM\))?\s+(RX\s+([45]\d\d|5\d{3})(?!\d)|RX\s+Vega|R[579]\s|HD\s)""",
        RegexOption.IGNORE_CASE)
    // Lunar Lake's Arc 130V/140V and Panther Lake's Arc B3xx (they held 4K at 2× on battery in M0), and AMD's Strix Halo.
    private val NEWEST_BUILT_IN = Regex("""Arc(\(TM\))?\s+(\d{3}V|B\d{3})\b|Radeon(\(TM\))?\s+80\d0S\b""", RegexOption.IGNORE_CASE)
}
