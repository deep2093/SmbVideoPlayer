package com.skywing.desktop

import androidx.compose.ui.input.key.Key
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After

class PageViewsTest {
    private val folders = mutableListOf<Path>()
    private val opened = mutableListOf<LibraryViews>()

    @After
    fun cleanUp() {
        // A save still waiting would write its file again after the folder is gone.
        opened.forEach { it.flush() }
        folders.forEach { it.toFile().deleteRecursively() }
    }

    private fun file(): Path = Files.createTempDirectory("skywing-views-test").also { folders.add(it) }.resolve("views.json")

    private fun views(seed: FolderPrefs = FolderPrefs(), path: Path = file()) =
        LibraryViews(LibraryViewStore(path), seed).also { opened.add(it) }

    @Test
    fun unsetPageUsesLastChoice() {
        val views = views()
        views.change("a") { it.copy(videoPoster = true, size = CardSizes.LARGE) }
        assertEquals(PageView(videoPoster = true, size = CardSizes.LARGE), views.of("never-set"))
    }

    @Test
    fun changingAPageSetsLastChoice() {
        val views = views()
        views.change("a") { it.copy(actorPortrait = true) }
        views.change("b") { it.copy(list = true) }
        assertEquals(PageView(actorPortrait = true), views.of("a"))
        assertEquals(PageView(actorPortrait = true, list = true), views.of("b"))
        assertEquals(views.of("b"), views.data.last)
    }

    @Test
    fun stepAndMediumMoveAlongTheSizes() {
        val views = views()
        views.step("a", 1)
        assertEquals(210, views.of("a").size)
        views.step("a", -1)
        views.step("a", -1)
        assertEquals(170, views.of("a").size)
        views.medium("a")
        assertEquals(CardSizes.NORMAL, views.of("a").size)
        repeat(30) { views.step("a", 1) }
        assertEquals(CardSizes.STEPS.last(), views.of("a").size)
    }

    @Test
    fun seedFromOldPrefs() {
        val seed = FolderPrefs(listMode = true, folderCardSize = 150, videoCardSize = 255, tallPages = setOf("custom", VIDEOS_PAGE))
        val data = LibraryViewStore(file()).load(seed)
        assertEquals(PageView(size = 150, list = true), data.last)
        assertEquals(PageView(actorPortrait = true, videoPoster = true, size = 150, list = true), data.pages["custom"])
        val videos = data.pages.getValue(VIDEOS_PAGE)
        assertEquals(255, videos.size)
        assertTrue(videos.actorPortrait && videos.videoPoster)
    }

    @Test
    fun seedOfDefaultPrefsIsTheDefaultViews() {
        val data = LibraryViewStore(file()).load(FolderPrefs())
        assertEquals(ViewsData(pages = mapOf(VIDEOS_PAGE to PageView())), data)
    }

    @Test
    fun resetClearsEverything() {
        val views = views()
        views.change("a") { it.copy(list = true) }
        views.setLayout(ActorLayout.STUDIO)
        views.setKeepMenuOpen(true)
        views.reset()
        assertEquals(ViewsData(), views.data)
    }

    @Test
    fun listEverywhereChangesEveryPage() {
        val views = views()
        views.change("a") { it.copy(size = CardSizes.LARGE) }
        views.change("b") { it.copy(actorPortrait = true) }
        views.setListEverywhere(true)
        assertTrue(views.of("a").list && views.of("b").list && views.data.last.list)
        assertEquals(CardSizes.LARGE, views.of("a").size)
        assertTrue(views.of("b").actorPortrait)
        views.setListEverywhere(false)
        assertTrue(!views.of("a").list && !views.of("b").list && !views.of("other").list)
    }

    @Test
    fun choicesSurviveARestart() {
        val path = file()
        val first = views(path = path)
        first.change("a") { it.copy(videoPoster = true, size = CardSizes.SMALL) }
        first.change("b") { it.copy(list = true) }
        first.setLayout(ActorLayout.STUDIO)
        first.setKeepMenuOpen(true)
        first.flush()
        val again = views(path = path)
        assertEquals(first.data, again.data)
    }

    @Test
    fun unreadableFileKeepsDefaultsAndWritesNothing() {
        val path = file()
        for (text in listOf("""{"version":2,"last":{},"pages":{}}""", """{"version":1,"last":5,"pages":{}}""", """{"version":1,"last":{},"pages":{"a":3}}""", """{"last":{},"pages":{}}""")) {
            Files.writeString(path, text)
            val before = Files.readAllBytes(path)
            val views = views(FolderPrefs(listMode = true), path)
            assertEquals(ViewsData(), views.data)
            views.change("a") { it.copy(list = true) }
            views.reset()
            views.flush()
            assertTrue(before.contentEquals(Files.readAllBytes(path)), text)
        }
    }

    @Test
    fun aFileThatCannotBeReadKeepsDefaultsAndWritesNothing() {
        // A folder in the file's place can't be read as a file, so it counts as locked.
        val folder = Files.createTempDirectory("skywing-views-test").also { folders.add(it) }
        val path = folder.resolve("views.json")
        Files.createDirectory(path)
        Files.writeString(path.resolve("x"), "x")
        val views = views(FolderPrefs(listMode = true), path)
        assertEquals(ViewsData(), views.data)
        views.change("a") { it.copy(list = true) }
        views.flush()
        assertTrue(Files.isDirectory(path))
        assertEquals(listOf("x"), Files.list(path).use { list -> list.map { it.fileName.toString() }.toList() })
        assertEquals(setOf("views.json"), Files.list(folder).use { list -> list.map { it.fileName.toString() }.toList().toSet() })
    }

    @Test
    fun aSaveThatFailsKeepsTheChoice() {
        val folder = Files.createTempDirectory("skywing-views-test").also { folders.add(it) }
        val path = folder.resolve("views.json")
        val views = views(path = path)
        // The file is read as missing; now its place is taken by a folder that isn't empty, so the move fails.
        Files.createDirectory(path)
        Files.writeString(path.resolve("x"), "x")
        views.change("a") { it.copy(list = true, size = CardSizes.LARGE) }
        views.flush()
        assertEquals(PageView(list = true, size = CardSizes.LARGE), views.of("a"))
        assertTrue(Files.isDirectory(path))
    }

    @Test
    fun aSizeChangeThatChangesNothingDoesNotTellTheApp() {
        val views = views()
        var calls = 0
        views.onSizeChange = { calls++ }
        views.setSize("a", CardSizes.NORMAL)
        views.setSize("a", CardSizes.STEPS.last())
        assertEquals(1, calls)
        views.setSize("a", CardSizes.STEPS.last())
        views.step("a", 1)
        assertEquals(1, calls)
    }

    @Test
    fun theSizeForNewPagesLeavesPagesWithTheirOwnViewAlone() {
        val views = views()
        views.setSize("own", CardSizes.SMALL)
        var calls = 0
        views.onSizeChange = { calls++ }
        views.setSizeForNewPages(CardSizes.LARGE)
        assertEquals(CardSizes.LARGE, views.of("never-set").size)
        assertEquals(CardSizes.SMALL, views.of("own").size)
        assertEquals(1, calls)
        views.setSizeForNewPages(CardSizes.LARGE)
        assertEquals(1, calls)
    }

    @Test
    fun aFailingSizeHookDoesNotBreakTheChange() {
        val views = views()
        views.onSizeChange = { error("hook") }
        views.setSize("a", CardSizes.LARGE)
        assertEquals(CardSizes.LARGE, views.of("a").size)
        views.step("a", -1)
        assertEquals(255, views.of("a").size)
    }

    @Test
    fun videosPageKeepsItsOwnSizeWhenSeeded() {
        for (tall in listOf(emptySet(), setOf(VIDEOS_PAGE))) {
            val data = LibraryViewStore(file()).load(FolderPrefs(folderCardSize = 150, videoCardSize = 0, tallPages = tall))
            val videos = data.pages.getValue(VIDEOS_PAGE)
            assertEquals(CardSizes.NORMAL, videos.size)
            assertEquals(tall.isNotEmpty(), videos.actorPortrait && videos.videoPoster)
            assertEquals(150, data.last.size)
        }
    }

    @Test
    fun everySizeChangeTellsTheApp() {
        val views = views()
        var calls = 0
        views.onSizeChange = { calls++ }
        views.step("a", 1)
        views.step("a", -1)
        views.medium("a")
        views.setSize("a", CardSizes.LARGE)
        // Medium was already the size after the step back, so it is not a change.
        assertEquals(3, calls)
        views.change("a") { it.copy(list = true, actorPortrait = true, videoPoster = true) }
        views.setListEverywhere(false)
        assertEquals(3, calls)
    }

    @Test
    fun autoColumnsOnlyResetsTheColumns() {
        val prefs = FolderPrefs(columns = 4, listMode = true, folderCardSize = 150)
        assertEquals(prefs.copy(columns = 0), prefs.autoColumns())
        assertEquals(FolderPrefs(), FolderPrefs().autoColumns())
    }

    @Test
    fun sizeNames() {
        assertEquals("Small", CardSizes.named(135))
        assertEquals("Medium", CardSizes.named(188))
        assertEquals("Medium", CardSizes.named(230))
        assertEquals("Large", CardSizes.named(255))
        assertEquals("Small", CardSizes.named(88))
        assertEquals("Large", CardSizes.named(580))
    }

    @Test
    fun viewKeysIgnoredInTextField() {
        assertNull(viewKeyAction(Key.Zero, ctrl = true, inTextField = true))
        assertEquals(0, viewKeyAction(Key.Zero, ctrl = true, inTextField = false))
    }

    @Test
    fun viewKeysNeedCtrl() {
        assertNull(viewKeyAction(Key.Equals, ctrl = false, inTextField = false))
        assertNull(viewKeyAction(Key.Minus, ctrl = false, inTextField = false))
        assertEquals(1, viewKeyAction(Key.Equals, ctrl = true, inTextField = false))
        assertEquals(1, viewKeyAction(Key.Plus, ctrl = true, inTextField = false))
        assertEquals(1, viewKeyAction(Key.NumPadAdd, ctrl = true, inTextField = false))
        assertEquals(-1, viewKeyAction(Key.Minus, ctrl = true, inTextField = false))
        assertEquals(-1, viewKeyAction(Key.NumPadSubtract, ctrl = true, inTextField = false))
        assertEquals(0, viewKeyAction(Key.NumPad0, ctrl = true, inTextField = false))
        assertNull(viewKeyAction(Key.A, ctrl = true, inTextField = false))
    }
}
