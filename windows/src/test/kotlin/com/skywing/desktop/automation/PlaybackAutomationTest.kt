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

/** The player's transport bar as JAB shows it: the Seek slider and the buttons next to it. */
internal class PlayerControls(private val app: SkyWingTestApp) {
    private val bar: String by lazy { seek().parentPath }

    /** The Seek slider; its JAB value is the position in percent of the duration. */
    fun seek(): JabNode = app.find("Seek", LABEL) { it.value != null }
    fun percent(): Double = seek().value!!.toDouble()
    /** Which of [candidates] the player shows as its title. */
    fun title(candidates: List<String>): String? = app.withScreen { s ->
        candidates.singleOrNull { name -> s.nodes.any { it.parentPath == bar && ControlQuery.matches(it, name, LABEL) } }
    }

    /** A transport button. The mini player in the navigation has its own Play/Pause, so lookups stay in the bar. */
    fun press(label: String) = app.activate(label, BUTTON, where = { it.parentPath == bar })
    fun waitButton(label: String) =
        app.waitUntil("the $label button") { s -> s.nodes.any { ControlQuery.matches(it, label, BUTTON) && it.parentPath == bar } }

    fun waitTitle(expected: String) =
        app.waitUntil("the player to show $expected") { s -> s.nodes.any { it.parentPath == bar && ControlQuery.matches(it, expected, LABEL) } }

    /**
     * Waits until the position moves forward by more than [minStepPercent] of the duration between two readings at
     * least 400 ms apart. The default suits the tests' clips of a few seconds. On an hour-long clip 0.5 % is about 20
     * seconds of playback, longer than the timeout, so pass 0.0 there.
     */
    fun waitAdvancing(what: String, minStepPercent: Double = 0.5) {
        var last = -1.0
        var lastAt = 0L
        app.waitUntil("$what (position advancing)", timeoutMs = 15_000) { s ->
            val now = ControlQuery.single(s.nodes, "Seek", LABEL) { it.value != null }.value!!.toDouble()
            val time = System.currentTimeMillis()
            val advanced = last >= 0 && now > last + minStepPercent && time - lastAt >= 400
            if (last < 0 || time - lastAt >= 400) { last = now; lastAt = time }
            advanced
        }
    }

    fun waitPercent(what: String, range: ClosedFloatingPointRange<Double>) =
        app.waitUntil("$what (Seek in $range)") { s -> ControlQuery.single(s.nodes, "Seek", LABEL) { it.value != null }.value!!.toDouble() in range }

    companion object {
        const val LABEL = "label"
        const val BUTTON = "push button"
        const val TAB = "page tab"
    }
}

/**
 * Plays generated videos in the packaged SkyWing through its real controls, read and pressed over Java Access
 * Bridge. Media and library data are temporary; SkyWing runs on isolated preferences (see [SkyWingTestApp]).
 */
class PlaybackAutomationTest {
    @Before
    fun requireOptIn() {
        assumeTrue("Set SKYWING_TEST_AUTOMATION=true to run UI automation", System.getenv("SKYWING_TEST_AUTOMATION") == "true")
    }

    @Test
    fun mp4PlaysPausesResumesAndSeeks() = transport(mp4)

    @Test
    fun mkvPlaysPausesResumesAndSeeks() = transport(mkv)

    /** B38: a second start with a video plays it in the window already open and exits. */
    @Test
    fun aSecondStartPlaysItsVideoInTheOpenWindow() = SkyWingTestApp.launch("second-start", args = listOf(mp4.absolutePath)).use { app ->
        val player = PlayerControls(app)
        player.waitTitle(mp4.nameWithoutExtension)
        val window = app.mainWindow()
        assertEquals(0, app.startAgain(listOf(mkv.absolutePath)), "the second start exits once it has handed over")
        player.waitTitle(mkv.nameWithoutExtension)
        player.waitAdvancing("${mkv.name} playing after a second start")
        assertEquals(listOf(window), app.windows(), "still one SkyWing window")
        app.close()
        assertEquals(true, app.exitedGracefully)
    }

    private fun transport(clip: File) = SkyWingTestApp.launch("transport-${clip.extension}", args = listOf(clip.absolutePath)).use { app ->
        val player = PlayerControls(app)
        player.waitTitle(clip.nameWithoutExtension)
        player.waitAdvancing("${clip.name} playing")

        player.press("Pause")
        player.waitButton("Play")
        val held = player.percent()
        Thread.sleep(800)
        assertEquals(held, player.percent(), 0.5, "position holds while paused")

        player.press("Play")
        player.waitAdvancing("${clip.name} resumed")

        player.press("Pause")
        val before = player.percent()
        player.press("Forward 10 seconds")
        player.waitPercent("forward 10 s of a 20 s video", (before + 40)..100.0)
        val after = player.percent()
        player.press("Back 10 seconds")
        player.waitPercent("back 10 s", 0.0..(after - 40))
        player.press("Play")
        player.waitAdvancing("${clip.name} after seeking")

        app.close()
        assertEquals(true, app.exitedGracefully, "SkyWing exited by itself while a video was playing")
    }

    @Test
    fun videosPagePlaysTheQueueAndStopKeepsProgressAcrossARestart() {
        val first = SkyWingTestApp.launch("queue") { root, dir ->
            val library = dir.resolve("Library").toFile().apply { mkdirs() }
            // Continue watching keeps only positions past 5 s and more than 30 s before the end, so these run 60 s.
            longMp4.copyTo(File(library, mp4.name))
            longMkv.copyTo(File(library, mkv.name))
            root.node("com/skywing/desktop/local-library-folders").apply { putInt("count", 1); put("folder.0", library.absolutePath) }
        }
        // The data stays for a relaunch; delete it whatever happens, even when a step fails.
        try {
            try {
                first.activate("Videos", PlayerControls.TAB)
                first.find("2 of 2 videos", PlayerControls.LABEL, timeoutMs = 30_000)
                first.activate("Play all", PlayerControls.BUTTON)
                val player = PlayerControls(first)
                player.waitAdvancing("the first queued video")
                val opening = player.title(NAMES)
                assertTrue(opening != null, "plays one of the library videos")
                val other = NAMES.single { it != opening }

                player.press("Next video")
                player.waitTitle(other)
                player.waitAdvancing("the next video")
                player.press("Previous video")
                player.waitTitle(opening)
                player.waitAdvancing("the previous video")

                player.press("Forward 10 seconds")
                player.waitPercent("a position Continue watching keeps (past 5 s, before the last 30 s)", 12.0..45.0)
                player.press("Stop (S)")
                first.find("Continue watching", PlayerControls.LABEL, timeoutMs = 15_000)
                assertTrue(first.isAbsent("Seek", PlayerControls.LABEL), "the player closed")
            } finally {
                first.close(keepData = true)
            }
            assertEquals(true, first.exitedGracefully)

            SkyWingTestApp.launch("queue-relaunch", dataDir = first.dataDir).use { app ->
                app.activate("Videos", PlayerControls.TAB)
                app.find("Continue watching", PlayerControls.LABEL, timeoutMs = 30_000)
                app.find("2 of 2 videos", PlayerControls.LABEL)
            }
        } finally {
            first.close()
        }
    }

    @Test
    fun aMissingVideoOnTheCommandLineShowsWhyAndLeavesTheLibraryUsable() {
        val missing = File(media, "missing-video.mp4")
        SkyWingTestApp.launch("missing", args = listOf(missing.absolutePath)).use { app ->
            app.find("Video not available", PlayerControls.LABEL)
            app.find("missing-video.mp4 can’t be found. It may have been moved or renamed, or the drive or network share " +
                "it is on is offline.", PlayerControls.LABEL)
            app.activate("OK", PlayerControls.BUTTON)
            app.waitUntil("the message to close") { s -> s.nodes.none { ControlQuery.matches(it, "Video not available", PlayerControls.LABEL) } }
            app.activate("Videos", PlayerControls.TAB)
            app.find("No videos yet. Add folders or a network share in Files.", PlayerControls.LABEL)
            app.close()
            assertEquals(true, app.exitedGracefully)
        }
    }

    @Test
    fun aCorruptVideoDoesNotStopTheNextOne() {
        val corrupt = File(media, "corrupt.mp4").apply { writeBytes(ByteArray(64 * 1024) { (it * 31 % 251).toByte() }) }
        val first = SkyWingTestApp.launch("corrupt", args = listOf(corrupt.absolutePath))
        // The data stays for a relaunch; delete it whatever happens, even when a step fails.
        try {
            try {
                val player = PlayerControls(first)
                player.waitTitle(corrupt.nameWithoutExtension)
                val reason = "SkyWing can’t play corrupt.mp4. The file may be damaged or in a format SkyWing can’t read."
                first.find("Can’t play this video", PlayerControls.LABEL)
                first.find(reason, PlayerControls.LABEL)
                first.activate("OK", PlayerControls.BUTTON)
                first.waitUntil("the message to close") { s -> s.nodes.none { ControlQuery.matches(it, "Can’t play this video", PlayerControls.LABEL) } }
                first.waitUntil("the reason to stay in the player status") { s -> s.nodes.any { it.roleName == PlayerControls.LABEL && reason in it.name } }
                first.find("--:--", PlayerControls.LABEL)
                Thread.sleep(1_500)
                assertEquals(0.0, player.percent(), "a corrupt file never plays")
                assertTrue(!player.seek().enabled, "Seek stays disabled without a duration")
                first.saveDiagnostics("after opening a corrupt file")
                player.press("Stop (S)")
                first.waitUntil("the player to close after Stop") { s -> s.nodes.none { ControlQuery.matches(it, "Seek", PlayerControls.LABEL) } }
            } finally {
                first.close(keepData = true)
            }
            SkyWingTestApp.launch("corrupt-then-good", dataDir = first.dataDir, args = listOf(mp4.absolutePath)).use { app ->
                PlayerControls(app).waitAdvancing("a good video after a corrupt one")
            }
        } finally {
            first.close()
        }
    }

    companion object {
        private lateinit var media: File
        private lateinit var mp4: File
        private lateinit var mkv: File
        private lateinit var longMp4: File
        private lateinit var longMkv: File
        private val NAMES get() = listOf(mp4.nameWithoutExtension, mkv.nameWithoutExtension)

        @BeforeClass @JvmStatic
        fun generateMedia() {
            if (System.getenv("SKYWING_TEST_AUTOMATION") != "true") return
            media = Files.createTempDirectory("skywing-e2e-media-").toFile()
            mp4 = TestMedia.encode(media, TestMedia.MP4_H264.copy(seconds = 20))
            mkv = TestMedia.encode(media, TestMedia.MKV_VP9.copy(seconds = 20))
            val long = File(media, "long").apply { mkdirs() }
            longMp4 = TestMedia.encode(long, TestMedia.MP4_H264.copy(seconds = 60))
            longMkv = TestMedia.encode(long, TestMedia.MKV_VP9.copy(seconds = 60))
        }

        @AfterClass @JvmStatic
        fun deleteMedia() {
            if (::media.isInitialized) media.deleteRecursively()
        }
    }
}
