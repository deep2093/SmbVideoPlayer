package com.deepak.videolibrary.player

/**
 * Decides how far between the previous and the newest decoded frame a 60 Hz
 * output tick should be. The newest frame is held back by one source interval so
 * that every tick can blend two real frames (one frame of added latency).
 *
 * Pure logic with no Android types so it can be unit tested.
 */
internal class BlendClock {
    private var lastPts = Long.MIN_VALUE
    private var lastArrival = 0L
    private var gapNs = 0.0
    private var valid = false

    /** Source frame interval (ns) as seen by the display, smoothed; 0 until two frames arrived. */
    val smoothedGapNs: Long get() = gapNs.toLong()

    /** True when the newest frame has a predecessor and the source is slow enough to need new frames. */
    val needsInterpolation: Boolean get() = valid && gapNs >= PASS_THROUGH_GAP_NS

    /** True when the frame just recorded has a real predecessor to blend with. */
    fun onFrame(ptsNs: Long, arrivalNs: Long): Boolean {
        val ptsGap = ptsNs - lastPts
        if (lastPts == Long.MIN_VALUE || ptsGap <= 0L || ptsGap > RESET_GAP_NS) {
            // First frame, seek or discontinuity: show it immediately, learn the pace again.
            gapNs = 0.0
            valid = false
        } else {
            val arrivalGap = (arrivalNs - lastArrival).toDouble()
            val usable = arrivalGap in ptsGap / 4.0..ptsGap * 4.0
            val sample = if (usable) arrivalGap else ptsGap.toDouble()
            gapNs = if (gapNs == 0.0) sample else gapNs * 0.8 + sample * 0.2
            valid = true
        }
        lastPts = ptsNs
        lastArrival = arrivalNs
        return valid
    }

    /** 0 = previous frame, 1 = newest frame. */
    fun fraction(nowNs: Long): Float {
        if (!valid || gapNs <= 0.0) return 1f
        // Sources already near 60 fps are shown untouched, with no added latency.
        if (gapNs < PASS_THROUGH_GAP_NS) return 1f
        return ((nowNs - lastArrival) / gapNs).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val RESET_GAP_NS = 1_000_000_000L
        const val PASS_THROUGH_GAP_NS = 22_000_000.0
    }
}
