package com.skywing.desktop.automation

import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library in the packaged SkyWing, on temporary folders and isolated data: scanning, search, thumbnail caching,
 * files that disappear, and recovery from damaged settings and a damaged library index. The user's registry
 * preferences and %LOCALAPPDATA%\SkyWing are compared before and after to prove they are not touched.
 */
class LibraryAutomationTest {
    @Before
    fun requireOptIn() {
        assumeTrue("Set SKYWING_TEST_AUTOMATION=true to run UI automation", System.getenv("SKYWING_TEST_AUTOMATION") == "true")
    }

    @Test
    fun scansAFolderSearchesCachesThumbnailsAndDropsRemovedFiles() {
        val realBefore = realUserState()
        val thumbnailFoldersBefore = thumbnailPlayerFolders()
        lateinit var library: File
        val first = SkyWingTestApp.launch("library") { root, dir -> library = seedLibrary(root, dir) }
        // The data stays for a relaunch; delete it whatever happens, even when a step fails.
        try {
            try {
                first.activate("Videos", PlayerControls.TAB)
                first.find("2 of 2 videos", PlayerControls.LABEL, timeoutMs = 30_000)
                assertTrue(first.isAbsent("notes", PlayerControls.LABEL), "a text file is not listed as a video")

                first.typeInto("Search videos", "vp9")
                first.find("1 of 2 videos", PlayerControls.LABEL)
                first.waitUntil("only the VP9 clip to be listed") { s ->
                    s.nodes.any { ControlQuery.matches(it, "clip-vp9", PlayerControls.LABEL) } &&
                        s.nodes.none { ControlQuery.matches(it, "clip-h264", PlayerControls.LABEL) }
                }
                first.typeInto("Search videos", "", erase = 3)
                first.find("2 of 2 videos", PlayerControls.LABEL)

                val thumbs = first.dataDir.resolve("LocalAppData/SkyWing/library/thumbs").toFile()
                first.waitUntil("a cached thumbnail for each video", timeoutMs = 60_000) { thumbs.jpegs().size == 2 }
            } finally {
                first.close(keepData = true)
            }
            assertEquals(true, first.exitedGracefully)
            // SkyWing closed while its thumbnail players waited idle; their image folders go with it.
            assertEquals(emptySet(), thumbnailPlayerFolders() - thumbnailFoldersBefore, "thumbnail players' folders left in the temp folder")
            val cached = first.dataDir.resolve("LocalAppData/SkyWing/library/thumbs").toFile().jpegs()

            assertTrue(File(library, "clip-h264.mp4").delete(), "remove one video from the folder")
            SkyWingTestApp.launch("library-relaunch", dataDir = first.dataDir).use { app ->
                app.activate("Videos", PlayerControls.TAB)
                app.find("1 of 1 videos", PlayerControls.LABEL, timeoutMs = 30_000)
                app.find("clip-vp9", PlayerControls.LABEL)
                assertTrue(app.isAbsent("clip-h264", PlayerControls.LABEL), "a removed file leaves the library")
                val again = app.dataDir.resolve("LocalAppData/SkyWing/library/thumbs").toFile().jpegs()
                assertTrue(again.isNotEmpty() && again.all { it in cached }, "cached thumbnails are reused, not made again: $cached -> $again")
                app.close()
                assertEquals(true, app.exitedGracefully)
            }
        } finally {
            first.close()
        }
        assertEquals(realBefore, realUserState(), "the user's registry preferences and SkyWing data were not touched")
    }

    @Test
    fun damagedSettingsAndADamagedLibraryIndexDoNotStopSkyWing() {
        val realBefore = realUserState()
        SkyWingTestApp.launch("damaged-settings") { root, dir ->
            seedLibrary(root, dir)
            root.node("com/skywing/desktop/playback").apply {
                put("smoothing", "NOT-A-MODE"); put("motion.vrr", "maybe"); put("motion.lowerAutomatically", "sometimes")
                put("motion.1080-sdr.stop", "many"); put("motion.1080-sdr.quality", "")
            }
            root.node("com/skywing/desktop/playback-history").apply {
                put("count", "999"); put("path.0", ""); put("position.0", "x"); put("duration.0", "-1")
            }
            root.node("com/skywing/desktop/smb-share-profiles").apply { put("count", "-4"); put("name.0", "") }
            val index = dir.resolve("LocalAppData/SkyWing/library").also { Files.createDirectories(it) }
            Files.write(index.resolve("media-index.bin"), ByteArray(4096) { (it * 7).toByte() })
        }.use { app ->
            assertTrue(app.find("Folders", PlayerControls.TAB).selected, "SkyWing opens normally")
            app.activate("Videos", PlayerControls.TAB)
            app.find("2 of 2 videos", PlayerControls.LABEL, timeoutMs = 30_000)
            app.activate("Play all", PlayerControls.BUTTON)
            val player = PlayerControls(app)
            player.waitAdvancing("a video despite damaged settings")
            player.waitButton("Smoothing: Off")
            app.close()
            assertEquals(true, app.exitedGracefully)
        }
        assertEquals(realBefore, realUserState(), "the user's registry preferences and SkyWing data were not touched")
    }

    private fun seedLibrary(root: Preferences, dir: Path): File {
        val library = dir.resolve("Library").toFile().apply { mkdirs() }
        mp4.copyTo(File(library, mp4.name))
        mkv.copyTo(File(library, mkv.name))
        File(library, "notes.txt").writeText("not a video")
        root.node("com/skywing/desktop/local-library-folders").apply { putInt("count", 1); put("folder.0", library.absolutePath) }
        return library
    }

    /** The image folders of SkyWing's thumbnail players in the temp folder the launched SkyWing uses (TMP, as Windows picks it). */
    private fun thumbnailPlayerFolders(): Set<String> {
        val temp = (System.getenv("TMP") ?: System.getenv("TEMP"))?.let(::File) ?: return emptySet()
        return temp.listFiles { f -> f.isDirectory && THUMBNAIL_PLAYER_FOLDER.matches(f.name) }?.map { it.name }?.toSet().orEmpty()
    }

    private fun File.jpegs(): Set<String> = listFiles { f -> f.extension == "jpg" }?.map { it.name }?.toSet().orEmpty()

    /** The user's real SkyWing preferences (registry) and data folder, as text, to compare before and after. */
    private fun realUserState(): String {
        val process = ProcessBuilder("reg", "query", """HKCU\Software\JavaSoft\Prefs\com\skywing""", "/s").redirectErrorStream(true).start()
        val registry = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        // The test JVM's LOCALAPPDATA is redirected (build.gradle.kts); the real folder comes in a property.
        val real = System.getProperty("skywing.test.realLocalAppData")?.takeIf { it.isNotBlank() } ?: System.getenv("LOCALAPPDATA").orEmpty()
        val data = File(real, "SkyWing/library")
        val files = data.listFiles()?.sortedBy { it.name }?.joinToString("\n") { "${it.name} ${it.length()} ${it.lastModified()}" } ?: "none"
        return "$registry\n$files"
    }

    companion object {
        private val THUMBNAIL_PLAYER_FOLDER = Regex("skywing-thumb[0-9]+")
        private lateinit var media: File
        private lateinit var mp4: File
        private lateinit var mkv: File

        @BeforeClass @JvmStatic
        fun generateMedia() {
            if (System.getenv("SKYWING_TEST_AUTOMATION") != "true") return
            media = Files.createTempDirectory("skywing-e2e-library-").toFile()
            mp4 = TestMedia.encode(media, TestMedia.MP4_H264.copy(seconds = 20))
            mkv = TestMedia.encode(media, TestMedia.MKV_VP9.copy(seconds = 20))
        }

        @AfterClass @JvmStatic
        fun deleteMedia() {
            if (::media.isInitialized) media.deleteRecursively()
        }
    }
}
