package com.skywing.desktop

import androidx.compose.ui.input.key.Key
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actor pages: the video menu made for a PC, and Ctrl/Shift-click selection. */
class CardMenusTest {
    private val noActions = VideoMenuActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun menu(state: VideoMenuState, actions: VideoMenuActions = noActions) = videoMenu(state, actions)

    private val half = VideoMenuState(resumeAt = 754.0, watched = false, artworkOn = true, pictureHint = "Automatic")

    @Test fun videoMenuHalfWatched() {
        val items = menu(half)
        assertEquals(
            listOf(
                "Resume (12:34)", "Play from start", "Mark as watched", "Change picture…", "Add to folder…",
                "Add to virtual folder…", "Rename file…", "Show in File Explorer", "Details", "Select",
            ),
            items.map { it.label },
        )
        assertEquals(
            listOf("Mark as watched", "Change picture…", "Show in File Explorer"),
            items.filter { it.dividerBefore }.map { it.label },
        )
        assertEquals("Enter", items.first().shortcut)
        assertEquals("F2", items.first { it.label == "Rename file…" }.shortcut)
        assertEquals("Alt+Enter", items.first { it.label == "Details" }.shortcut)
    }

    @Test fun videoMenuWatchedArtworkOff() {
        val items = menu(VideoMenuState(resumeAt = null, watched = true, artworkOn = false, pictureHint = "Automatic"))
        assertEquals("Play", items[0].label)
        assertEquals("Mark as unwatched", items[2].label)
        assertTrue(items.none { it.label == "Change picture…" })
        assertEquals(
            listOf("Mark as unwatched", "Add to folder…", "Show in File Explorer"),
            items.filter { it.dividerBefore }.map { it.label },
        )
    }

    @Test fun changePictureShowsItsSource() {
        assertEquals("Automatic", menu(half).first { it.label == "Change picture…" }.hint)
        assertEquals("Chosen", menu(half.copy(pictureHint = "Chosen")).first { it.label == "Change picture…" }.hint)
    }

    @Test fun menuItemsRunTheirActions() {
        val ran = mutableListOf<String>()
        val actions = VideoMenuActions(
            play = { ran += "play" }, playFromStart = { ran += "start" }, setWatched = { ran += "watched=$it" },
            changePicture = {}, addToFolder = {}, addToVirtual = {}, rename = { ran += "rename" },
            showInExplorer = {}, details = {}, select = {},
        )
        val items = menu(half, actions)
        items.first { it.label.startsWith("Resume") }.action()
        items.first { it.label == "Play from start" }.action()
        items.first { it.label == "Mark as watched" }.action()
        menu(half.copy(watched = true), actions).first { it.label == "Mark as unwatched" }.action()
        assertEquals(listOf("play", "start", "watched=true", "watched=false"), ran)
    }

    @Test fun shortcutFindsRename() {
        var renamed = false
        val items = menu(half, VideoMenuActions({}, {}, {}, {}, {}, {}, { renamed = true }, {}, {}, {}))
        shortcutAction(items, "F2")!!.action()
        assertTrue(renamed)
        assertNotNull(shortcutAction(items, "Alt+Enter"))
        assertNull(shortcutAction(items, "F9"))
    }

    @Test fun ctrlClickToggles() {
        val keys = listOf("a", "b", "c")
        assertEquals(setOf("a", "b"), clickSelection(keys, setOf("a"), "a", "b", ctrl = true, shift = false))
        assertEquals(emptySet(), clickSelection(keys, setOf("a"), "a", "a", ctrl = true, shift = false))
    }

    @Test fun shiftClickSelectsRange() {
        assertEquals(
            setOf("b", "c", "d"),
            clickSelection(listOf("a", "b", "c", "d"), setOf("b"), "b", "d", ctrl = false, shift = true),
        )
        assertEquals(
            setOf("b", "c"),
            clickSelection(listOf("a", "b", "c", "d"), emptySet(), "c", "b", ctrl = false, shift = true),
        )
        assertEquals(setOf("c"), clickSelection(listOf("a", "b", "c"), emptySet(), null, "c", ctrl = false, shift = true))
    }

    @Test fun plainClickIsNull() {
        assertNull(clickSelection(listOf("a"), emptySet(), null, "a", ctrl = false, shift = false))
    }

    @Test fun explorerSelectsAShareFile() {
        assertEquals(
            listOf("explorer.exe", "/select,\\\\192.0.2.10\\videos\\a b.mkv"),
            explorerCommand(File("\\\\192.0.2.10\\videos\\a b.mkv"), true),
        )
        assertEquals(
            listOf("explorer.exe", "\\\\192.0.2.10\\videos"),
            explorerCommand(File("\\\\192.0.2.10\\videos"), false),
        )
    }

    @Test fun detailsAddLengthAndWatched() {
        assertEquals("path\n1.0 KB\nSource", videoDetails(listOf("path", "1.0 KB", "Source"), null, false))
        assertEquals(
            "path\nSource\nLength: 1:02:03\nWatched",
            videoDetails(listOf("path", "Source"), 3723.0, true),
        )
    }

    @Test fun cardKeysMapToMenuShortcuts() {
        assertEquals("F2", cardKeyShortcut(Key.F2, alt = false, shift = false))
        assertEquals("Alt+Enter", cardKeyShortcut(Key.Enter, alt = true, shift = false))
        assertEquals(MENU_KEY, cardKeyShortcut(Key.F10, alt = false, shift = true))
        assertEquals(MENU_KEY, cardKeyShortcut(Key.Menu, alt = false, shift = false))
        assertNull(cardKeyShortcut(Key.Enter, alt = false, shift = false))
        assertNull(cardKeyShortcut(Key.F10, alt = false, shift = false))
    }
}
