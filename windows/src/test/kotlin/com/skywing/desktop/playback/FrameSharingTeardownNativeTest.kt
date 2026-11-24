package com.skywing.desktop.playback

import com.skywing.desktop.BorderlessFullscreen
import com.skywing.desktop.automation.TestMedia
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.awt.Canvas
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Frame sharing's teardown stress (plan 5.5). With smoothing on, every seek frees the VapourSynth script while the
 * video output may still show frames that script made. Each of 10 rounds opens a player, does 100 × (seek, full screen
 * on, seek, full screen off) and closes it. Nothing may hang or crash, smoothing must still run at the end of every
 * round, and closing must finish within 30 s. Runs with the pinned libmpv (SKYWING_TEST_MPV=true), takes about 20
 * minutes and the desktop: the window goes full screen 1,000 times. The report names the libmpv runtime; only the
 * frame-sharing runtime exercises shared frames.
 */
class FrameSharingTeardownNativeTest {
    @Before
    fun requireNativePlayer() {
        assumeTrue(System.getenv("SKYWING_TEST_MPV") == "true")
        assumeTrue(!GraphicsEnvironment.isHeadless())
    }

    @Test(timeout = 3_600_000)
    fun smoothedVideoSurvivesSeeksFullScreenTogglesAndCloses() {
        val resources = File(System.getProperty("compose.application.resources.dir"))
        assertNotNull(MotionRuntime.directory(), "the native test folder must hold the motion payload")
        val runtime = if (File(resources, "MPV-PREVIEW-MANIFEST.json").isFile) "frame-sharing" else "stock (frame sharing not exercised)"
        val report = File("build/reports/tests/test/frame-sharing-teardown.txt").absoluteFile.apply { parentFile.mkdirs() }
        val lines = mutableListOf("libmpv runtime: $runtime", "Sharing switch SKYWING_VS_SHARE_FRAMES: ${System.getenv("SKYWING_VS_SHARE_FRAMES") ?: "unset (on)"}")
        val folder = Files.createTempDirectory("skywing-teardown-").toFile()
        try {
            // A keyframe every half second, so each seek lands close to where it was asked to.
            val clip = TestMedia.encode(folder, TestMedia.MP4_H264.copy(name = "teardown.mp4", seconds = LENGTH, ovcopts = "g=12"))
            // Plan 5.8 rule 3: drawn by Microsoft's software renderer (no graphics driver, as on a hosted CI runner),
            // smoothing stays off, so there is nothing to tear down. The player decides; the test only asks it.
            var software = false
            withPlayer { player, _ ->
                assertTrue(player.open(clip))
                waitFor("the video to play") { (player.playbackPosition() ?: 0.0) > 0.3 }
                software = player.updateSmoothing(SmoothingMode.MOTION).capabilities.softwareRender
            }
            if (software) lines += "Not run: drawn by Microsoft's software renderer, so smoothing is off (plan 5.8 rule 3)"
            assumeFalse("Smoothing is off on Microsoft's software renderer (no graphics driver), so this PC can't run it", software)
            repeat(ROUNDS) { round ->
                val started = System.nanoTime()
                val closeMs = withPlayer { player, fullscreen ->
                    assertTrue(player.open(clip))
                    val smoothing = Smoothing(player)
                    smoothing.waitUntilHolding("round ${round + 1} to start smoothing")
                    repeat(CYCLES) { n ->
                        val step = round * CYCLES + n
                        val where = "round ${round + 1}, cycle ${n + 1}"
                        smoothing.seekAndPlay((step * 7 % 25 + 1).toDouble(), "$where, first seek")
                        fullscreen(true)
                        smoothing.fullscreen = true
                        smoothing.seekAndPlay(((step * 7 + 13) % 25 + 1).toDouble(), "$where, seek in full screen")
                        fullscreen(false)
                        smoothing.fullscreen = false
                    }
                    smoothing.waitUntilHolding("round ${round + 1} to still smooth after $CYCLES cycles")
                    assertNull(player.playbackFailure, "round ${round + 1}")
                }
                lines += String.format(Locale.ROOT, "Round %d: %.0f s, closed in %d ms", round + 1, (System.nanoTime() - started) / 1e9, closeMs)
                println(lines.last())
            }
        } finally {
            report.writeText(lines.joinToString("\n") + "\n")
            folder.deleteRecursively()
        }
    }

    /** Drives smoothing as the player page does: [EmbeddedMpv.updateSmoothing] about twice a second. */
    private class Smoothing(private val player: EmbeddedMpv) {
        var fullscreen = false
        private var last: SmoothingStatus? = null
        private var lastUpdate = 0L

        private fun update(): SmoothingStatus? {
            val now = System.nanoTime()
            if (last == null || now - lastUpdate > 500_000_000L) {
                last = player.updateSmoothing(SmoothingMode.MOTION, fullscreen)
                lastUpdate = now
            }
            return last
        }

        fun waitUntilHolding(what: String) = waitFor(what, { "last status: ${last?.detail}" }) {
            lastUpdate = 0L
            update()?.let { it.motionActive && it.detail.startsWith("Holding") } == true
        }

        /** Seeks to [target], then waits until the video plays 0.2 s past where it landed. */
        fun seekAndPlay(target: Double, what: String) {
            assertTrue(player.seekAbsolute(target), what)
            var landed: Double? = null
            waitFor(what, { "position ${player.playbackPosition()}, landed $landed, last status: ${last?.detail}" }) {
                update()
                val position = player.playbackPosition() ?: return@waitFor false
                if (landed == null && position in target - 0.6..target + 3.0 && player.diagnosticProperty("seeking") == "no") landed = position
                landed?.let { position >= it + 0.2 } == true
            }
            last?.let { assertTrue(it.motionActive, "$what: smoothing stopped: ${it.detail}") }
        }
    }

    private fun withPlayer(block: (EmbeddedMpv, (Boolean) -> Unit) -> Unit): Long {
        val player = EmbeddedMpv()
        lateinit var frame: Frame
        lateinit var canvas: Canvas
        lateinit var borderless: BorderlessFullscreen
        SwingUtilities.invokeAndWait {
            frame = Frame("SkyWing teardown test")
            canvas = Canvas()
            frame.add(canvas)
            frame.setSize(480, 300)
            frame.isVisible = true
            borderless = BorderlessFullscreen(frame)
        }
        try {
            waitFor("the video surface") { canvas.isDisplayable }
            var attached = false
            SwingUtilities.invokeAndWait { attached = player.attach(canvas) }
            assertTrue(attached, "attach failed: ${player.lastError}")
            block(player) { on -> SwingUtilities.invokeAndWait { assertTrue(borderless.set(on) == on, "full screen $on") } }
            return closeWithin(player, 30_000)
        } finally {
            SwingUtilities.invokeAndWait {
                if (borderless.isFullscreen) borderless.set(false)
                frame.dispose()
            }
        }
    }

    /** Closes on its own thread, so a hang in mpv's teardown fails the test instead of stalling it. */
    private fun closeWithin(player: EmbeddedMpv, timeoutMs: Long): Long {
        val done = CountDownLatch(1)
        val started = System.nanoTime()
        Thread({ player.close(); done.countDown() }, "SkyWing teardown test close").apply { isDaemon = true }.start()
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) fail("Closing the player took longer than $timeoutMs ms")
        return (System.nanoTime() - started) / 1_000_000
    }

    companion object {
        private const val ROUNDS = 10
        private const val CYCLES = 100
        private const val LENGTH = 30

        private fun waitFor(what: String, detail: () -> String = { "" }, timeoutMs: Long = 20_000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                if (condition()) return
                Thread.sleep(50)
            }
            fail("Timed out waiting for $what. ${detail()}")
        }
    }
}
