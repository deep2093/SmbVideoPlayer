package com.deepak.videolibrary.player

import kotlin.math.floor

/** A source-frame pair and blend/warp position for one target display timestamp. */
internal data class MotionFrameSample(
    val before: Long,
    val after: Long,
    val fraction: Double,
    val outputTimestampNs: Long,
)

/**
 * Initial 60-fps motion-interpolation *timing plan*.
 *
 * Handles arbitrary source FPS (24, 25, 28, 30, 39) without blindly doubling:
 * each target timestamp maps to neighbouring original frames. A future GPU MEMC
 * backend can use the (before, after, fraction) triple to synthesize pixels.
 *
 * This module does not synthesize pixels, invoke the codec, or advertise 60-fps
 * output as enabled. Normal decoding remains unchanged until GPU integration.
 */
internal object MotionFrameTiming {
    const val TARGET_FPS = 60.0

    fun sample(outputIndex: Long, sourceFps: Double, targetFps: Double = TARGET_FPS): MotionFrameSample {
        require(outputIndex >= 0L)
        require(sourceFps.isFinite() && sourceFps > 0.0)
        require(targetFps.isFinite() && targetFps > 0.0)
        val location = outputIndex.toDouble() * sourceFps / targetFps
        val before = floor(location).toLong()
        val fraction = (location - before).coerceIn(0.0, 1.0)
        val after = if (fraction < 1e-8) before else before + 1
        return MotionFrameSample(
            before = before,
            after = after,
            fraction = if (after == before) 0.0 else fraction,
            outputTimestampNs = (outputIndex.toDouble() * 1_000_000_000.0 / targetFps).toLong(),
        )
    }

    fun generatedFramesPerSecond(sourceFps: Double): Double =
        (TARGET_FPS - sourceFps).coerceAtLeast(0.0)
}
