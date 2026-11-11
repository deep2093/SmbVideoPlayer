package com.skywing.desktop.playback

import org.junit.Test
import kotlin.test.*

class SmoothingTest {
    @Test fun retiredModesMigrateToOff() {
        assertEquals(SmoothingMode.MOTION, SmoothingMode.parse("MOTION"))
        listOf(null, "OFF", "AUTO", "VRR", "FPS_60", "FPS_75", "FPS_90", "FPS_120", "unknown").forEach {
            assertEquals(SmoothingMode.OFF, SmoothingMode.parse(it))
        }
    }
    @Test fun exactStopsDefaultsAndDisplayLimits() {
        val film = MotionRate(24000, 1001)
        assertEquals(MotionRate(60000, 1001), MotionStops.rate(film, 1))
        assertEquals(MotionRate(125, 2), MotionStops.rate(MotionRate(25), 1))
        assertEquals(listOf(0, 1), MotionStops.allowed(film, 60.0))
        assertEquals(listOf(0), MotionStops.allowed(MotionRate(25), 60.0))
        assertEquals(1, MotionStops.default(MotionRate(24)))
        assertEquals(1, MotionStops.default(MotionRate(25)))
        listOf(30, 50, 60).forEach { assertEquals(0, MotionStops.default(MotionRate(it))) }
        assertEquals(MotionRate(240), MotionRate.target(MotionRate(60), 240.0, 3))
        assertNull(MotionRate.target(MotionRate(120), 240.0))
    }
    @Test fun videoTypesKeepIndependentSettings() {
        val node = java.util.prefs.Preferences.userRoot().node("skywing-test-" + java.util.UUID.randomUUID())
        try {
            val store = MotionPreferences(node)
            assertFalse(store.lowerAutomatically)
            val keys = mutableSetOf<String>()
            for ((w,h) in listOf(1920 to 1080, 2560 to 1440, 3840 to 2160)) for (hdr in listOf(false, true)) {
                val key = MotionStops.type(SmoothingCapabilities(width=w, height=h, hdrVideo=hdr))
                keys += key
                val setting = MotionSettings(keys.size % 4, MotionQuality.entries[keys.size % 5])
                store.save(key, setting)
                assertEquals(setting, store.load(key, 0))
            }
            assertEquals(6, keys.size)
            assertEquals(MotionSettings(1), store.load("new", 1))
        } finally { node.removeNode() }
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
