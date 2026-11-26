package com.skywing.desktop.automation

import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Full screen and navigation in the packaged SkyWing, through its own controls over Java Access Bridge. Window
 * rectangles are read with GetWindowRect. Shortcuts are posted to SkyWing's own window; nothing uses screen

 * coordinates or global input.
 */
class FullscreenAutomationTest {
    @Before
    fun requireOptIn() {
        assumeTrue("Set SKYWING_TEST_AUTOMATION=true to run UI automation", System.getenv("SKYWING_TEST_AUTOMATION") == "true")
    }

    @Test
    fun fullScreenCoversTheMonitorHidesTheControlsAndReturnsToTheWindow() =
        SkyWingTestApp.launch("fullscreen", args = listOf(clip.absolutePath)).use { app ->
            val player = PlayerControls(app)
            player.waitAdvancing("the video in a window")
            val main = app.mainWindow()
            val monitor = Win32.monitorBounds(main)
            val windowed = Win32.bounds(main)
            assertNotEquals(monitor, windowed, "SkyWing starts in a window, not covering the monitor")

            player.press("Toggle full screen (F)")
            app.waitUntil("a SkyWing window to cover the whole monitor") { app.windows().any { Win32.bounds(it) == monitor } }
            player.waitAdvancing("the video in full screen")
            app.find("Toggle full screen (F)", PlayerControls.BUTTON)
            val beforeHiding = player.percent()
            // Auto-hidden controls leave the accessibility tree, so the F shortcut is the way back.
            app.waitUntil("the controls to hide in full screen", timeoutMs = 20_000) { s ->
                s.nodes.none { ControlQuery.matches(it, "Toggle full screen (F)", PlayerControls.BUTTON) }
            }
            Thread.sleep(2_000)
            // Accessibility: any key brings the hidden controls back into the tree, so keyboard and screen-reader
            // users reach them without the mouse. Shift does nothing else in SkyWing.
            val covering = app.windows().first { Win32.bounds(it) == monitor }
            Win32.postKey(covering, Win32.VK_SHIFT, Win32.SCAN_SHIFT)
            app.find("Toggle full screen (F)", PlayerControls.BUTTON)
            player.seek()
            app.waitUntil("the controls to hide again", timeoutMs = 20_000) { s ->
                s.nodes.none { ControlQuery.matches(it, "Toggle full screen (F)", PlayerControls.BUTTON) }
            }

            val fullscreen = app.windows().first { Win32.bounds(it) == monitor }
            Win32.postKey(fullscreen, Win32.VK_F, Win32.SCAN_F)
            app.waitUntil("SkyWing to leave full screen after F") { app.windows().none { Win32.bounds(it) == monitor } }
            app.waitUntil("the main window back at its size") { Win32.bounds(main) == windowed }
            player.waitAdvancing("the video back in the window")
            assertTrue(player.percent() > beforeHiding + 5, "kept playing while the controls were hidden: $beforeHiding -> ${player.percent()}")
            Win32.postKey(main, Win32.VK_SPACE, Win32.SCAN_SPACE)
            player.waitButton("Play")
            Win32.postKey(main, Win32.VK_SPACE, Win32.SCAN_SPACE)
            player.waitButton("Pause")
            assertEquals(1, app.windows().count { Win32.title(it) == SkyWingTestApp.MAIN_TITLE }, "one main window, none left invisible or duplicated")

            app.close()
            assertEquals(true, app.exitedGracefully, "every SkyWing process exited by itself after full screen")
        }

    @Test
    fun leavingThePlayerPausesTheVideoAndNowPlayingResumesItWhereItWas() =
        SkyWingTestApp.launch("navigation-during-playback", args = listOf(clip.absolutePath)).use { app ->
            val player = PlayerControls(app)
            player.waitAdvancing("the video")
            player.press("Back to the library")
            app.waitUntil("the library to show") { s -> s.nodes.none { ControlQuery.matches(it, "Seek", PlayerControls.LABEL) } }
            app.openSettingsPage("Playback")
            app.activate("Videos", PlayerControls.TAB)
            Thread.sleep(1_500)
            // M3: Now playing is a tab of the navigation, beside the sections.
            app.activate("Now playing", PlayerControls.TAB)
            val back = PlayerControls(app)
            back.waitTitle(clip.nameWithoutExtension)
            // Leaving the player pauses the video (Main.kt go()); it comes back paused where it was.
            back.waitButton("Play")
            val held = back.percent()
            Thread.sleep(800)
            assertEquals(held, back.percent(), 0.5, "the video stays paused after returning")
            back.press("Play")
            back.waitAdvancing("the same video, resumed")
            assertTrue(back.percent() >= held, "resumed from where it was: $held -> ${back.percent()}")
            app.close()
            assertEquals(true, app.exitedGracefully)
        }

    companion object {
        private lateinit var media: File
        private lateinit var clip: File

        @BeforeClass @JvmStatic
        fun generateMedia() {
            if (System.getenv("SKYWING_TEST_AUTOMATION") != "true") return
            media = Files.createTempDirectory("skywing-e2e-fullscreen-").toFile()
            clip = TestMedia.encode(media, TestMedia.MP4_H264.copy(seconds = 60))
        }

        @AfterClass @JvmStatic
        fun deleteMedia() {
            if (::media.isInitialized) media.deleteRecursively()
        }
    }
}
