package com.skywing.desktop.playback

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class MotionEngineTest {
    @Test fun exactRatesRespectSourceAndActiveDisplay() {
        assertEquals(MotionRate(24000, 1001), MotionRate.source(23.976))
        assertEquals(MotionRate(30000, 1001), MotionRate.source(29.97003))
        assertEquals(MotionRate(120000, 1001), MotionRate.target(MotionRate(24000,1001), 240.0))
        assertEquals(MotionRate(100), MotionRate.target(MotionRate(25), 240.0))
        assertEquals(MotionRate(48), MotionRate.target(MotionRate(24), 60.0))
        assertEquals(MotionRate(120), MotionRate.target(MotionRate(24), null))
        assertNull(MotionRate.target(MotionRate(120), 240.0))
        assertNull(MotionRate.source(Double.NaN))
        assertNull(MotionRate.source(24.123))
    }

    @Test fun restoresOriginalPropertiesAndPreservesOtherFiltersAfterPartialFailure() {
        val root = Files.createTempDirectory("motion-test-").toFile()
        try {
            val props = mutableMapOf("hwdec" to "nvdec", "video-sync" to "display-resample", "interpolation" to "yes", "vf" to "hflip")
            val calls = mutableListOf<List<String>>()
            val session = MotionSession(props::get, { args ->
                calls.add(args)
                if (args[0] == "set") props[args[1]] = args[2]
                !(args[0] == "vf" && args[1] == "add")
            }, { root }, { null }, { root })
            assertNotNull(session.begin("local-video", MotionRate(24), MotionRate(120)))
            assertFalse(session.active)
            assertEquals("nvdec", props["hwdec"])
            assertEquals("display-resample", props["video-sync"])
            assertEquals("yes", props["interpolation"])
            assertEquals("hflip", props["vf"])
            assertTrue(calls.contains(listOf("vf", "remove", "@skywing-motion")))
            val count = calls.size
            assertNotNull(session.begin("local-video", MotionRate(24), MotionRate(120)))
            assertEquals(count, calls.size, "A failed file must not repeatedly rebuild")
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun missingRuntimeDoesNotChangePlayerAndFailureIsPerFile() {
        var discoveries = 0
        val session = MotionSession({ error("must not read settings") }, { error("must not change settings") },
            { discoveries++; null })
        assertNotNull(session.begin("first", MotionRate(24), MotionRate(120)))
        assertNotNull(session.begin("first", MotionRate(24), MotionRate(120)))
        assertEquals(1, discoveries)
        assertNotNull(session.begin("second", MotionRate(25), MotionRate(100)))
        assertEquals(2, discoveries)
    }

    @Test fun scriptUsesExactOutputDurationsAndEscapedLocalPaths() {
        val script = SvpScript.create(File("C:/SkyWing's runtime/é"), File("C:/local/status"),
            MotionRate(24000,1001), MotionRate(120000,1001))
        assertTrue(script.contains("fpsnum=120000, fpsden=1001"))
        assertTrue(script.contains("num:120000,den:1001"))
        assertTrue(script.contains("SkyWing\\'s"))
        assertFalse(script.contains("192.168."))
        assertTrue(script.contains("video_in.set_output()"))
        assertTrue(script.contains("scene:{mode:0}"))
    }
}
