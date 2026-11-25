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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
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

    @Test fun aKeyWithNoCardIsClearedAndFocusStays() {
        var key by mutableStateOf<String?>(null)
        var cleared = 0
        val tiles = numbered(40)
        compose.setContent {
            Page { TileGrid(tiles, remember { LazyGridState() }, organizer(), "own:1", emptyMap(), { _, _ -> }, focusKey = key, onFocused = { cleared++ }) }
        }
        compose.onNodeWithText("Folder 001").requestFocus()
        key = "group:gone"
        compose.waitForIdle()
        compose.onNodeWithText("Folder 001").assertIsFocused()
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
