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
    @Test fun anyReportedRateCanBeSmoothed() {
        // MSI Claw 8 report of 23 November: a local 1080p H.264 clip whose container reports this average rate.
        val clip = SmoothingCapabilities(displayHz = 120.0, sourceFps = 23.06080436706543, gpuOutput = true,
            hardwareDecode = true, width = 1920, height = 1080)
        assertNull(clip.unavailable(SmoothingMode.MOTION))
        assertNull(clip.unavailable(SmoothingMode.OFF))
        // Standard rates as mpv reports them (container-fps is a 32-bit float), then rates that are not standard:
        // near 23.976, a file whose last frame has no duration, half of PAL, and the slowest rate SkyWing smooths.
        listOf(23.976024627685547, 24.0, 25.0, 30000 / 1001.0, 30.0, 50.0, 60000 / 1001.0, 60.0,
            23.982, 24.34, 12.5, 1.0).forEach {
            assertNull(clip.copy(sourceFps = it).unavailable(SmoothingMode.MOTION), "$it fps")
        }
        // Slower than a frame a second, such as a slideshow, is named rather than waited on.
        assertEquals("Can’t smooth video below 1 fps", clip.copy(sourceFps = 0.5).unavailable(SmoothingMode.MOTION))
        // Until mpv reports a rate, SkyWing keeps waiting for one.
        assertNull(clip.copy(sourceFps = null).unavailable(SmoothingMode.MOTION))
        assertNull(clip.copy(sourceFps = 0.0).unavailable(SmoothingMode.MOTION))
        // The other reasons keep their precedence.
        assertEquals("Already smooth", clip.copy(sourceFps = 75.3).unavailable(SmoothingMode.MOTION))
        assertEquals("HDR smoothing is not available yet", clip.copy(hdrVideo = true).unavailable(SmoothingMode.MOTION))
        assertEquals("Play a hardware-decoded video first", clip.copy(hardwareDecode = false).unavailable(SmoothingMode.MOTION))
    }
    @Test fun interlacedVideoIsNotSmoothedAndSaysWhy() {
        // Plan 5.8 rule 9: fields are never smoothed as if they were frames; mpv deinterlaces the video instead.
        val tv = SmoothingCapabilities(displayHz = 60.0, sourceFps = 25.0, gpuOutput = true, hardwareDecode = true,
            interlaced = true, width = 1920, height = 1080)
        assertEquals("Interlaced video isn’t smoothed; it is deinterlaced instead", tv.unavailable(SmoothingMode.MOTION))
        assertNull(tv.unavailable(SmoothingMode.OFF))
        assertNull(tv.copy(interlaced = false).unavailable(SmoothingMode.MOTION))
        // HDR keeps its own reason.
        assertEquals("HDR smoothing is not available yet", tv.copy(hdrVideo = true).unavailable(SmoothingMode.MOTION))
    }
    @Test fun withoutAnOpenClDeviceOnly1080pAt2xIsSmoothedOnTheProcessor() {
        // Plan 5.8 rule 4: no OpenCL device at all, so open-svpflow runs on the processor, up to 1080p at 2×.
        val clip = SmoothingCapabilities(displayHz = 120.0, sourceFps = 24.0, gpuOutput = true, hardwareDecode = true,
            interlaced = false, width = 1920, height = 1080, processor = true)
        assertNull(clip.unavailable(SmoothingMode.MOTION))
        assertNull(clip.copy(width = 1080, height = 1920).unavailable(SmoothingMode.MOTION))
        val tooBig = "Update your graphics driver: without OpenCL, smoothing works only up to 1080p at 2×"
        assertEquals(tooBig, clip.copy(width = 2560, height = 1440).unavailable(SmoothingMode.MOTION))
        assertEquals(tooBig, clip.copy(width = 3840, height = 2160).unavailable(SmoothingMode.MOTION))
        assertNull(clip.copy(width = 3840, height = 2160, processor = false).unavailable(SmoothingMode.MOTION))
        // Only 2× is offered on the processor; with an OpenCL device every stop the screen fits.
        assertEquals(listOf(0), MotionStops.allowed(MotionRate(24), 120.0, processor = true))
        assertEquals(listOf(0, 1, 2, 3), MotionStops.allowed(MotionRate(24), 120.0))
    }
    @Test fun softwareRenderingAndASoftwareDecodeFallbackSayWhy() {
        val clip = SmoothingCapabilities(displayHz = 60.0, sourceFps = 24.0, gpuOutput = true, hardwareDecode = true,
            interlaced = false, width = 1920, height = 1080)
        // Plan 5.8 rule 3: drawn by Microsoft's software renderer (no graphics driver, or remote desktop).
        assertEquals("Install your graphics driver for smooth playback",
            clip.copy(softwareRender = true, hardwareDecode = false).unavailable(SmoothingMode.MOTION))
        // Plan 5.8 rule 2: mpv stepped down to software decoding during this file.
        assertEquals("Decoding fell back to software, so this video isn’t smoothed",
            clip.copy(hardwareDecode = false, decoderFailures = listOf("d3d11va-copy")).unavailable(SmoothingMode.MOTION))
        // A decoder that failed but left a working hardware one is smoothed.
        assertNull(clip.copy(decoderFailures = listOf("d3d11va")).unavailable(SmoothingMode.MOTION))
        assertEquals("Play a hardware-decoded video first", clip.copy(hardwareDecode = false).unavailable(SmoothingMode.MOTION))
    }
    @Test fun mpvsDecoderStepDownIsRead() {
        // vd_lavc.c logs this at warn once a hardware decoder was in use, before trying the next method.
        assertEquals("d3d11va-copy", DecoderFallback.failedMethod("vd", "Attempting next decoding method after failure of d3d11va-copy.\n"))
        assertEquals("nvdec", DecoderFallback.failedMethod("vd", "Attempting next decoding method after failure of nvdec."))
        assertNull(DecoderFallback.failedMethod("vd", "Using software decoding.\n"))
        assertNull(DecoderFallback.failedMethod("cplayer", "Attempting next decoding method after failure of d3d11va.\n"))
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

    /** Owner decision, 23 November: Fast is the default quality on every PC; a quality chosen for a video type is kept. */
    @Test fun fastIsTheQualityUntilAVideoTypeSavesOne() {
        val node = java.util.prefs.Preferences.userRoot().node("skywing-test-" + java.util.UUID.randomUUID())
        try {
            val store = MotionPreferences(node)
            assertEquals(MotionQuality.FAST, MotionSettings(0).quality)
            assertEquals(MotionQuality.FAST, store.load("1080-sdr", 0).quality)
            store.save("1080-sdr", MotionSettings(0, MotionQuality.BALANCED))
            assertEquals(MotionQuality.BALANCED, store.load("1080-sdr", 0).quality)
            assertEquals(MotionQuality.FAST, store.load("2160-sdr", 0).quality)
            node.put("motion.1440-sdr.quality", "NOT-A-PRESET")
            assertEquals(MotionQuality.FAST, store.load("1440-sdr", 0).quality)
        } finally { node.removeNode() }
    }

    @Test fun sustainedRecentDropsTripAndRecover() {
        val health = SmoothingHealth()
        assertFalse(health.sample(0, 0, true))
        assertFalse(health.sample(7000, 30, true)) // warmup is not a strike
        assertFalse(health.sample(8000, 33, true))
        assertFalse(health.sample(9000, 36, true))
        assertTrue(health.sample(10000, 39, true))
        assertTrue(health.sample(11000, 39, true))
        assertTrue(health.sample(12000, 39, true))
        assertFalse(health.sample(13000, 39, true))
        health.reset()
        assertFalse(health.sample(14000, 0, true))
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
    @Test fun rollingDropsExpireAndSurviveIndependentCounterResets() {
        val recent = RecentDrops()
        assertEquals(0L, recent.sample(0, listOf(100L, 20L)))
        assertEquals(5L, recent.sample(1000, listOf(103L, 22L)))
        assertEquals(7L, recent.sample(2000, listOf(1L, 23L)))
        assertEquals(2L, recent.sample(61000, listOf(1L, 23L)))
        assertEquals(0L, recent.sample(62000, listOf(1L, 23L)))
        assertNull(recent.sample(63000, listOf(null, 23L)))
        assertNull(recent.sample(64000, listOf(1L, 23L)))
        assertEquals(0L, recent.sample(124000, listOf(1L, 23L)))
        recent.reset()
        assertEquals(0L, recent.sample(125000, listOf(500L, 50L)))
    }
    @Test fun oldDropTotalsAndSlowTelemetryAloneDoNotTrip() {
        val health = SmoothingHealth()
        for (second in 0..20) assertFalse(health.sample(second * 1000L, 10000, true, 55.0, 100.0))
    }
    @Test fun letVrrControlRefreshIsOffUntilChosenAndKeepsTheOldSwitchsChoice() {
        // Owner decision (plan 5.4): off on every screen, with no default from the capability queries.
        val node = java.util.prefs.Preferences.userRoot().node("skywing-vrr-test-" + java.util.UUID.randomUUID())
        try {
            val prefs = MotionPreferences(node)
            assertFalse(prefs.vrr)
            assertNull(prefs.vrrOverride)
            // A choice saved with the old "My screen uses G-SYNC / FreeSync" switch is kept.
            node.put("motion.vrr", "true")
            assertTrue(MotionPreferences(node).vrr)
            prefs.vrr = false
            assertFalse(MotionPreferences(node).vrr)
            assertEquals(false, prefs.vrrOverride)
        } finally { node.removeNode() }
    }
    @Test fun anUnknownRefreshNeverAllowsStopsAboveSixtyHz() {
        // B30: Windows' current mode is the refresh; without one SkyWing assumes 60 Hz, never 240.
        val film = MotionRate(24000, 1001)
        assertEquals(listOf(0, 1), MotionStops.allowed(film, null))
        assertEquals(listOf(0, 1), MotionStops.allowed(film, Double.NaN))
        assertNull(MotionRate.target(MotionRate(30), null, 1))
        assertEquals(MotionRate(60), MotionRate.target(MotionRate(30), null, 0))
    }
    @Test fun aStopFitsWhenTheScreenShowsItEvenly() {
        assertTrue(MotionStops.fits(60000 / 1001.0, 120.0))
        assertTrue(MotionStops.fits(48000 / 1001.0, 240.0002))
        assertTrue(MotionStops.fits(60.0, 60000 / 1001.0))
        assertFalse(MotionStops.fits(60000 / 1001.0, 144.0))
        assertFalse(MotionStops.fits(74.93, 120.0))
        assertFalse(MotionStops.fits(130.0, 120.0))
    }
    @Test fun firstUsePrefersAStopTheFixedScreenShowsEvenly() {
        // B31: the default stays closest to 60 fps, but a stop that fits the screen goes first.
        val film = MotionRate(24)
        assertEquals(1, MotionStops.default(film, 60.0))
        assertEquals(1, MotionStops.default(film, 120.0))
        assertEquals(0, MotionStops.default(film, 144.0))
        assertEquals(1, MotionStops.default(film, 144.0, vrr = true))
        // Nothing fits: closest to 60 fps as before (the display limit then lowers it).
        assertEquals(1, MotionStops.default(MotionRate(25), 60.0))
        listOf(30, 50, 60).forEach { assertEquals(0, MotionStops.default(MotionRate(it), 144.0)) }
    }
    @Test fun aStopThatDoesNotFitAFixedScreenSaysItMayJudder() {
        val film = MotionRate(24000, 1001)
        assertEquals("May judder on this 144 Hz screen", MotionStops.note(film, 1, 144.0, vrr = false))
        assertNull(MotionStops.note(film, 1, 120.0, vrr = false))
        assertNull(MotionStops.note(film, 1, 144.0, vrr = true))
        // Above the refresh the stop is turned off instead.
        assertNull(MotionStops.note(film, 3, 60.0, vrr = false))
        // No refresh, no claim about it.
        assertNull(MotionStops.note(film, 1, null, vrr = false))
    }
}
