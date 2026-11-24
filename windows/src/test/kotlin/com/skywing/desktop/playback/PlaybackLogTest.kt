package com.skywing.desktop.playback

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackLogTest {
    private val hdrOn = HdrState(true, true, "Windows advanced colour info 2")
    private val hdrOff = HdrState(true, false, "Windows advanced colour info 2")

    @Test fun hdrIsEngagedOnlyWhenWindowsTheSourceAndMpvAllSayHdr() {
        assertEquals(ScreenReading("yes", "Windows HDR on; source pq; mpv sends pq, bt.2020, peak 1000 nits"),
            DisplayState.hdrEngaged(hdrOn, "pq", "pq", "bt.2020", 1000.0))
        assertEquals("yes", DisplayState.hdrEngaged(hdrOn, "hlg", "hlg", null, null).value)
        assertEquals(ScreenReading("no", "source is SDR (bt.1886)"), DisplayState.hdrEngaged(hdrOn, "bt.1886", "bt.1886", null, null))
        assertEquals(ScreenReading("no", "Windows HDR is off on this screen"), DisplayState.hdrEngaged(hdrOff, "pq", "bt.1886", null, null))
        assertEquals(ScreenReading("no", "mpv sends bt.1886: tone-mapped to SDR"), DisplayState.hdrEngaged(hdrOn, "pq", "bt.1886", null, null))
        // An SDR source is never HDR, whatever else is unknown.
        assertEquals("no", DisplayState.hdrEngaged(AdvancedColor.UNKNOWN, "srgb", null, null, null).value)
        assertEquals(ScreenReading("unknown", "Windows did not report HDR for this screen"),
            DisplayState.hdrEngaged(AdvancedColor.UNKNOWN, "pq", "pq", null, null))
        assertEquals(ScreenReading("unknown", "no video"), DisplayState.hdrEngaged(hdrOn, null, null, null, null))
        assertEquals(ScreenReading("unknown", "mpv did not report what it sends"), DisplayState.hdrEngaged(hdrOn, "pq", null, null, null))
        assertEquals("yes (Windows HDR on; source pq; mpv sends pq)", DisplayState.hdrEngaged(hdrOn, "pq", "pq", null, null).text)
    }

    @Test fun vrrActiveIsNvidiasReadingOrSaysWhyItIsNotMeasured() {
        val on = VrrCapability("\\\\.\\DISPLAY1", true, "NVIDIA: VRR possible", NvidiaVrr.parse(0x13))
        assertEquals("yes", DisplayState.vrrActive(on).value)
        assertTrue(DisplayState.vrrActive(on).reason!!.contains("display in VRR mode true"))
        val off = on.copy(nvidia = NvidiaVrr.parse(0x02))
        assertEquals("no", DisplayState.vrrActive(off).value)
        assertEquals(ScreenReading("not measured", "only NVIDIA reports it; NVIDIA status -6"),
            DisplayState.vrrActive(VrrCapability("\\\\.\\DISPLAY1", null, "NVIDIA status -6")))
    }

    private fun sample(at: Long, frameDrops: Long = 0, cpuMs: Long = 0, fps: Double? = 48.0, paused: Boolean = false,
                       cache: Boolean = false, fullscreen: Boolean = true, seeks: Int = 0, restarts: Int = 0,
                       generations: Int = 1, age: Long? = 300, jitter: Double? = 0.01, focused: Boolean? = true) = PlaybackSample(
        atMillis = at, target = "48", outputFps = fps, counters = listOf(frameDrops, 0L, 2L, null),
        vsyncJitter = jitter, displayFps = 59.94, cpuNanos = cpuMs * 1_000_000, heartbeatAgeMillis = age,
        generations = generations, seeks = seeks, restarts = restarts, paused = paused, cacheWait = cache,
        fullscreen = fullscreen, hdr = ScreenReading("no", "source is SDR (bt.1886)"), vrr = ScreenReading("not measured", "x"), refreshHz = 59.94,
        focused = focused)

    @Test fun samplesBecomeOneRowPerMinuteWithDeltasAndEvents() {
        val rows = mutableListOf<PlaybackMinute>()
        val log = PlaybackMinutes(rows::add)
        // 2.5 minutes, a sample every 500 ms. 1.5 cores busy throughout; 3 drops in the first minute, none after.
        for (i in 0..300) {
            val at = 1_000_000L + i * 500
            val elapsed = i * 500L
            log.add(sample(at, frameDrops = if (elapsed >= 20_000) 3 else 0, cpuMs = elapsed * 3 / 2,
                fps = if (i == 10) 30.0 else 48.0,
                paused = elapsed in 70_000..74_999, cache = elapsed in 80_000..80_999,
                fullscreen = elapsed !in 90_000..95_000, seeks = if (elapsed >= 100_000) 2 else 0,
                restarts = if (elapsed >= 110_000) 1 else 0, generations = if (elapsed >= 100_000) 3 else 1,
                age = if (elapsed == 30_000L) 9_000 else 300, focused = elapsed < 100_000))
        }
        assertEquals(2, rows.size)
        val first = rows[0]
        assertEquals(1, first.minute)
        assertEquals(60.0, first.seconds)
        assertEquals(3L, first.counters[0])
        assertEquals(0L, first.counters[1])
        assertEquals(0L, first.counters[2])
        assertNull(first.counters[3], "unavailable stays unavailable")
        assertEquals(1.5, first.cpuCores!!, 0.001)
        assertEquals(30.0, first.outputFpsMin)
        assertEquals(9_000L, first.heartbeatAgeMaxMillis)
        assertEquals(0, first.generations)
        assertEquals("yes", first.focused, "in focus for the whole minute")
        val second = rows[1]
        assertEquals(2, second.minute)
        assertEquals(0L, second.counters[0], "minute 2 starts where minute 1 ended")
        assertEquals(48.0, second.outputFpsMean!!, 0.001)
        assertEquals(1, second.pauses)
        assertEquals(1, second.cacheWaits)
        assertEquals(2, second.fullscreenChanges)
        assertEquals(2, second.seeks)
        assertEquals(1, second.recoveries)
        assertEquals(2, second.generations)
        assertEquals("mixed", second.focused, "another window took the focus during the minute")
        log.finish()
        assertEquals(3, rows.size, "the last 30 s are a row of their own")
        assertEquals(30.0, rows[2].seconds)
        assertEquals("no", rows[2].focused)
        log.add(sample(5_000_000))
        log.add(sample(5_003_000))
        log.finish()
        assertEquals(3, rows.size, "under 5 s is not a row")
    }

    @Test fun csvRowsUseFixedColumnsAndLeaveUnavailableValuesEmpty() {
        val rows = mutableListOf<PlaybackMinute>()
        val log = PlaybackMinutes(rows::add)
        for (i in 0..120) log.add(sample(i * 500L, cpuMs = i * 500L, jitter = null))
        val row = rows.single()
        assertEquals(PlaybackMinute.HEADER.split(',').size, row.csv().split(',').size)
        assertEquals("minute,seconds,target_fps,output_fps_mean,output_fps_min,frame_drops,decoder_drops,vo_delayed," +
            "mistimed,vsync_jitter_max,estimated_display_fps,cpu_cores,heartbeat_age_max_ms,graph_generations,seeks," +
            "pauses,cache_waits,fullscreen_changes,recoveries,hdr_engaged,vrr_active,refresh_hz,focused", PlaybackMinute.HEADER)
        assertEquals("1,60.0,48,48.00,48.00,0,0,0,,,59.940,1.00,300,0,0,0,0,0,0,no,not measured,59.94,yes", row.csv())
        val unknown = mutableListOf<PlaybackMinute>()
        PlaybackMinutes(unknown::add).let { minutes -> for (i in 0..120) minutes.add(sample(i * 500L, focused = null)) }
        assertNull(unknown.single().focused, "Windows did not say which window has the focus")
        assertTrue(unknown.single().csv().endsWith(",59.94,"), unknown.single().csv())
    }

    @Test fun logFilesAreNewPerSessionAndOnlyTheNewestAreKept() {
        val folder = Files.createTempDirectory("playback-logs-").toFile()
        try {
            File(folder, "notes.txt").writeText("not ours")
            repeat(25) { File(folder, "playback-20261101-0000%02d.csv".format(it)).writeText("old") }
            val log = PlaybackCsv.create(folder, java.time.LocalDateTime.of(2026, 11, 24, 5, 30, 1))!!
            assertEquals("playback-20261124-053001.csv", log.file.name)
            assertEquals(PlaybackMinute.HEADER + "\n", log.file.readText())
            val kept = folder.listFiles()!!.map { it.name }.filter { it.startsWith("playback-") }.sorted()
            assertEquals(PlaybackCsv.KEEP, kept.size)
            assertEquals("playback-20261124-053001.csv", kept.last())
            assertFalse("playback-20261101-000000.csv" in kept, "the oldest go first")
            assertTrue(File(folder, "notes.txt").isFile, "other files are left alone")
            val rows = mutableListOf<PlaybackMinute>()
            val minutes = PlaybackMinutes(rows::add)
            for (i in 0..120) minutes.add(sample(i * 500L))
            log.append(rows.single())
            assertEquals(2, log.file.readLines().size)
        } finally { folder.deleteRecursively() }
    }

    @Test fun theTestReportTableShowsEachMinute() {
        val rows = mutableListOf<PlaybackMinute>()
        val log = PlaybackMinutes(rows::add)
        for (i in 0..240) log.add(sample(i * 500L, cpuMs = i * 500L))
        val table = PlaybackMinute.table(rows)
        assertEquals("Minute  Seconds  Output fps (mean/min)  Drops (video/decoder/delayed/mistimed)  CPU cores  Heartbeat max  HDR  VRR  Focus", table.first())
        assertEquals(3, table.size)
        assertEquals("1       60       48.0/48.0              0/0/0/–                                 1.00       300 ms         no   not measured  yes", table[1])
        assertEquals(listOf("No full minute of smoothing yet"), PlaybackMinute.table(emptyList()))
    }
}
