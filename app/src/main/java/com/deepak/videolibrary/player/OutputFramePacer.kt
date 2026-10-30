package com.deepak.videolibrary.player

/** Deadlines rather than an integer vsync divisor: 90 Hz must not become 45 fps. */
internal class OutputFramePacer(private val targetFps: Double = 60.0) {
    init { require(targetFps.isFinite() && targetFps > 0.0) }
    private val periodNs = 1e9 / targetFps
    private var nextNs = Double.NaN
    private var lastNs = Long.MIN_VALUE

    fun shouldDraw(vsyncNs: Long): Boolean {
        if (nextNs.isNaN() || vsyncNs <= lastNs || vsyncNs - lastNs > 250_000_000L) {
            nextNs = vsyncNs.toDouble()
        }
        lastNs = vsyncNs
        if (vsyncNs + 1.0 < nextNs) return false
        // Advance the output timeline, skipping deadlines missed by a slow GPU.
        nextNs += (kotlin.math.floor((vsyncNs - nextNs) / periodNs).coerceAtLeast(0.0) + 1) * periodNs
        return true
    }
}
