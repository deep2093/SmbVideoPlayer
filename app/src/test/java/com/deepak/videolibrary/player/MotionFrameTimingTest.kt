package com.deepak.videolibrary.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionFrameTimingTest {
    @Test fun maps24fpsFramesTo60HzCorrectly() {
        val first = MotionFrameTiming.sample(0, 24.0)
        assertEquals(0L, first.before)
        assertEquals(0L, first.after)
        assertEquals(0.0, first.fraction, 0.00001)
        val next = MotionFrameTiming.sample(1, 24.0)
        assertEquals(0L, next.before)
        assertEquals(1L, next.after)
        assertEquals(0.4, next.fraction, 0.00001)
        assertEquals(36.0, MotionFrameTiming.generatedFramesPerSecond(24.0), 0.00001)
    }

    @Test fun handlesNonIntegerMultiples() {
        for (fps in listOf(25.0, 28.0, 30.0, 39.0)) {
            val samples = (0L..59L).map { MotionFrameTiming.sample(it, fps) }
            assertTrue(samples.zipWithNext().all { (a, b) -> b.outputTimestampNs > a.outputTimestampNs })
            assertTrue(samples.all { it.fraction in 0.0..1.0 })
            assertEquals(60.0 - fps, MotionFrameTiming.generatedFramesPerSecond(fps), 0.00001)
        }
    }

    @Test fun native60NeedsNoNewFrames() {
        for (index in 0L..59L) {
            val sample = MotionFrameTiming.sample(index, 60.0)
            assertEquals(sample.before, sample.after)
            assertEquals(0.0, sample.fraction, 0.00001)
        }
    }
}
