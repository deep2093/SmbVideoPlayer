package com.deepak.videolibrary.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendClockTest {
    @Test fun sixtyFpsSourceCanInterpolateForNinetyFpsTarget() {
        val clock = BlendClock(90)
        clock.onFrame(0, 0)
        clock.onFrame(16_666_667, 16_666_667)
        assertTrue(clock.needsInterpolation)
        assertEquals(0.5f, clock.fraction(25_000_000), 0.0001f)
        clock.targetFps = 60
        assertFalse(clock.needsInterpolation)
        assertEquals(1f, clock.fraction(25_000_000), 0.0001f)
    }

    private val ms = 1_000_000L

    @Test fun callbackJitterDoesNotChangePresentationCadence() {
        val clock = BlendClock()
        clock.onFrame(0, 0)
        clock.onFrame(40 * ms, 80 * ms)
        assertEquals(40 * ms, clock.smoothedGapNs)
        assertEquals(0.5f, clock.fraction(100 * ms), 0.0001f)
    }

    @Test fun variableFrameIntervalsUseTheCurrentPair() {
        val clock = BlendClock()
        clock.onFrame(0, 0)
        clock.onFrame(40 * ms, 40 * ms)
        clock.onFrame(100 * ms, 100 * ms)
        assertEquals(0.5f, clock.fraction(130 * ms), 0.0001f)
    }

    @Test fun firstFrameIsShownImmediately() {
        val clock = BlendClock()
        assertFalse(clock.onFrame(0, 0))
        assertEquals(1f, clock.fraction(10 * ms), 0.0001f)
    }

    @Test fun blendsAcrossOneSourceInterval() {
        val clock = BlendClock()
        clock.onFrame(0, 0)
        assertTrue(clock.onFrame(41_666_667, 41 * ms))
        assertEquals(0f, clock.fraction(41 * ms), 0.0001f)
        assertEquals(0.5f, clock.fraction(41 * ms + 20 * ms + 833_000), 0.02f)
        assertEquals(1f, clock.fraction(200 * ms), 0.0001f)
    }

    @Test fun nearly60fpsSourcePassesThrough() {
        val clock = BlendClock()
        clock.onFrame(0, 0)
        clock.onFrame(16_666_667, 16 * ms)
        assertEquals(1f, clock.fraction(16 * ms + 2 * ms), 0.0001f)
    }

    @Test fun seekResetsBlending() {
        val clock = BlendClock()
        clock.onFrame(0, 0)
        clock.onFrame(41 * ms, 41 * ms)
        assertFalse(clock.onFrame(60_000 * ms, 100 * ms))
        assertEquals(1f, clock.fraction(101 * ms), 0.0001f)
        assertFalse(clock.onFrame(10 * ms, 140 * ms))
    }

    @Test fun jitteredArrivalsKeepASmoothedInterval() {
        val clock = BlendClock()
        var arrival = 0L
        clock.onFrame(0, 0)
        for (i in 1..30) {
            arrival += if (i % 2 == 0) 33 * ms else 50 * ms
            clock.onFrame(i * 41_666_667L, arrival)
        }
        assertEquals(41.5, clock.smoothedGapNs / 1e6, 5.0)
    }
}
