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

    @Test fun failureKeepsLastMetricsAndRestartIsBoundedPerFile() {
        val root = Files.createTempDirectory("motion-retained-").toFile()
        try {
            val props = mutableMapOf("hwdec" to "auto-safe", "video-sync" to "audio", "interpolation" to "no",
                "vf" to "@skywing-motion:vapoursynth", "frame-drop-count" to "39")
            val session = MotionSession(props::get, { args ->
                if (args[0] == "set") props[args[1]] = args[2]
                true
            }, { root }, { null }, { root })
            assertNull(session.begin("video", MotionRate(25), MotionRate(100)))
            fun heartbeat() {
                val script = root.listFiles()!!.single { it.extension == "vpy" }
                File(script.path + ".status").writeText("ok\ttest\t1200\t96.0\t" + System.currentTimeMillis())
                assertNotNull(session.heartbeat())
            }
            heartbeat()
            session.lastAction = "seek requested"
            assertTrue(session.restartAfterStall())
            assertEquals(1, session.restarts)
            assertFalse(session.restartAfterStall())
            heartbeat()
            session.fail("injected stall")
            assertFalse(session.active)
            assertEquals(96.0, session.lastHeartbeat?.processingFps)
            assertEquals(1200, session.lastHeartbeat?.completed)
            assertEquals("39", session.lastIncident?.counters?.get("frame-drop-count"))
            assertEquals(true, session.lastIncident?.filterPresent)
            assertEquals("seek requested", session.lastIncident?.action)
            assertNotNull(session.lastIncident?.heartbeatAgeMillis)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun diagnosticsRedactLocalAndNetworkPaths() {
        listOf("C:\\Users\\Someone\\Private file.mp4", "\\\\192.168.1.2\\share\\private file.mp4",
            "https://host/private?key=secret").forEach { path ->
            val safe = MotionLog.redact("Could not open $path")
            assertTrue(safe.contains("[path omitted]"), safe)
            assertFalse(safe.contains("Private", true), safe)
            assertFalse(safe.contains("secret"), safe)
        }
        assertFalse(MotionLog.redact("Host 192.168.1.2 disconnected").contains("192.168"))
    }
}
