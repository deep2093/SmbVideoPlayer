package com.skywing.desktop.playback

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class MotionEngineTest {
    @Test fun exactRatesRespectSourceAndActiveDisplay() {
        assertEquals(MotionRate(24000, 1001), MotionRate.source(23.976))
        assertEquals(MotionRate(30000, 1001), MotionRate.source(29.97003))
        assertEquals(MotionRate(60000, 1001), MotionRate.target(MotionRate(24000,1001), 240.0))
        assertEquals(MotionRate(125, 2), MotionRate.target(MotionRate(25), 240.0))
        assertEquals(MotionRate(60), MotionRate.target(MotionRate(24), 60.0))
        assertEquals(MotionRate(60), MotionRate.target(MotionRate(24), null))
        assertNull(MotionRate.target(MotionRate(120), 240.0))
        assertNull(MotionRate.source(Double.NaN))
        assertNull(MotionRate.source(0.5))
    }

    @Test fun otherRatesAreMeasuredToThreeDecimals() {
        // MSI Claw 8 report of 23 November: a local 1080p clip whose container reports this average rate.
        val claw = MotionRate.source(23.06080436706543)
        assertEquals(MotionRate(23061, 1000), claw)
        assertEquals(MotionRate(24123, 1000), MotionRate.source(24.123))
        assertEquals(MotionRate(1217, 50), MotionRate.source(24.34))
        assertEquals(MotionRate(25, 2), MotionRate.source(12.5))
        assertEquals(MotionRate(1), MotionRate.source(1.0))
        assertEquals(MotionRate(23061, 400), MotionRate.target(claw!!, 240.0, 1))
    }

    @Test fun onlyStandardRatesResampleToTheDisplay() {
        val film = MotionRate(24000, 1001)
        val output = MotionStops.rate(film, 1) // 59.94 fps
        assertEquals("display-resample", MotionRate.videoSync(film, output, 59.94, vrr = false))
        assertEquals("display-resample", MotionRate.videoSync(film, output, 60.0, vrr = false))
        assertEquals("audio", MotionRate.videoSync(film, output, 59.94, vrr = true))
        // Plan 5.4: a fixed screen resamples every target that is its refresh or a whole divisor of it.
        assertEquals("display-resample", MotionRate.videoSync(film, output, 120.0, vrr = false))
        assertEquals("display-resample", MotionRate.videoSync(film, output, 240.0002, vrr = false))
        assertEquals("audio", MotionRate.videoSync(film, output, 144.0, vrr = false))
        assertEquals("audio", MotionRate.videoSync(film, MotionStops.rate(film, 0), 60000 / 1001.0, vrr = false))
        assertEquals("audio", MotionRate.videoSync(film, output, null, vrr = false))
        // A measured rate may be a variable rate's average, so its frames keep their own times against the audio,
        // even where the output (60.31 fps) is within 1% of the display.
        val measured = assertNotNull(MotionRate.source(24.123))
        assertEquals("audio", MotionRate.videoSync(measured, MotionStops.rate(measured, 1), 60.0, vrr = false))
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
        assertTrue(script.contains("out = core.std.AssumeFPS(out, fpsnum=120000, fpsden=1001)"))
        assertTrue(script.contains("num:120000,den:1001"))
        assertTrue(script.contains("SkyWing\\'s"))
        assertFalse(script.contains("192.168."))
        assertTrue(script.contains("video_in.set_output()"))
        assertTrue(script.contains("scene:{mode:0}"))
        assertFalse(script.contains("follow_source_times(out"))
    }

    @Test fun scriptTimesOtherRatesFromTheSourceFrames() {
        val status = File("C:/local/status")
        val measured = MotionRate(23061, 1000)
        val script = SvpScript.create(File("C:/runtime"), status, measured, MotionRate(23061, 400))
        // SVP works at the measured rate. Output frames then take their times from the source frames' durations
        // (2.5×: five output frames for every two source frames), not from one rate the video may not have.
        assertTrue(script.contains("clip = core.std.AssumeFPS(video_in, fpsnum=23061, fpsden=1000)"))
        assertTrue(script.contains("num:23061,den:400"))
        assertTrue(script.contains("out = follow_source_times(out, 5, 2, 23061, 1000)"))
        assertFalse(script.contains("core.std.AssumeFPS(out"))
        // Only the smoothing stops (2×, 2.5×, 3× and 4×) are supported multipliers.
        assertFailsWith<IllegalArgumentException> { SvpScript.create(File("C:/runtime"), status, measured, MotionRate(60)) }
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

    @Test fun missedStatusReadDoesNotStallARecentlyActiveGraph() {
        val root = Files.createTempDirectory("motion-heartbeat-").toFile()
        try {
            var now = 1_000_000L
            val props = mapOf("hwdec" to "auto-safe", "video-sync" to "audio", "interpolation" to "no")
            val session = MotionSession(props::get, { true }, { root }, { null }, { root }, { now })
            assertNull(session.begin("video", MotionRate(25), MotionRate(50)))
            val script = root.listFiles()!!.single { it.extension == "vpy" }
            val status = File(script.path + ".status")
            status.writeText("ok\tgraph\t30\t50.0\t$now")
            assertNotNull(session.heartbeat())
            now += 400_000
            status.writeText("ok\tgraph\t20000\t50.0\t$now")
            assertNotNull(session.heartbeat())
            assertTrue(status.delete())
            now += 1_040
            assertNull(session.heartbeat())
            assertFalse(session.stalled(true))
            now += 14_000
            assertTrue(session.stalled(true))
            assertTrue(session.restartAfterStall())
            assertEquals(1, session.restarts)
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
