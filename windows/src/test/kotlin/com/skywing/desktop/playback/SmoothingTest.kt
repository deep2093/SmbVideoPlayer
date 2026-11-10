package com.skywing.desktop.playback

import org.junit.Test
import kotlin.test.*

class SmoothingTest {
    private fun caps(hz: Double = 60.0, fps: Double = 24.0) = SmoothingCapabilities(hz, fps, true, true)

    @Test fun fixedChoicesFollowActualDisplayRatherThanInventingAnOutputRate() {
        SmoothingMode.entries.filter { it.fps != null }.forEach { mode ->
            val hz = checkNotNull(mode.fps).toDouble()
            assertTrue(caps(hz).decision(mode).enabled)
            assertTrue(caps(hz * 1000 / 1001).decision(mode).enabled)
            assertFalse(caps(hz + 10).decision(mode).enabled)
        }
        assertFalse(caps(144.0).decision(SmoothingMode.FPS_120).enabled)
        assertTrue(caps(144.0).decision(SmoothingMode.AUTO).enabled)
        assertFalse(caps(60.0).decision(SmoothingMode.FPS_120).enabled)
    }

    @Test fun unknownSoftwareAndHighFpsPlaybackBypassSafely() {
        assertFalse(SmoothingCapabilities().decision(SmoothingMode.AUTO).enabled)
        assertFalse(caps().copy(gpuOutput = false).decision(SmoothingMode.AUTO).enabled)
        assertFalse(caps().copy(hardwareDecode = false).decision(SmoothingMode.AUTO).enabled)
        assertFalse(caps().copy(hdrVideo = true).decision(SmoothingMode.AUTO).enabled)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 50.0).forEach {
            assertFalse(caps(it).decision(SmoothingMode.AUTO).enabled)
        }
        assertFalse(caps().copy(sourceFps = null).decision(SmoothingMode.AUTO).enabled)
        assertFalse(caps(60.0, 120.0).decision(SmoothingMode.AUTO).enabled)
        assertFalse(caps(59.94, 60.0).decision(SmoothingMode.AUTO).enabled)
        assertFalse(caps().decision(SmoothingMode.OFF).enabled)
        assertEquals(SmoothingMode.OFF, SmoothingMode.parse("future-unknown-mode"))
    }

    @Test fun sustainedDropsTripAndStayBypassedUntilReset() {
        val health = SmoothingHealth()
        assertFalse(health.sample(0, 0, true))
        assertFalse(health.sample(7000, 30, true)) // warmup is not a strike
        assertFalse(health.sample(8000, 33, true))
        assertFalse(health.sample(9000, 36, true))
        assertTrue(health.sample(10000, 39, true))
        assertTrue(health.sample(11000, 39, true))
        health.reset()
        assertFalse(health.sample(12000, 0, true))
    }

    @Test fun seeksCacheWaitsPausesAndCounterResetsDoNotTrip() {
        val health = SmoothingHealth()
        health.sample(0, 0, true)
        health.sample(8000, 3, true)
        health.sample(9000, 6, true)
        assertFalse(health.sample(10000, 100, false))
        assertFalse(health.sample(11000, 103, true))
        assertFalse(health.sample(18000, 0, true)) // new counter / stream
        assertFalse(health.sample(19000, 20, true))
        assertFalse(health.sample(20000, null, true))
        assertFalse(health.sample(30000, 50, true))
    }
}
