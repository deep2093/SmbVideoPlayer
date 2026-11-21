package com.skywing.desktop.automation

import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Motion smoothing in the packaged SkyWing, driven over Java Access Bridge with a generated constant-rate 24 fps
 * 1280x720 H.264 video (ffmpeg). "Holding N fps" is the configured output rate that the running motion engine reports. It is not a
 * measurement of frames shown on the display; real cadence, VRR and smoothness need hardware verification.
 */
class MotionAutomationTest {
    @Before
    fun requireOptIn() {
        assumeTrue("Set SKYWING_TEST_AUTOMATION=true to run UI automation", System.getenv("SKYWING_TEST_AUTOMATION") == "true")
        assumeTrue("ffmpeg is needed for a constant-rate motion fixture", TestMedia.ffmpeg != null)
    }

    @Test
    fun smoothingButtonTurnsMotionOnSurvivesSeekingAndTheChoiceSurvivesARestart() {
        val first = SkyWingTestApp.launch("motion-toggle", args = listOf(clip.absolutePath)) { root, _ ->
            root.node(PLAYBACK).put("smoothing", "OFF")
        }
        // The data stays for a relaunch; delete it whatever happens, even when a step fails.
        try {
            try {
                val player = PlayerControls(first)
                player.waitAdvancing("the 24 fps video")
                first.waitUntil("the status to say smoothing is off") { s -> s.status()?.startsWith("Smoothing off") == true }

                player.press("Smoothing: Off")
                val held = first.waitForHolding()
                assertTrue(held in setOf(48.0, 60.0), "24 fps uses its 2.5x default (60) or 2x (48) on a slower screen: $held")
                player.waitButton("Smoothing: On")
                assertEquals("MOTION", first.preferences().node(PLAYBACK).get("smoothing", null), "the choice is saved")
                player.waitAdvancing("the video with motion smoothing")

                player.press("Forward 10 seconds")
                player.waitAdvancing("the video after seeking with motion smoothing")
                assertEquals(held, first.waitForHolding(), "the configured rate after a seek")

                player.press("Motion options")
                first.waitUntil("the Motion options window") { s -> s.nodes.any { ControlQuery.matches(it, "Motion smoothing", CHECK_BOX) } }
                assertTrue(first.find("Motion smoothing", CHECK_BOX).checked, "the dialog shows smoothing on")
                first.saveDiagnostics("motion options with smoothing on")
                first.activate("Done", PlayerControls.BUTTON)
                first.waitUntil("the Motion options window to close") { s -> s.nodes.none { ControlQuery.matches(it, "Motion smoothing", CHECK_BOX) } }
            } finally {
                first.close(keepData = true)
            }
            assertEquals(true, first.exitedGracefully, "SkyWing exited by itself with the motion engine running")

            SkyWingTestApp.launch("motion-toggle-relaunch", dataDir = first.dataDir, args = listOf(clip.absolutePath)).use { app ->
                val player = PlayerControls(app)
                player.waitButton("Smoothing: On")
                app.waitForHolding()
                player.press("Smoothing: On")
                player.waitButton("Smoothing: Off")
                app.waitUntil("the status to say smoothing is off") { s -> s.status()?.startsWith("Smoothing off") == true }
                app.waitUntil("smoothing=OFF saved") { app.preferences().node(PLAYBACK).get("smoothing", null) == "OFF" }
                player.waitAdvancing("the video after turning smoothing off")
                app.close()
                assertEquals(true, app.exitedGracefully)
            }
        } finally {
            first.close()
        }
    }

    @Test
    fun aSavedMotionProfileSetsTheRateAndInvalidValuesFallBackSafely() {
        val first = SkyWingTestApp.launch("motion-profile", args = listOf(clip.absolutePath)) { root, _ ->
            root.node(PLAYBACK).apply { put("smoothing", "MOTION"); putInt("motion.$PROFILE.stop", 0); put("motion.$PROFILE.quality", "BEST") }
        }
        // The data stays for a relaunch; delete it whatever happens, even when a step fails.
        try {
            try {
                assertEquals(48.0, first.waitForHolding(), "a saved 2x profile holds 48 fps for a 24 fps video")
            } finally {
                first.close(keepData = true)
            }
            SkyWingTestApp.launch("motion-profile-invalid", dataDir = first.dataDir, args = listOf(clip.absolutePath)) { root, _ ->
                root.node(PLAYBACK).apply { putInt("motion.$PROFILE.stop", -5); put("motion.$PROFILE.quality", "NOT-A-PRESET") }
            }.use { app ->
                assertEquals(48.0, app.waitForHolding(), "an out-of-range stop is clamped to 2x")
                PlayerControls(app).waitAdvancing("the video with a damaged motion profile")
                app.close()
                assertEquals(true, app.exitedGracefully)
            }
        } finally {
            first.close()
        }
    }

    private fun Screen.status(): String? = nodes.firstOrNull { it.roleName == PlayerControls.LABEL && STATUS.containsMatchIn(it.name) }?.name

    /** Waits for the engine to report the rate it holds, and returns it. */
    private fun SkyWingTestApp.waitForHolding(): Double {
        var rate = 0.0
        waitUntil("the motion engine to report 'Holding N fps'", timeoutMs = 45_000) { s ->
            val text = s.status() ?: return@waitUntil false
            if (UNAVAILABLE.any { it in text }) throw AssertionError("Motion smoothing did not start: $text")
            HOLDING.find(text)?.let { rate = it.groupValues[1].toDouble() } != null
        }
        return rate
    }

    companion object {
        private const val PLAYBACK = "com/skywing/desktop/playback"
        private const val PROFILE = "1080-sdr"
        private const val CHECK_BOX = "check box"
        private val HOLDING = Regex("""^Holding ([0-9.]+) fps""")
        private val STATUS = Regex("""^(Smoothing off|Holding |Starting motion|Motion |Play a |Waiting |Could not |Can.t keep|Lowered|No smoothing|Cannot keep)""")
        private val UNAVAILABLE = listOf("unavailable:", "Play a hardware-decoded video first", "No smoothing rate", "Could not")
        private lateinit var media: File
        private lateinit var clip: File

        @BeforeClass @JvmStatic
        fun generateMedia() {
            if (System.getenv("SKYWING_TEST_AUTOMATION") != "true" || TestMedia.ffmpeg == null) return
            media = Files.createTempDirectory("skywing-e2e-motion-").toFile()
            clip = TestMedia.constantRateMp4(media, "motion-720p24.mp4", seconds = 90, width = 1280, height = 720, fps = 24)
        }

        @AfterClass @JvmStatic
        fun deleteMedia() {
            if (::media.isInitialized) media.deleteRecursively()
        }
    }
}
