package com.skywing.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.skywing.desktop.ui.adaptive.followFocusVisibility
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Spec 5.4, 5.5 and 5.10: focus comes back to the card the person left (scrolling only when it is out of view), typing a
 * name jumps to it, the arrows move between cards, the grid is one Tab stop, and the photo moves only between the grid
 * and an actor page.
 */
@OptIn(ExperimentalTestApi::class)
class GridFocusTest {
    @get:Rule val compose = createComposeRule()

    private val folder = Files.createTempDirectory("skywing-gridfocus")

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    /* ---------- The pure rules ---------- */

    @Test fun noScrollWhenVisible() {
        assertFalse(scrollNeeded(5, listOf(3, 4, 5, 6)))
    }

    @Test fun scrollWhenOffScreen() {
        assertTrue(scrollNeeded(9, listOf(3, 4, 5, 6)))
        assertTrue(scrollNeeded(0, emptyList()))
    }

    @Test fun typeToJumpFindsPrefix() {
        assertEquals(1, typeToJump(listOf("Alpha", "Bravo", "Charlie", "Brick"), "br"))
    }

    @Test fun typeToJumpIgnoresCase() {
        assertEquals(1, typeToJump(listOf("alpha", "BRAVO"), "bRa"))
    }

    @Test fun typeToJumpNoMatchIsNull() {
        assertNull(typeToJump(listOf("Alpha", "Bravo"), "z"))
        assertNull(typeToJump(listOf("Alpha"), ""))
    }

    @Test fun typedTextGrowsWithinASecondAndStartsAgainAfterIt() {
        assertEquals("ch", typedText("c", 1_000, 'h', 1_900))
        assertEquals("h", typedText("c", 1_000, 'h', 2_100))
    }

    @Test fun escAndAltLeftGoBack() {
        assertTrue(isBackShortcut(Key.Escape, alt = false, ctrl = false, shift = false))
        assertTrue(isBackShortcut(Key.DirectionLeft, alt = true, ctrl = false, shift = false))
        assertFalse(isBackShortcut(Key.DirectionLeft, alt = false, ctrl = false, shift = false))
        assertFalse(isBackShortcut(Key.Escape, alt = false, ctrl = true, shift = false))
    }

    @Test fun focusKeysMatchTileKeys() {
        val f = folders()
        val entry = WindowsMediaEntry("\\\\192.0.2.10\\videos\\A.mkv", "SMB:videos", "SMB", "Videos", 1, 2)
        assertEquals(f.videoTiles(listOf(entry), "set:1").single().key, videoFocusKey(File("\\\\192.0.2.10\\videos\\A.mkv")))
        assertEquals(actorTile(f, "x").key, actorFocusKey("x"))
    }

    @Test fun anActorCardsPhotoMovesUnderTheKeyOfItsPageHeader() {
        assertEquals(actorPhotoKey(actorKey("set:1", "x")), actorTile(folders(), "x").photoKey)
    }

    @Test fun backFromAnActorPageFocusesItsCard() {
        val hub = HubState()
        hub.setId = "set"
        hub.setGroup = "performer"
        assertTrue(hub.navigateBack(OrganizerData()))
        assertEquals(actorFocusKey("performer"), hub.focusTile)

        val own = HubState()
        own.ownId = "custom"
        own.ownGroup = "group"
        assertTrue(own.navigateBack(OrganizerData(own = listOf(OwnFolder("custom", "Custom")))))
        assertEquals(actorFocusKey("group"), own.focusTile)
    }

    /* ---------- The grid ---------- */

    @Test fun theFocusKeysCardTakesFocusWithoutAScroll() {
        val state = LazyGridState()
        var cleared = 0
        grid(numbered(300), state, focusKey = "dir:2") { cleared++ }
        compose.waitForIdle()
        compose.onNodeWithText("Folder 002").assertIsFocused()
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
        assertEquals(1, cleared)
    }

    @Test fun aCardOutOfViewIsScrolledToAndFocused() {
        val state = LazyGridState()
        var cleared = 0
        grid(numbered(300), state, focusKey = "dir:250") { cleared++ }
        compose.waitForIdle()
        compose.onNodeWithText("Folder 250").assertIsFocused()
        assertTrue(state.layoutInfo.visibleItemsInfo.any { it.index == 250 })
        assertEquals(1, cleared)
    }

    /** The title of the card that has focus (its first text), or null when nothing has. */
    private fun focusedText(): String? =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    @Test fun aKeyWithNoCardIsClearedAndFocusGoesToACardInViewWithoutAScroll() {
        // A merged or gone actor, or a video whose card is not on this page: focus is never left nowhere.
        var key by mutableStateOf<String?>(null)
        var cleared = 0
        val state = LazyGridState(firstVisibleItemIndex = 40, firstVisibleItemScrollOffset = 25)
        compose.setContent {
            Page { TileGrid(numbered(300), state, organizer(), "own:1", emptyMap(), { _, _ -> }, focusKey = key, onFocused = { cleared++ }) }
        }
        compose.waitForIdle()
        val before = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        key = "group:gone"
        compose.waitForIdle()
        assertTrue(focusedText().orEmpty().startsWith("Folder"), "focus: ${focusedText()}")
        assertEquals(before, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
        assertEquals(1, cleared)
    }

    @Test fun arrowsMoveBetweenCards() {
        val state = LazyGridState()
        grid(numbered(60), state)
        compose.onNodeWithText("Folder 000").requestFocus()
        focused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Folder 001").assertIsFocused()
        focused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Folder 000").assertIsFocused()
        val columns = state.layoutInfo.maxSpan
        focused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Folder %03d".format(columns)).assertIsFocused()
    }

    @Test fun typingANameJumpsToItsCard() {
        val tiles = listOf("Alpha", "Bravo", "Cara", "Chess", "Delta").mapIndexed { i, title -> folderTile(i, title) } + numbered(40, from = 5)
        grid(tiles, LazyGridState())
        compose.onNodeWithText("Alpha").requestFocus()
        focused().performKeyInput { pressKey(Key.C) }
        compose.onNodeWithText("Cara").assertIsFocused()
        focused().performKeyInput { pressKey(Key.H) }
        compose.onNodeWithText("Chess").assertIsFocused()
    }

    @Test fun theGridIsOneTabStop() {
        val tiles = numbered(60)
        compose.setContent {
            Page {
                Column(Modifier.fillMaxSize()) {
                    SkyButton("Before", {})
                    Box(Modifier.weight(1f)) { TileGrid(tiles, remember { LazyGridState() }, organizer(), "own:1", emptyMap(), { _, _ -> }) }
                    SkyButton("After", {})
                }
            }
        }
        compose.onNodeWithText("Folder 000").requestFocus()
        focused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("After").assertIsFocused()

        compose.onNodeWithText("Folder 003").requestFocus()
        focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        compose.onNodeWithText("Before").assertIsFocused()

        focused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("Folder 000").assertIsFocused()
    }

    @Test fun aBarIsOneTabStopAndItsArrowsMoveAlongIt() {
        compose.setContent {
            Column {
                SkyButton("Before", {})
                OneTabStop {
                    androidx.compose.foundation.layout.Row {
                        SkyButton("One", {})
                        SkyButton("Two", {})
                        SkyButton("Three", {})
                    }
                }
                SkyButton("After", {})
            }
        }
        compose.onNodeWithText("One").requestFocus()
        focused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Two").assertIsFocused()
        focused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("After").assertIsFocused()
        compose.onNodeWithText("Three").requestFocus()
        focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        compose.onNodeWithText("Before").assertIsFocused()
    }

    @Test fun theActorListAndTheSortBarAreOneTabStopEach() {
        val f = folders()
        val actors = listOf("ada", "bea", "cid").map { ActorRef(it, it.uppercase(), "vset:1:$it", "set:1|$it", emptyList()) }
        compose.setContent {
            Page {
                Box(Modifier.size(1000.dp, 700.dp)) {
                    ActorPage(actors[1], actors, f, onOpenActor = {}, onBack = {}, menuOf = { _, _, _ -> emptyList() })
                }
            }
        }
        // Tab leaves the actor list for the header's Back, not the next actor in the list (buttons are named by text).
        compose.onNodeWithText("Search actors").requestFocus()
        focused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("Back").assertIsFocused()
        // Tab leaves the sort bar; with no videos nothing follows it, so focus goes round to the actor list.
        compose.onNodeWithText("Newest").requestFocus()
        focused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("Search actors").assertIsFocused()
    }

    /* ---------- Esc in a search, and the focus ring after a keyboard back ---------- */

    /** An actor page inside the same back keys FoldersScreen puts around every page; [goBack] counts the backs. */
    private fun actorPageWithBackKeys(f: Folders, goBack: () -> Boolean) {
        val actors = listOf("ada", "bea").map { ActorRef(it, it.uppercase(), "vset:1:$it", "set:1|$it", emptyList()) }
        compose.setContent {
            Page {
                Box(Modifier.size(1000.dp, 700.dp).backKeys(goBack, onKeyboardBack = {})) {
                    ActorPage(actors[0], actors, f, onOpenActor = {}, onBack = {}, menuOf = { _, _, _ -> emptyList() })
                }
            }
        }
    }

    @Test fun escInASearchWithTextClearsItAndStaysThere() {
        val f = folders()
        f.hub.query = "ab"
        var backs = 0
        actorPageWithBackKeys(f) { backs++; true }
        compose.onNodeWithContentDescription("Search here").requestFocus()
        focused().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals("", f.hub.query)
        compose.onNodeWithContentDescription("Search here").assertIsFocused()
        assertEquals(0, backs)
    }

    @Test fun escInAnEmptySearchGoesBack() {
        val f = folders()
        var backs = 0
        actorPageWithBackKeys(f) { backs++; true }
        compose.onNodeWithContentDescription("Search here").requestFocus()
        focused().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    @Test fun altLeftInASearchWithTextGoesBackAndKeepsTheText() {
        val f = folders()
        f.hub.query = "ab"
        var backs = 0
        actorPageWithBackKeys(f) { backs++; true }
        compose.onNodeWithContentDescription("Search here").requestFocus()
        focused().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) } }
        compose.waitForIdle()
        assertEquals(1, backs)
        assertEquals("ab", f.hub.query)
    }

    /** The Folders search field takes the same Esc rule as the sort bar's (the screen itself is not drawn in tests). */
    @Test fun anyFieldWithTheEscRuleClearsFirstThenGoesBack() {
        var text by mutableStateOf("ab")
        var backs = 0
        compose.setContent {
            Box(Modifier.backKeys({ backs++; true }, onKeyboardBack = {})) {
                SkyField(text, { text = it }, "Search custom", Modifier.escClears(text) { text = "" })
            }
        }
        compose.onNodeWithContentDescription("Search custom").requestFocus()
        focused().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals("" to 0, text to backs)
        focused().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    @Test fun anEscThatGoesBackShowsTheFocusRing() {
        val visible = mutableStateOf(false)
        compose.setContent {
            Box(Modifier.followFocusVisibility(visible).backKeys({ true }, onKeyboardBack = { visible.value = true })) {
                SkyButton("Card", {})
            }
        }
        compose.onNodeWithText("Card").requestFocus()
        focused().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertTrue(visible.value)
    }

    @Test fun anEscWithNowhereToGoBackLeavesTheRingHidden() {
        val visible = mutableStateOf(false)
        compose.setContent {
            Box(Modifier.followFocusVisibility(visible).backKeys({ false }, onKeyboardBack = { visible.value = true })) {
                SkyButton("Card", {})
            }
        }
        compose.onNodeWithText("Card").requestFocus()
        focused().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertFalse(visible.value)
    }

    /* ---------- A new actor page takes focus; every search clears on Esc first; Esc in a menu ---------- */

    @Test fun anActorPageThatOpensFromTheGridTakesFocusAndEscGoesBack() {
        // Opening the page disposes the focused card; without a new focus Esc and Alt+Left would reach nothing.
        val f = folders()
        val actors = listOf("ada", "bea").map { ActorRef(it, it.uppercase(), "vset:1:$it", "set:1|$it", emptyList()) }
        var group by mutableStateOf<String?>(null)
        var backs = 0
        compose.setContent {
            Page {
                Box(Modifier.size(1000.dp, 700.dp).backKeys({ backs++; group = null; true }, onKeyboardBack = {})) {
                    ActorSwitch(group) { shown, _ ->
                        if (shown == null) {
                            TileGrid(numbered(6), remember { LazyGridState() }, f.organizer, "set:1", emptyMap(), { _, _ -> }, Modifier.fillMaxSize())
                        } else {
                            ActorPage(actors.first { it.identity == shown }, actors, f, onOpenActor = {}, onBack = {}, menuOf = { _, _, _ -> emptyList() })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Folder 000").requestFocus()
        compose.runOnIdle { group = "ada" }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty(), "nothing has focus on the new page")
        // The actor has no videos, so focus is on the header's Back.
        compose.onNodeWithText("Back").assertIsFocused()
        focused().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    @Test fun aGridThatTakesFocusOnStartFocusesTheFirstCardInViewWithoutAScroll() {
        // An actor page's grid: its first card fully in view, never one the scroll would have to bring in.
        val state = LazyGridState(firstVisibleItemIndex = 40, firstVisibleItemScrollOffset = 25)
        compose.setContent {
            Page { TileGrid(numbered(300), state, organizer(), "own:1", emptyMap(), { _, _ -> }, focusOnStart = true) }
        }
        compose.waitForIdle()
        val before = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        val fullyInView = state.layoutInfo.visibleItemsInfo.first { it.offset.y >= state.layoutInfo.viewportStartOffset }.index
        assertEquals("Folder %03d".format(fullyInView), focusedText())
        assertEquals(before, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
    }

    /** A narrow actor page (‹ › beside the name, no actor list) for [ids], each actor with one video; › opens the next. */
    private fun narrowActorPage(ids: List<String>): List<ActorRef> {
        val f = folders()
        val actors = ids.map { id ->
            val clip = WindowsMediaEntry("\\\\192.0.2.10\\videos\\$id clip.mkv", "s", "PC", "Videos", 1, 1)
            ActorRef(id, id.uppercase(), "vset:1:$id", "set:1|$id", listOf(clip))
        }
        var shown by mutableStateOf(ids.first())
        compose.setContent {
            Page {
                Box(Modifier.size(700.dp, 700.dp)) {
                    ActorPage(actors.first { it.identity == shown }, actors, f, onOpenActor = { shown = it }, onBack = {}, menuOf = { _, _, _ -> emptyList() })
                }
            }
        }
        compose.waitForIdle()
        return actors
    }

    @Test fun nextActorToTheLastOneLeavesTheFocusOnTheFirstVideoCard() {
        // At the last actor › turns off and loses the focus; the page takes it again, as when it opens.
        val actors = narrowActorPage(listOf("ada", "bea"))
        compose.onNodeWithText("Next actor").requestFocus()
        focused().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(actors[1].videos.first().title(), focusedText())
    }

    @Test fun nextActorThatStaysOnKeepsTheFocus() {
        narrowActorPage(listOf("ada", "bea", "cid"))
        compose.onNodeWithText("Next actor").requestFocus()
        focused().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.onNodeWithText("Next actor").assertIsFocused()
    }

    @Test fun clickingNextActorWhileAVideoCardHasTheFocusLeavesItOnTheNewFirstCard() {
        // As the screen test and a mouse do it: the card keeps the focus while › is clicked, and goes with the grid.
        val actors = narrowActorPage(listOf("ada", "bea"))
        compose.onNodeWithText(actors[0].videos.first().title()).requestFocus()
        compose.onNodeWithText("Next actor").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(actors[1].videos.first().title(), focusedText())
    }

    /**
     * ActorAutomationTest, step for step, on the real Folders screen of a Virtual folder: open an actor, Esc, open it
     * again, then click › [frames] frames later (null: once everything is idle). Returns the focused card's name.
     */
    private fun actorFlowOnTheFoldersScreen(frames: Int?): String? {
        showVirtualFolder()
        click("Ada Moss")
        compose.waitForIdle()
        focused().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        if (frames == null) {
            click("Ada Moss")
            compose.waitForIdle()
            click("Next actor")
        } else {
            compose.mainClock.autoAdvance = false
            click("Ada Moss")
            repeat(frames) { compose.mainClock.advanceTimeByFrame() }
            click("Next actor")
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        return focusedText()
    }

    /** The screen test's click: the button's own click action, as the accessibility action runs it. */
    private fun click(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
    }

    /** The real Folders screen of a Virtual folder of four videos, two by Ada Moss and two by Bea Lund. */
    private fun showVirtualFolder() {
        val library = folder.resolve("Library").toFile().apply { mkdirs() }
        FolderOrganizerStore(folder.resolve("folders.bin")).save(
            OrganizerData(sets = listOf(VirtualSet(id = "test-set", name = "Mix", folders = listOf(library.absolutePath)))),
        )
        val organizer = organizer()
        val model = LibraryModel(WindowsMediaIndex(folder.resolve("media-index.bin")))
        model.all = listOf("Ada Moss - one.mp4", "Bea Lund - one.mp4", "Ada Moss - two.mkv", "Bea Lund - two.mkv").mapIndexed { i, name ->
            WindowsMediaEntry(File(library, name).absolutePath, "local", "PC", "Library", 1, 1_000L + i)
        }
        val hub = HubState().apply { tab = 1; setId = "test-set" }
        compose.setContent {
            Page {
                Box(Modifier.size(800.dp, 650.dp)) {
                    FoldersScreen(model, organizer, hub, emptyMap(), { _, _ -> }, {}, { _, _, _, _ -> }, {}, { _, _ -> })
                }
            }
        }
    }

    @Test fun anActorOpenedAgainAsSoonAsItsCardHasTheFocusAfterEscTakesTheFocusToo() {
        // The screen test's pace: Esc, then the actor is opened again the moment its card has the focus, while the page
        // it left is still fading out.
        showVirtualFolder()
        click("Ada Moss")
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        focused().performKeyInput { pressKey(Key.Escape) }
        var frames = 0
        while (focusedText() != "Ada Moss" && frames < 60) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        assertEquals("Ada Moss", focusedText(), "Ada's card has the focus after Esc")
        focused().performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("Ada Moss - two", focusedText())
    }

    @Test fun theScreenTestsActorFlowKeepsTheFocusOnTheNextActorsFirstCard() {
        assertEquals("Bea Lund - two", actorFlowOnTheFoldersScreen(null))
    }

    @Test fun theScreenTestsActorFlowWithTheClickDuringTheOpeningKeepsTheFocusOnTheNextActorsFirstCard() {
        assertEquals("Bea Lund - two", actorFlowOnTheFoldersScreen(3))
    }

    /** A narrow page of one actor whose videos [videos] can change while it shows. */
    private fun changingActorPage(videos: () -> List<WindowsMediaEntry>) {
        val f = folders()
        compose.setContent {
            Page {
                Box(Modifier.size(700.dp, 700.dp)) {
                    val actor = ActorRef("ada", "ADA", "vset:1:ada", "set:1|ada", videos())
                    ActorPage(actor, listOf(actor), f, onOpenActor = {}, onBack = {}, menuOf = { _, _, _ -> emptyList() })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun clip(name: String, modified: Long) = WindowsMediaEntry("\\\\192.0.2.10\\videos\\$name.mkv", "s", "PC", "Videos", 1, modified)

    @Test fun aFocusedCardThatLeavesTheGridLateLeavesTheFocusOnACardInView() {
        // However late the focused card goes (here its video leaves the actor a second later), the focus comes back.
        val one = clip("ada one", 1)
        val two = clip("ada two", 2)
        var videos by mutableStateOf(listOf(one, two))
        changingActorPage { videos }
        assertEquals("ada two", focusedText())
        compose.mainClock.advanceTimeBy(1_000)
        videos = listOf(one)
        compose.waitForIdle()
        assertEquals("ada one", focusedText())
    }

    @Test fun focusThatMovedOnPurposeStaysWhenACardLeaves() {
        // The search field has the focus (as when typing filters the cards): a card that goes takes nothing with it.
        val one = clip("ada one", 1)
        val two = clip("ada two", 2)
        var videos by mutableStateOf(listOf(one, two))
        changingActorPage { videos }
        compose.onNodeWithContentDescription("Search here").requestFocus()
        compose.waitForIdle()
        videos = listOf(one)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Search here").assertIsFocused()
    }

    @Test fun escInAGridPagesSearchClearsItFirstThenGoesBack() {
        val organizer = organizer()
        val id = organizer.createOwn("Mix")!!
        val hub = HubState().apply { ownId = id; query = "ab" }
        val library = LibraryModel(WindowsMediaIndex(folder.resolve("media-index.bin")))
        compose.setContent {
            Page { FoldersScreen(library, organizer, hub, emptyMap(), { _, _ -> }, {}, { _, _, _, _ -> }, {}, { _, _ -> }) }
        }
        compose.onNodeWithContentDescription("Search here").requestFocus()
        focused().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals("" to id, hub.query to hub.ownId)
        compose.onNodeWithContentDescription("Search here").assertIsFocused()
        focused().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertNull(hub.ownId)
    }

    @Test fun escInACardMenuClosesTheMenuAndDoesNotGoBack() {
        var backs = 0
        val tile = FolderTile("dir:1", "Folder one", "", null, emptyList(), onOpen = {}, menu = { listOf(MenuAction("Open here", SkyIcon.Folder) {}) })
        compose.setContent {
            Page {
                Box(Modifier.backKeys({ backs++; true }, onKeyboardBack = {})) {
                    TileGrid(listOf(tile), remember { LazyGridState() }, organizer(), "own:1", emptyMap(), { _, _ -> }, Modifier.fillMaxSize())
                }
            }
        }
        compose.onNodeWithText("Folder one").requestFocus()
        focused().performKeyInput { pressKey(Key(java.awt.event.KeyEvent.VK_CONTEXT_MENU)) }
        compose.waitForIdle()
        compose.onNodeWithText("Open here").assertExists()
        compose.onNodeWithText("Open here").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        compose.onNodeWithText("Open here").assertDoesNotExist()
        assertEquals(0, backs)
    }

    @Test fun theCardsPictureTakesThePictureModifier() {
        compose.setContent {
            Page { FolderCard("Name", "", null, emptyList(), {}, actor = true, initials = "N", pictureModifier = Modifier.testTag("picture")) }
        }
        // The card merges its parts into one button, so the picture is a node of the unmerged tree.
        compose.onNodeWithTag("picture", useUnmergedTree = true).assertExists()
    }

    /* ---------- The switch between the grid and an actor page ---------- */

    @Test fun actorToActorKeepsThePageAndGridToPageMakesANewOne() {
        var group by mutableStateOf<String?>("A")
        val tokens = HashMap<String?, Any>()
        compose.setContent {
            ActorSwitch(group, animate = true) { shown, _ ->
                val token = remember { Any() }
                tokens[shown] = token
            }
        }
        compose.waitForIdle()
        group = "B"
        compose.waitForIdle()
        assertSame(tokens["A"], tokens["B"])
        group = null
        compose.waitForIdle()
        assertNotSame(tokens["B"], tokens[null])
    }

    @Test fun onlyTheIncomingContentIsEntering() {
        var group by mutableStateOf<String?>("A")
        val entering = HashMap<String?, Boolean>()
        compose.setContent {
            ActorSwitch(group, animate = true) { shown, isEntering -> entering[shown] = isEntering }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        group = null
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(true, entering[null])
        assertEquals(false, entering["A"])
        compose.mainClock.autoAdvance = true
    }

    @Test fun thePhotoMovesOnlyWithAnimations() {
        val moves = HashMap<Boolean, Boolean>()
        compose.setContent {
            Column {
                ActorSwitch("A", animate = true) { _, _ -> moves[true] = LocalPhotoMove.current != null }
                ActorSwitch("A", animate = false) { _, _ -> moves[false] = LocalPhotoMove.current != null }
            }
        }
        compose.waitForIdle()
        assertEquals(true, moves[true])
        assertEquals(false, moves[false])
    }

    /* ---------- Helpers ---------- */

    private fun focused() = compose.onNode(isFocused())

    private fun organizer() = Organizer(FolderOrganizerStore(folder.resolve("folders.bin")))

    private val views = LibraryViews(LibraryViewStore(null), FolderPrefs())

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalLibraryViews provides views) { content() }
    }

    private fun grid(tiles: List<Tile>, state: LazyGridState, focusKey: String? = null, onFocused: () -> Unit = {}) {
        val organizer = organizer()
        compose.setContent {
            Page { TileGrid(tiles, state, organizer, "own:1", emptyMap(), { _, _ -> }, Modifier.fillMaxSize(), focusKey = focusKey, onFocused = onFocused) }
        }
    }

    private fun folderTile(i: Int, title: String) = FolderTile("dir:$i", title, "", null, emptyList(), onOpen = {})

    private fun numbered(count: Int, from: Int = 0) = (from until from + count).map { folderTile(it, "Folder %03d".format(it)) }

    private fun folders() = Folders(
        LibraryModel(WindowsMediaIndex(folder.resolve("media-index.bin"))),
        organizer(), HubState(), emptyMap(), { _, _ -> }, {}, { _, _, _, _ -> }, {}, { _, _ -> }, LibraryMarks(),
        CoroutineScope(Dispatchers.Unconfined), null, emptyList(), FolderDialogs(), null, false, views,
    )

    private fun actorTile(f: Folders, identity: String) = f.actorTile(
        ActorEntry(VirtualGroup(identity, identity.uppercase(), emptyList()), ActorInfo("", false)),
        "set:1", "cover", "Set", ActorRemoval.HIDE, onOpen = {}, rename = null, remove = {},
    )
}
