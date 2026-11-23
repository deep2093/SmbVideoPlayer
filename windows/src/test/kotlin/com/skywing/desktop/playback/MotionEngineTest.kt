package com.skywing.desktop.playback

import com.sun.jna.Pointer
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
        // Plan 5.8 rule 10: a standard rate whose frames are off its period is a variable rate's average.
        assertEquals("audio", MotionRate.videoSync(film, output, 59.94, vrr = false, variable = true))
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
        val script = SvpScript.create(File("C:/SkyWing's runtime/é"), 0x7ff612340000,
            MotionRate(24000,1001), MotionRate(120000,1001))
        // Each output frame of a standard rate is its exact period (here 1001/120000 s) when its source frames are within
        // mpv's rounding tolerance of theirs; a frame further off keeps its own time (plan 5.8 rule 10).
        assertTrue(script.contains("out = follow_source_times(out, 5, 1, 24000, 1001, True)"), script)
        assertTrue(script.contains("tolerance = Fraction(31, 10000)"), script)
        assertTrue(script.contains("num:120000,den:1001"))
        assertTrue(script.contains("SkyWing\\'s"))
        assertFalse(script.contains("192.168."))
        assertTrue(script.contains("video_in.set_output()"))
        assertTrue(script.contains("scene:{mode:0}"))
        assertFalse(script.contains("core.std.AssumeFPS(out"))
    }

    @Test fun scriptTimesOtherRatesFromTheSourceFrames() {
        val status = 0x7ff612340000
        val measured = MotionRate(23061, 1000)
        val script = SvpScript.create(File("C:/runtime"), status, measured, MotionRate(23061, 400))
        // SVP works at the measured rate. Output frames then take their times from the source frames' durations
        // (2.5×: five output frames for every two source frames), not from one rate the video may not have.
        assertTrue(script.contains("clip = core.std.AssumeFPS(video_in, fpsnum=23061, fpsden=1000)"))
        assertTrue(script.contains("num:23061,den:400"))
        assertTrue(script.contains("out = follow_source_times(out, 5, 2, 23061, 1000, False)"))
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
                beat(session, 1, 1200, 96.0, System.currentTimeMillis())
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
            session.release()
        } finally { root.deleteRecursively() }
    }

    @Test fun missedHeartbeatReadDoesNotStallARecentlyActiveGraph() {
        val root = Files.createTempDirectory("motion-heartbeat-").toFile()
        var now = 1_000_000L
        val props = mapOf("hwdec" to "auto-safe", "video-sync" to "audio", "interpolation" to "no")
        val session = MotionSession(props::get, { true }, { root }, { null }, { root }, { now })
        try {
            assertNull(session.begin("video", MotionRate(25), MotionRate(50)))
            beat(session, 7, 30, 50.0, now)
            assertNotNull(session.heartbeat())
            now += 400_000
            beat(session, 7, 20000, 50.0, now)
            assertNotNull(session.heartbeat())
            // Caught while the script writes: the read is missed, and the last beat is kept.
            beat(session, 7, 20012, 50.0, now + 500, finish = false)
            now += 1_040
            assertNull(session.heartbeat())
            assertEquals(20000, session.lastHeartbeat?.completed)
            assertFalse(session.stalled(true))
            now += 14_000
            assertTrue(session.stalled(true))
            assertTrue(session.restartAfterStall())
            assertEquals(1, session.restarts)
        } finally { session.release(); root.deleteRecursively() }
    }

    @Test fun theHeartbeatIsMemoryTheScriptWritesNotAFile() {
        val root = Files.createTempDirectory("motion-heartbeat-").toFile()
        val props = mapOf("hwdec" to "auto-safe", "video-sync" to "audio", "interpolation" to "no")
        val session = MotionSession(props::get, { true }, { root }, { null }, { root })
        try {
            assertNull(session.begin("video", MotionRate(25), MotionRate(50)))
            val address = assertNotNull(session.heartbeatAddress)
            val script = root.listFiles()!!.single { it.extension == "vpy" }.readText()
            assertTrue(script.contains("Heartbeat.from_address($address)"), script)
            assertFalse(script.contains("open("), "the script writes no status file")
            assertNull(session.heartbeat(), "nothing written yet")
            beat(session, 42, 120, 47.5, 1_700_000_000_000)
            assertEquals(MotionHeartbeat("42", 120, 47.5, 1_700_000_000_000), session.heartbeat())
            beat(session, 42, 144, 47.5, 1_700_000_000_500, irregular = 5)
            assertEquals(MotionHeartbeat("42", 144, 47.5, 1_700_000_000_500, irregularFrames = 5), session.heartbeat())
            assertTrue(root.listFiles()!!.none { it.name.contains(".status") })
        } finally { session.release(); root.deleteRecursively() }
    }

    @Test fun aScriptThatCannotStartSaysSoThroughTheHeartbeat() {
        val root = Files.createTempDirectory("motion-heartbeat-").toFile()
        val props = mapOf("hwdec" to "auto-safe", "video-sync" to "audio", "interpolation" to "no")
        val session = MotionSession(props::get, { true }, { root }, { null }, { root })
        try {
            assertNull(session.begin("video", MotionRate(25), MotionRate(50)))
            beat(session, 3, 0, 0.0, 1_700_000_000_000, state = HeartbeatMemory.FAILED)
            assertNull(session.heartbeat())
            assertEquals("The motion engine could not process this video", session.failure)
            assertFalse(session.active)
        } finally { session.release(); root.deleteRecursively() }
    }

    /**
     * B28: 30 minutes of smoothing on a simulated clock. The script writes a beat every 0.5 s; every 7th read
     * catches it mid-write, and every 5 minutes reads miss for 10 s in a row. None of that is a stall. Then the graph
     * really stops: after 15 s it restarts once, and a second stall of the same file is not restarted again.
     */
    @Test fun thirtyMinutesOfMissedReadsNeverRestartButARealStallRecoversOnce() {
        val root = Files.createTempDirectory("motion-heartbeat-").toFile()
        var now = 1_700_000_000_000L
        val props = mapOf("hwdec" to "auto-safe", "video-sync" to "audio", "interpolation" to "no")
        val session = MotionSession(props::get, { true }, { root }, { null }, { root }, { now })
        try {
            assertNull(session.begin("video", MotionRate(24), MotionRate(48)))
            var completed = 0L
            for (tick in 1..3600) {
                now += 500
                completed += 24
                val blackout = tick % 600 in 1..20
                beat(session, 1, completed, 48.0, now, finish = blackout || tick % 7 != 0)
                if (blackout) beat(session, 1, completed, 48.0, now, finish = false)
                val read = session.heartbeat()
                if (blackout || tick % 7 == 0) assertNull(read, "tick $tick should be a missed read")
                assertFalse(session.stalled(true), "false stall at ${tick / 2} s")
            }
            assertEquals(0, session.restarts)
            assertEquals(1, session.rebuilds)
            now += 15_001
            assertTrue(session.stalled(true))
            assertTrue(session.restartAfterStall())
            assertEquals(1, session.restarts)
            beat(session, 2, 24, 48.0, now)
            assertNotNull(session.heartbeat())
            now += 15_001
            assertTrue(session.stalled(true))
            assertFalse(session.restartAfterStall(), "one restart per file")
        } finally { session.release(); root.deleteRecursively() }
    }

    /**
     * Plan 5.8 rule 10: a phone video can average a standard rate (30 fps) while its frames are not evenly spaced. Once
     * the script finds frames off the period, the file is smoothed as a variable rate: its frames keep their own times
     * and play against the audio. One or two odd frames, such as a frame lost in a recording, do not change that.
     */
    @Test fun aStandardRateWithFramesOffItsPeriodIsSmoothedAsAVariableRate() {
        val root = Files.createTempDirectory("motion-variable-").toFile()
        val props = mapOf("hwdec" to "auto-safe", "video-sync" to "audio", "interpolation" to "no")
        val session = MotionSession(props::get, { true }, { root }, { null }, { root })
        try {
            assertNull(session.begin("phone", MotionRate(30), MotionRate(60)))
            assertFalse(session.variableRate)
            beat(session, 1, 30, 60.0, 1_700_000_000_000, irregular = MotionSession.VARIABLE_FRAMES - 1L)
            assertNotNull(session.heartbeat())
            assertFalse(session.variableRate, "a frame or two off the period is not a variable rate")
            beat(session, 1, 60, 60.0, 1_700_000_000_500, irregular = MotionSession.VARIABLE_FRAMES.toLong())
            assertNotNull(session.heartbeat())
            assertTrue(session.variableRate)
            // A seek rebuilds the script, which counts again from none; the file stays a variable rate.
            beat(session, 2, 10, 60.0, 1_700_000_001_000)
            assertNotNull(session.heartbeat())
            assertTrue(session.variableRate)
            assertTrue(session.end())
            assertNull(session.begin("film", MotionRate(24), MotionRate(48)))
            assertFalse(session.variableRate)
            assertTrue(session.end())
            assertNull(session.begin("phone", MotionRate(30), MotionRate(60)))
            assertTrue(session.variableRate, "known for the file before the script counts again")
        } finally { session.release(); root.deleteRecursively() }
    }

    /** Writes one beat as the script does: the sequence is odd while writing, and [finish] makes it even again. */
    private fun beat(session: MotionSession, generation: Long, completed: Long, fps: Double, updated: Long,
                     state: Long = HeartbeatMemory.RUNNING, finish: Boolean = true, irregular: Long = 0) {
        val memory = Pointer(assertNotNull(session.heartbeatAddress))
        val writing = memory.getLong(HeartbeatMemory.SEQUENCE).let { if (it % 2 == 1L) it else it + 1 }
        memory.setLong(HeartbeatMemory.SEQUENCE, writing)
        memory.setLong(HeartbeatMemory.STATE, state)
        memory.setLong(HeartbeatMemory.GENERATION, generation)
        memory.setLong(HeartbeatMemory.COMPLETED, completed)
        memory.setDouble(HeartbeatMemory.FPS, fps)
        memory.setLong(HeartbeatMemory.UPDATED, updated)
        memory.setLong(HeartbeatMemory.IRREGULAR, irregular)
        if (finish) memory.setLong(HeartbeatMemory.SEQUENCE, writing + 1)
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
