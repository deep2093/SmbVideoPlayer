package com.skywing.desktop.playback

import com.skywing.desktop.automation.TestMedia
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Canvas
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Plays generated videos through SkyWing's own [EmbeddedMpv] in a real window: formats, pause/resume/seek, audio and
 * subtitle tracks, unreadable files, video switching and native memory across many loads. Runs with the pinned
 * libmpv (SKYWING_TEST_MPV=true). Opens a small window, which may take focus.
 */
class EmbeddedPlaybackNativeTest {
    @Before
    fun requireNativePlayer() {
        assumeTrue(System.getenv("SKYWING_TEST_MPV") == "true")
        assumeTrue(!GraphicsEnvironment.isHeadless())
    }

    @Test(timeout = 120_000)
    fun playsMp4AndMkvWithPauseResumeAndSeek() = withPlayer { player ->
        for ((clip, codec) in listOf(mp4 to "h264", mkv to "vp9")) {
            assertTrue(player.open(clip), "open ${clip.name}")
            // loadfile is asynchronous: until mpv switches, time-pos is still the previous clip's position.
            waitFor("${clip.name} to start playing") {
                player.diagnosticProperty("path") == clip.absolutePath && (player.playbackPosition() ?: 0.0) > 0.3
            }
            assertEquals(codec, player.diagnosticProperty("current-tracks/video/codec"), "decoder for ${clip.name}")
            assertEquals(LENGTH.toDouble(), player.playbackDuration()!!, 0.3, "duration of ${clip.name}")
            assertEquals("1", player.diagnosticProperty("current-tracks/audio/id"), "${clip.name} has its audio track selected")

            assertTrue(player.pause())
            waitFor("${clip.name} to pause") { player.isPaused() == true }
            val held = player.playbackPosition()!!
            Thread.sleep(600)
            assertEquals(held, player.playbackPosition()!!, 0.05, "position holds while paused")

            assertTrue(player.togglePause())
            waitFor("${clip.name} to resume") { player.isPaused() == false && player.playbackPosition()!! > held + 0.2 }

            assertTrue(player.seekAbsolute(7.0))
            waitFor("${clip.name} to seek to 7 s") { (player.playbackPosition() ?: 0.0) in 6.8..9.5 }
            assertTrue(player.seek(-5))
            waitFor("${clip.name} to seek back 5 s") { (player.playbackPosition() ?: 99.0) < 5.0 }
            assertTrue(player.seekPercent(50))
            waitFor("${clip.name} to seek to 50 %") { (player.playbackPosition() ?: 0.0) in 4.8..7.5 }
        }
        assertTrue(player.stop())
        waitFor("the player to become idle") { player.diagnosticProperty("idle-active") == "yes" }
    }

    @Test(timeout = 120_000)
    fun switchesBetweenTwoAudioTracksAndTwoSubtitleTracks() {
        assumeTrue("ffmpeg is needed to mux the multi-track MKV", TestMedia.ffmpeg != null)
        val clip = TestMedia.multiTrackMkv(Files.createTempDirectory(media.toPath(), "multi").toFile())
        withPlayer { player ->
            assertTrue(player.open(clip))
            waitFor("the multi-track MKV to load") { player.diagnosticProperty("track-list/count") == "5" }
            val types = (0 until 5).map { player.diagnosticProperty("track-list/$it/type") }
            assertEquals(listOf("video", "audio", "audio", "sub", "sub"), types)
            assertEquals("eng", player.diagnosticProperty("current-tracks/audio/lang"))

            assertTrue(player.nextAudioTrack())
            waitFor("the French audio track") { player.diagnosticProperty("current-tracks/audio/lang") == "fra" }
            assertTrue(player.nextAudioTrack())
            waitFor("audio to cycle away from track 2") { player.diagnosticProperty("aid") != "2" }

            val firstSub = player.diagnosticProperty("sid")
            assertTrue(player.nextSubtitleTrack())
            waitFor("another subtitle track") { player.diagnosticProperty("sid") != firstSub }
            val chosen = player.diagnosticProperty("sid")
            if (chosen != "no") assertNotNull(player.diagnosticProperty("current-tracks/sub/lang"))
            assertTrue(player.subtitleOff())
            waitFor("subtitles off") { player.diagnosticProperty("sid") == "no" }
            waitFor("playback to continue") { (player.playbackPosition() ?: 0.0) > 0.5 }
        }
    }

    @Test(timeout = 60_000)
    fun openingAnotherVideoWhilePausedPlaysIt() = withPlayer { player ->
        assertTrue(player.open(mp4))
        waitFor("the first video to play") { (player.playbackPosition() ?: 0.0) > 0.3 }
        assertTrue(player.pause())
        waitFor("the first video to pause") { player.isPaused() == true }
        assertTrue(player.open(mkv))
        waitFor("the second video to load") { player.diagnosticProperty("path") == mkv.absolutePath }
        waitFor("the second video to play, not stay paused") { player.isPaused() == false && (player.playbackPosition() ?: 0.0) > 0.3 }
    }

    @Test(timeout = 60_000)
    fun loadsTheSubtitleFileNextToTheVideo() = withPlayer { player ->
        val folder = Files.createTempDirectory(media.toPath(), "subs").toFile()
        val clip = File(folder, mp4.name).also { mp4.copyTo(it) }
        TestMedia.subtitlesFor(clip)
        assertTrue(player.open(clip))
        waitFor("the external subtitle to be selected") { player.diagnosticProperty("current-tracks/sub/external") == "yes" }
        assertEquals("srt", player.diagnosticProperty("current-tracks/sub/codec").orEmpty().let { if (it == "subrip") "srt" else it })
    }

    @Test(timeout = 60_000)
    fun missingAndCorruptFilesFailWithoutBreakingThePlayer() = withPlayer { player ->
        assertFalse(player.open(File(media, "does-not-exist.mp4")), "a missing file is rejected before mpv")
        val corrupt = File(media, "corrupt.mp4").apply { writeBytes(ByteArray(64 * 1024) { (it * 31 % 251).toByte() }) }
        assertTrue(player.open(corrupt), "mpv accepts the request and fails it asynchronously")
        waitFor("mpv to give up on the corrupt file") { player.diagnosticProperty("idle-active") == "yes" }
        assertTrue(player.open(mp4), "a good file still opens after a failure")
        waitFor("playback after a failure") { (player.playbackPosition() ?: 0.0) > 0.3 }
    }

    @Test(timeout = 60_000)
    fun aCorruptFileReportsAPlaybackFailureThatTheNextVideoClears() = withPlayer { player ->
        val corrupt = File(media, "damaged.mp4").apply { writeBytes(ByteArray(64 * 1024) { (it * 37 % 241).toByte() }) }
        assertTrue(player.open(corrupt))
        waitFor("the failure to be reported") { player.updateSmoothing(SmoothingMode.OFF); player.playbackFailure != null }
        val failure = player.playbackFailure.orEmpty()
        assertTrue("damaged.mp4" in failure, "the failure names the file: $failure")
        assertTrue(player.open(mp4))
        assertEquals(null, player.playbackFailure, "opening another video clears the failure")
        waitFor("the good video to play") { player.updateSmoothing(SmoothingMode.OFF); (player.playbackPosition() ?: 0.0) > 0.5 }
        assertEquals(null, player.playbackFailure, "a video that plays reports no failure")
    }

    @Test(timeout = 240_000)
    fun repeatedVideoSwitchingAndPlayerRestartsKeepNativeMemoryBounded() {
        val samples = mutableListOf<Long>()
        repeat(4) { round ->
            withPlayer { player ->
                repeat(10) { n ->
                    val clip = if (n % 2 == 0) mp4 else mkv
                    assertTrue(player.open(clip))
                    waitFor("switch ${round * 10 + n} to play ${clip.name}") {
                        player.diagnosticProperty("path") == clip.absolutePath && (player.playbackPosition() ?: 0.0) > 0.2
                    }
                }
            }
            System.gc()
            samples += privateBytes()
        }
        val growth = samples.last() - samples[1]
        File("build/reports/tests/test").mkdirs()
        File("build/reports/tests/test/playback-memory.txt").writeText(
            "Private bytes after each of 4 player sessions (10 file switches each): " +
                samples.joinToString { "${it / (1024 * 1024)} MB" } + "\nGrowth from session 2 to 4: ${growth / (1024 * 1024)} MB\n",
        )
        assertTrue(growth < 96L * 1024 * 1024, "native memory grew ${growth / (1024 * 1024)} MB over 20 switches and 2 restarts: $samples")
    }

    private fun withPlayer(block: (EmbeddedMpv) -> Unit) {
        lateinit var frame: Frame
        lateinit var canvas: Canvas
        SwingUtilities.invokeAndWait {
            frame = Frame("SkyWing playback test")
            canvas = Canvas()
            frame.add(canvas)
            frame.setSize(480, 300)
            frame.isVisible = true
        }
        val player = EmbeddedMpv()
        try {
            waitFor("the video surface") { canvas.isDisplayable }
            var attached = false
            SwingUtilities.invokeAndWait { attached = player.attach(canvas) }
            assertTrue(attached, "attach failed: ${player.lastError}")
            block(player)
        } finally {
            player.close()
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for $what")
    }

    @Suppress("FunctionName")
    private interface Psapi : StdCallLibrary {
        @Structure.FieldOrder(
            "cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
            "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage",
            "PeakPagefileUsage", "PrivateUsage",
        )
        class Counters : Structure() {
            @JvmField var cb = size()
            @JvmField var PageFaultCount = 0
            @JvmField var PeakWorkingSetSize = 0L
            @JvmField var WorkingSetSize = 0L
            @JvmField var QuotaPeakPagedPoolUsage = 0L
            @JvmField var QuotaPagedPoolUsage = 0L
            @JvmField var QuotaPeakNonPagedPoolUsage = 0L
            @JvmField var QuotaNonPagedPoolUsage = 0L
            @JvmField var PagefileUsage = 0L
            @JvmField var PeakPagefileUsage = 0L
            @JvmField var PrivateUsage = 0L
        }
        fun K32GetProcessMemoryInfo(process: Pointer, counters: Counters, size: Int): Boolean
        fun GetCurrentProcess(): Pointer
    }

    private fun privateBytes(): Long {
        val kernel = Native.load("kernel32", Psapi::class.java)
        val counters = Psapi.Counters()
        counters.cb = counters.size()
        check(kernel.K32GetProcessMemoryInfo(kernel.GetCurrentProcess(), counters, counters.size()))
        return counters.PrivateUsage
    }

    companion object {
        private const val LENGTH = 10
        private lateinit var media: File
        private lateinit var mp4: File
        private lateinit var mkv: File

        @BeforeClass @JvmStatic
        fun generateMedia() {
            if (System.getenv("SKYWING_TEST_MPV") != "true" || GraphicsEnvironment.isHeadless()) return
            media = Files.createTempDirectory("skywing-playback-").toFile()
            mp4 = TestMedia.encode(media, TestMedia.MP4_H264.copy(seconds = LENGTH))
            mkv = TestMedia.encode(media, TestMedia.MKV_VP9.copy(seconds = LENGTH))
        }

        @AfterClass @JvmStatic
        fun deleteMedia() {
            if (::media.isInitialized) media.deleteRecursively()
        }
    }
}
