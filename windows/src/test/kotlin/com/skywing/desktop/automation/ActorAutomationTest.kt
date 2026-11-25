package com.skywing.desktop.automation

import com.skywing.desktop.FolderOrganizerStore
import com.skywing.desktop.OrganizerData
import com.skywing.desktop.VirtualSet
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

/**
 * An actor page in the packaged SkyWing, on a Virtual folder made through the organizer's own save code, on temporary
 * clips and isolated data. The user's registry preferences and %LOCALAPPDATA%\SkyWing are compared before and after to
 * prove they are not touched. The names are invented.
 */
class ActorAutomationTest {
    @Before
    fun requireOptIn() {
        assumeTrue("Set SKYWING_TEST_AUTOMATION=true to run UI automation", System.getenv("SKYWING_TEST_AUTOMATION") == "true")
    }

    @Test
    fun actorPageOpensResumesAndReturns() {
        val realBefore = AutomationIsolation.realUserState()
        val thumbnailFoldersBefore = AutomationIsolation.thumbnailPlayerFolders()
        SkyWingTestApp.launch("actor-page") { root, dir -> seedVirtualFolder(root, dir) }.use { app ->
            // Narrower than 900 dp, so the actor list is hidden and the previous and next buttons show.
            WindowShots.setContentSizeDp(app.mainWindow(), 800, 650)

            app.activate("Folders", PlayerControls.TAB)
            app.activate("Virtual folders", PlayerControls.TAB)
            app.activate(SET_NAME, PlayerControls.BUTTON, timeoutMs = 30_000)
            // Four clips, two names: exactly two actor cards.
            app.find(AVA, PlayerControls.BUTTON, timeoutMs = 30_000)
            app.find(MIRA, PlayerControls.BUTTON)

            // Open the first actor: nothing is half watched, so the main button says Play newest.
            app.activate(AVA, PlayerControls.BUTTON)
            app.find("Play newest", PlayerControls.BUTTON)
            app.waitUntil("the opened page to have a focused control") { s -> s.nodes.any { it.isFocusedButton() } }

            // Esc goes back to the grid, and the card that was opened has focus.
            WindowShots.postEscape(app.mainWindow())
            app.waitUntil("$AVA's card to have focus after Esc") { s ->
                s.nodes.filter { ControlQuery.matches(it, AVA, PlayerControls.BUTTON) }.let { it.size == 1 && "focused" in it.single().states }
            }

            // Open it again; the next-actor button shows the other actor.
            app.activate(AVA, PlayerControls.BUTTON)
            app.find("Play newest", PlayerControls.BUTTON)
            app.waitUntil("the Next actor button to be enabled") { s ->
                ControlQuery.single(s.nodes, "Next actor", PlayerControls.BUTTON).enabled
            }
            app.activate("Next actor", PlayerControls.BUTTON)
            app.waitUntil("$MIRA's page to show") { s ->
                s.nodes.any { MIRA in it.name || MIRA in it.description } &&
                    ControlQuery.single(s.nodes, "Previous actor", PlayerControls.BUTTON).enabled &&
                    !ControlQuery.single(s.nodes, "Next actor", PlayerControls.BUTTON).enabled
            }

            // The new page focuses its first video card; the menu key opens that card's menu.
            app.waitUntil("a video card on $MIRA's page to have focus") { s ->
                s.nodes.any { it.isFocusedButton() && it.label !in NOT_VIDEO_CARDS }
            }
            Win32.postKey(app.mainWindow(), VK_APPS, SCAN_APPS, extended = true)
            app.waitUntil("the video card menu to show Play from start") { s -> s.nodes.any { it.label == "Play from start" } }

            app.close()
            assertEquals(true, app.exitedGracefully)
        }
        assertEquals(emptySet(), AutomationIsolation.thumbnailPlayerFolders() - thumbnailFoldersBefore, "thumbnail players' folders left in the temp folder")
        assertEquals(realBefore, AutomationIsolation.realUserState(), "the user's registry preferences and SkyWing data were not touched")
    }

    private fun JabNode.isFocusedButton(): Boolean = roleName == PlayerControls.BUTTON && "focused" in states

    /** Four clips named so they make two actors, a Virtual folder over them, saved by the organizer's own store. */
    private fun seedVirtualFolder(root: Preferences, dir: Path) {
        val library = dir.resolve("Library").toFile().apply { mkdirs() }
        listOf("Ava Stone - one.mp4", "Mira Lake - one.mp4").forEach { clipA.copyTo(File(library, it)) }
        listOf("Ava Stone - two.mkv", "Mira Lake - two.mkv").forEach { clipB.copyTo(File(library, it)) }
        root.node("com/skywing/desktop/local-library-folders").apply { putInt("count", 1); put("folder.0", library.absolutePath) }
        // The app runs with LOCALAPPDATA at <dir>/LocalAppData; its organizer file is there.
        val store = FolderOrganizerStore(dir.resolve("LocalAppData/SkyWing/library/folders.bin"))
        store.save(OrganizerData(sets = listOf(VirtualSet(id = "test-set", name = SET_NAME, folders = listOf(library.absolutePath)))))
    }

    companion object {
        private const val SET_NAME = "Test Collection"
        private const val AVA = "Ava Stone"
        private const val MIRA = "Mira Lake"

        private val NOT_VIDEO_CARDS = setOf("Back", "Previous actor", "Next actor")
        private const val VK_APPS = 0x5D
        private const val SCAN_APPS = 0x5D
        private lateinit var media: File
        private lateinit var clipA: File
        private lateinit var clipB: File

        @BeforeClass @JvmStatic
        fun generateMedia() {
            if (System.getenv("SKYWING_TEST_AUTOMATION") != "true") return
            media = Files.createTempDirectory("skywing-e2e-actor-").toFile()
            clipA = TestMedia.encode(media, TestMedia.MP4_H264.copy(seconds = 10))
            clipB = TestMedia.encode(media, TestMedia.MKV_VP9.copy(seconds = 10))
        }

        @AfterClass @JvmStatic
        fun deleteMedia() {
            if (::media.isInitialized) media.deleteRecursively()
        }
    }
}
