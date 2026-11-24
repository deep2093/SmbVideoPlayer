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
            // GitHub's runners have no sound device. mpv then plays the video without sound and drops the audio track.
            if (hasSoundDevice(player)) {
                assertEquals("1", player.diagnosticProperty("current-tracks/audio/id"), "${clip.name} has its audio track selected")
            } else {
                assertTrue("audio" in entries(player, "track-list", "type"), "${clip.name} has an audio track")
                println("No sound device here, so mpv plays ${clip.name} without its audio track")
            }

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

    /** M3 item 10: a left click on mpv's own video window reaches SkyWing at once, which hides the full-screen bars. */
    @Test(timeout = 120_000)
    fun aClickOnTheVideoReachesSkyWing() = withPlayer { player ->
        val clicks = java.util.concurrent.atomic.AtomicInteger()
        player.onVideoClick = { clicks.incrementAndGet() }
        assertTrue(player.open(mp4))
        waitFor("the clip to start playing") { (player.playbackPosition() ?: 0.0) > 0.3 }
        assertTrue(player.clickVideoForTest())
        waitFor("the click to arrive", timeoutMs = 2_000) { clicks.get() == 1 }
    }

    @Test(timeout = 120_000)
    fun controlsOnTheScreenThreadDoNotWaitForABusyPlayer() = withPlayer { player ->
        assertTrue(player.open(mp4))
        waitFor("the clip to start playing") { (player.playbackPosition() ?: 0.0) > 0.3 }
        // Another call holds the player for 2 s, as starting motion smoothing can (B7).
        val busy = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            synchronized(player) {
                busy.countDown()
                Thread.sleep(2_000)
            }
        }.apply { start() }
        busy.await()
        var waitedMs = 0L
        var accepted = emptyList<Boolean>()
        SwingUtilities.invokeAndWait {
            val started = System.nanoTime()
            accepted = listOf(player.togglePause(), player.setCursorHidden(false), player.volumeDown(), player.volumeUp(),
                player.toggleMute(), player.toggleMute(), player.seek(1), player.seekAbsolute(2.0))
            waitedMs = (System.nanoTime() - started) / 1_000_000
        }
        holder.join()
        assertEquals(List(8) { true }, accepted, "mpv accepted every control")
        assertTrue(waitedMs < 300, "the controls waited $waitedMs ms for the busy player")
        waitFor("the pause to take effect") { player.isPaused() == true }
        waitFor("the seek to 2 s to take effect") { (player.playbackPosition() ?: 0.0) in 1.8..2.6 }
        assertEquals(false, player.isMuted(), "the two mute toggles ran in order")
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

    /**
     * SKYWING_GPU=NVIDIA draws on NVIDIA even where Intel drives the window's monitor, as on a Hybrid laptop's own
     * screen. Without an NVIDIA adapter (CI) the plan keeps its usual GPU.
     */
    @Test(timeout = 60_000)
    fun skywingGpuChoosesTheGpuThatDrawsTheVideo() = withPlayer(EmbeddedMpv(forcedGpu = "NVIDIA")) { player ->
        assertTrue(player.open(mp4))
        waitFor("the video to play") { (player.playbackPosition() ?: 0.0) > 0.3 }
        fun line(prefix: String) = player.updateSmoothing(SmoothingMode.OFF).report.lines().single { it.startsWith(prefix) }
        if (GpuAdapters.list().none { it.gpuVendor == GpuVendor.NVIDIA }) {
            println("No NVIDIA adapter here: ${line("GPU plan: ")}")
            return@withPlayer
        }
        assertTrue(line("GPU plan: ").contains("Forced by SKYWING_GPU"), line("GPU plan: "))
        assertTrue(line("Renderer: ").contains("preference: NVIDIA"), line("Renderer: "))
        waitFor("mpv to draw on NVIDIA: ${line("Renderer: ")}") { line("Renderer: ").startsWith("Renderer: NVIDIA") }
        println("Forced: ${line("Renderer: ")}")
    }

    /**
     * Plan 5.8 rule 3, checked with the pinned libmpv: drawn by Microsoft's software renderer (what `d3d11-warp=auto`
     * falls back to without a graphics driver), the video still plays, the report says so and smoothing is off with
     * "Install your graphics driver for smooth playback".
     */
    @Test(timeout = 60_000)
    fun withTheSoftwareRendererTheVideoPlaysAndSmoothingSaysToInstallTheDriver() = withPlayer(EmbeddedMpv(forceWarp = true)) { player ->
        assertTrue(player.open(mp4))
        waitFor("the video to play on the software renderer") { (player.playbackPosition() ?: 0.0) > 0.3 }
        fun line(prefix: String) = player.updateSmoothing(SmoothingMode.OFF).report.lines().single { it.startsWith(prefix) }
        waitFor("mpv to name its device: ${line("Renderer: ")}") { !line("Renderer: ").startsWith("Renderer: Not yet reported") }
        assertTrue(line("Renderer: ").startsWith("Renderer: Microsoft Basic Render Driver"), line("Renderer: "))
        assertTrue(line("Render path: ").contains("software (Microsoft's WARP renderer"), line("Render path: "))
        assertEquals("Install your graphics driver for smooth playback", player.updateSmoothing(SmoothingMode.MOTION).detail)
        println(line("Render path: "))
        println(line("Decode path: "))
    }

    /**
     * Plan 5.8 rule 8. Only a graphics driver reset loses the device, and that needs the owner's OK, so the test hands
     * the player the message libplacebo logs when it happens; everything after that is the player's real recovery.
     */
    @Test(timeout = 90_000)
    fun afterALostGraphicsDeviceTheVideoOutputIsRecreatedAndTheVideoGoesOn() = withPlayer { player ->
        fun line(prefix: String) = player.updateSmoothing(SmoothingMode.OFF).report.lines().single { it.startsWith(prefix) }
        fun renderer() = line("Renderer: ").takeUnless { it.startsWith("Renderer: Not yet reported") }
        fun loseDevice() = player.logMessage("vo/gpu-next/libplacebo", "error", "Device lost!\n")
        assertTrue(player.open(mp4))
        waitFor("the video to play") { (player.playbackPosition() ?: 0.0) > 0.3 && renderer() != null }
        assertEquals("Graphics device lost: never", line("Graphics device lost: "))
        for ((loss, step) in listOf(1 to "on a fresh GPU plan", 2 to "on Windows' choice of graphics")) {
            val before = player.playbackPosition()!!
            loseDevice()
            waitFor("loss $loss to re-create the output: ${line("Graphics device lost: ")}") {
                line("Graphics device lost: ").endsWith("re-created $step")
            }
            waitFor("mpv to open a GPU again after loss $loss") { renderer() != null }
            waitFor("the video to go on after loss $loss") { (player.playbackPosition() ?: 0.0) > before + 0.5 }
            assertEquals(null, player.playbackFailure)
            println(line("Graphics device lost: ") + " | " + renderer() + " | " + line("GPU plan: "))
        }
        assertTrue(line("GPU plan: ").contains("drawing and motion on Windows' choice"), line("GPU plan: "))
        // A third loss within the minute is not chased: the video pauses and the player says why.
        loseDevice()
        // The player recovers in updateSmoothing, which the app calls once a second.
        waitFor("the third loss to pause the video") {
            player.updateSmoothing(SmoothingMode.OFF)
            player.playbackFailure != null
        }
        assertTrue(line("Graphics device lost: ").startsWith("Graphics device lost: 3 times; last: not re-created"))
        assertEquals("yes", player.diagnosticProperty("pause"))
        // The next video gets a new output and plays.
        assertTrue(player.open(mp4))
        waitFor("the next video to play on a new output") { (player.playbackPosition() ?: 0.0) > 0.3 && renderer() != null }
        assertEquals(null, player.playbackFailure)
    }

    /**
     * On a PC with integrated and dedicated graphics the playing video moves to the other GPU and back (plan 5.3):
     * mpv re-creates its output inside the same Canvas, opens the GPU it was given and the video goes on.
     */
    @Test(timeout = 90_000)
    fun theVideoMovesToTheOtherGpuWhilePlaying() {
        val adapters = GpuAdapters.list()
        if (adapters.none { it.dedicated } || adapters.all { it.dedicated }) {
            println("Needs integrated and dedicated graphics; this PC has ${adapters.map { it.name }}")
            return
        }
        val store = MotionSettingsStore.values
        val saved = store.gpuChoice
        store.gpuChoice = GpuChoice.POWER_SAVING
        try {
            withPlayer { player ->
                fun line(prefix: String) = player.updateSmoothing(SmoothingMode.OFF).report.lines().single { it.startsWith(prefix) }
                fun renderer() = line("Renderer: ").takeUnless { it.startsWith("Renderer: Not yet reported") }
                assertTrue(player.open(mp4))
                waitFor("the video to play") { (player.playbackPosition() ?: 0.0) > 0.3 }
                waitFor("mpv to name its GPU") { renderer() != null }
                val integrated = renderer()!!
                println("Power saving: $integrated | ${line("GPU plan: ")}")
                assertTrue(adapters.any { !it.dedicated && integrated.startsWith("Renderer: " + it.name) }, integrated)

                val before = player.playbackPosition()!!
                assertEquals(true, player.useGpu(GpuChoice.PERFORMANCE))
                waitFor("mpv to open the graphics card") { renderer().let { it != null && it != integrated } }
                val dedicated = renderer()!!
                println("Performance: $dedicated | ${line("GPU plan: ")}")
                assertTrue(adapters.any { it.dedicated && dedicated.startsWith("Renderer: " + it.name) }, dedicated)
                waitFor("the video to go on after the move") { (player.playbackPosition() ?: 0.0) > before + 0.5 }
                assertEquals(null, player.playbackFailure)
                // Asking for the GPU it already uses changes nothing.
                assertEquals(null, player.useGpu(GpuChoice.PERFORMANCE))

                assertEquals(true, player.useGpu(GpuChoice.POWER_SAVING))
                waitFor("mpv to open the processor's graphics again") { renderer() == integrated }
                assertEquals(null, player.playbackFailure)
            }
        } finally {
            store.gpuChoice = saved
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

    private fun withPlayer(player: EmbeddedMpv = EmbeddedMpv(), block: (EmbeddedMpv) -> Unit) {
        lateinit var frame: Frame
        lateinit var canvas: Canvas
        SwingUtilities.invokeAndWait {
            frame = Frame("SkyWing playback test")
            canvas = Canvas()
            frame.add(canvas)
            frame.setSize(480, 300)
            frame.isVisible = true
        }
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

    /** True when mpv lists a Windows (WASAPI) sound device. Its list has "auto" and "openal" either way. */
    private fun hasSoundDevice(player: EmbeddedMpv): Boolean =
        entries(player, "audio-device-list", "name").any { it.startsWith("wasapi/") }

    /** The [field] of every entry in the mpv list property [list], for example each track's "type" in "track-list". */
    private fun entries(player: EmbeddedMpv, list: String, field: String): List<String> {
        val count = player.diagnosticProperty("$list/count")?.toIntOrNull() ?: 0
        return (0 until count).mapNotNull { player.diagnosticProperty("$list/$it/$field") }
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
