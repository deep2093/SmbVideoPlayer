package com.skywing.desktop.automation

import com.skywing.desktop.WindowsMediaIndex
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
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
 * Network paths and long-running use of the packaged SkyWing, on temporary data only.
 *
 * SMB is exercised two ways without creating shares or touching real ones: a generated clip is played through the
 * loopback admin share (\\localhost\C$\ plus its temp path), and a library entry on a host that cannot exist
 * (.invalid) stands in for a share that has gone away. A loopback share is never put in the library: the rescan
 * walks the whole share root, which would be the user's C: drive.
 */
class NetworkAndStabilityAutomationTest {
    @Before
    fun requireOptIn() {
        assumeTrue("Set SKYWING_TEST_AUTOMATION=true to run UI automation", System.getenv("SKYWING_TEST_AUTOMATION") == "true")
    }

    @Test
    fun playsAndSeeksAVideoOverTheLoopbackSmbShare() {
        val unc = loopback(mp4)
        assumeTrue("The loopback admin share is not reachable: $unc", unc?.isFile == true)
        SkyWingTestApp.launch("smb-loopback", args = listOf(unc!!.path)).use { app ->
            val player = PlayerControls(app)
            player.waitTitle(mp4.nameWithoutExtension)
            player.waitAdvancing("a video read over SMB")
            player.press("Pause")
            val before = player.percent()
            player.press("Forward 10 seconds")
            player.waitPercent("a seek over SMB", (before + 40)..100.0)
            player.press("Play")
            player.waitAdvancing("the video after seeking over SMB")
            app.close()
            assertEquals(true, app.exitedGracefully)
        }
    }

    @Test
    fun anUnreachableShareKeepsItsVideosReportsTheErrorAndSkyWingStaysUsable() =
        SkyWingTestApp.launch("smb-unreachable") { root, dir ->
            val library = dir.resolve("Library").toFile().apply { mkdirs() }
            mp4.copyTo(File(library, mp4.name))
            root.node("com/skywing/desktop/local-library-folders").apply { putInt("count", 1); put("folder.0", library.absolutePath) }
            val index = dir.resolve("LocalAppData/SkyWing/library/media-index.bin")
            Files.createDirectories(index.parent)
            WindowsMediaIndex(index).replaceSource(GONE_ID, "SMB", GONE_NAME, listOf(File(GONE_FILE)))
        }.use { app ->
            app.activate("Settings", PlayerControls.TAB)
            app.activate("Sync & scanning", PlayerControls.BUTTON)
            app.activate("View scan errors (1)", PlayerControls.BUTTON, timeoutMs = 30_000)
            app.find(GONE_NAME, PlayerControls.LABEL)
            app.find("Not reachable; the previous list was kept", PlayerControls.LABEL)

            app.activate("Videos", PlayerControls.TAB)
            app.find("2 of 2 videos", PlayerControls.LABEL, timeoutMs = 30_000)
            app.activate("Film", PlayerControls.LABEL)
            app.find("Video not available", PlayerControls.LABEL)
            app.waitUntil("the reason to name the video") { s -> s.nodes.any { it.roleName == PlayerControls.LABEL && it.name.startsWith(File(GONE_FILE).name + " can’t be found") } }
            app.activate("OK", PlayerControls.BUTTON)
            app.waitUntil("the message to close") { s -> s.nodes.none { ControlQuery.matches(it, "Video not available", PlayerControls.LABEL) } }
            assertTrue(app.isAbsent("Seek", PlayerControls.LABEL), "a video on an unreachable share does not open a broken player")
            app.activate(mp4.nameWithoutExtension, PlayerControls.LABEL)
            PlayerControls(app).waitAdvancing("a local video after the unreachable one")
            app.close()
            assertEquals(true, app.exitedGracefully)
        }

    @Test
    fun repeatedPlaybackAndNavigationKeepMemoryBoundedAndExitCleanly() =
        SkyWingTestApp.launch("stability") { root, dir ->
            val library = dir.resolve("Library").toFile().apply { mkdirs() }
            mp4.copyTo(File(library, mp4.name))
            mkv.copyTo(File(library, mkv.name))
            root.node("com/skywing/desktop/local-library-folders").apply { putInt("count", 1); put("folder.0", library.absolutePath) }
        }.use { app ->
            app.activate("Videos", PlayerControls.TAB)
            app.find("2 of 2 videos", PlayerControls.LABEL, timeoutMs = 30_000)
            val samples = mutableListOf<Long>()
            repeat(ROUNDS) { round ->
                val clip = if (round % 2 == 0) mp4 else mkv
                app.activate(clip.nameWithoutExtension, PlayerControls.LABEL)
                val player = PlayerControls(app)
                player.waitTitle(clip.nameWithoutExtension)
                player.waitAdvancing("round ${round + 1}: ${clip.name}")
                player.press("Forward 10 seconds")
                player.press("Back to the library")
                app.waitUntil("round ${round + 1}: the library") { s -> s.nodes.none { ControlQuery.matches(it, "Seek", PlayerControls.LABEL) } }
                app.activate(if (round % 3 == 0) "Folders" else "Settings", PlayerControls.TAB)
                app.activate("Videos", PlayerControls.TAB)
                app.find("2 of 2 videos", PlayerControls.LABEL)
                samples += app.privateBytes()
            }
            val mb = samples.map { it / (1024 * 1024) }
            val growth = mb.last() - mb[WARM_UP - 1]
            File("build/reports/tests/test").mkdirs()
            File("build/reports/tests/test/app-memory.txt").writeText(
                "SkyWing private bytes (largest process of the launched tree) after each of $ROUNDS rounds of " +
                    "open video, play, seek, back to library, switch pages:\n${mb.joinToString(" MB, ")} MB\n" +
                    "Growth from round $WARM_UP to $ROUNDS: $growth MB\n",
            )
            assertTrue(growth < 160, "SkyWing memory grew $growth MB over ${ROUNDS - WARM_UP} rounds after warm-up: $mb")
            app.close()
            assertEquals(true, app.exitedGracefully, "SkyWing exited by itself after $ROUNDS rounds")
        }

    /** Private bytes of the biggest process SkyWing started (the launcher starts the Java VM as a child). */
    private fun SkyWingTestApp.privateBytes(): Long {
        val root = ProcessHandle.of(pid).orElseThrow()
        return (listOf(root) + root.descendants().toList()).maxOf { Kernel32.privateBytes(it.pid()) }
    }

    @Suppress("FunctionName")
    private interface Psapi : StdCallLibrary {
        @Structure.FieldOrder(
            "cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
            "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage",
            "PeakPagefileUsage", "PrivateUsage",
        )
        class Counters : Structure() {
            @JvmField var cb = 0
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
            init { cb = size() }
        }
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun K32GetProcessMemoryInfo(process: Pointer, counters: Counters, size: Int): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private object Kernel32 {
        private val api: Psapi = Native.load("kernel32", Psapi::class.java)
        private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

        fun privateBytes(pid: Long): Long {
            val handle = api.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt()) ?: return 0
            try {
                val counters = Psapi.Counters()
                return if (api.K32GetProcessMemoryInfo(handle, counters, counters.size())) counters.PrivateUsage else 0
            } finally {
                api.CloseHandle(handle)
            }
        }
    }

    companion object {
        private const val ROUNDS = 12
        private const val WARM_UP = 4
        private const val GONE_ID = "smb-test-gone"
        private const val GONE_NAME = "Test share (gone)"
        private const val GONE_FILE = """\\skywing-test-share.invalid\Videos\Film.mp4"""
        private lateinit var media: File
        private lateinit var mp4: File
        private lateinit var mkv: File

        /** The same file through the loopback admin share, or null when it is not on a lettered local drive. */
        private fun loopback(file: File): File? {
            val path = file.absolutePath
            if (path.length < 3 || path[1] != ':' || path[2] != '\\') return null
            return File("""\\localhost\""" + path[0] + "$" + path.substring(2))
        }

        @BeforeClass @JvmStatic
        fun generateMedia() {
            if (System.getenv("SKYWING_TEST_AUTOMATION") != "true") return
            media = Files.createTempDirectory("skywing-e2e-network-").toFile()
            mp4 = TestMedia.encode(media, TestMedia.MP4_H264.copy(seconds = 20))
            mkv = TestMedia.encode(media, TestMedia.MKV_VP9.copy(seconds = 20))
        }

        @AfterClass @JvmStatic
        fun deleteMedia() {
            if (::media.isInitialized) media.deleteRecursively()
        }
    }
}
