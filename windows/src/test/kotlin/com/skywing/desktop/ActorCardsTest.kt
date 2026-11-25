package com.skywing.desktop

import androidx.compose.ui.input.key.Key
import com.skywing.desktop.TileKind.ACTOR
import com.skywing.desktop.TileKind.FOLDER
import com.skywing.desktop.TileKind.VIDEO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actor pages: the words and numbers on actor cards, the folders behind them, and what a selection offers. */
class ActorCardsTest {
    private fun video(path: String, size: Long = 1, modified: Long = 1) = WindowsMediaEntry(path, "s", "PC", "Videos", size, modified)

    @Test fun initialsOfTwoWords() {
        assertEquals("OP", actorInitials("Owen Pike"))
        assertEquals("OM", actorInitials("owen mark pike"))
        assertEquals("O", actorInitials("Owen"))
    }

    @Test fun initialsSkipLeadingNumbers() {
        assertEquals("F", actorInitials("2 Fast"))
    }

    @Test fun initialsWithoutLettersAreHash() {
        assertEquals("#", actorInitials("123"))
        assertEquals("#", actorInitials("🎬"))
        assertEquals("#", actorInitials("   "))
    }

    @Test fun detailLines() {
        assertEquals("12 videos · 3 new", actorDetail(12, 3, 8))
        assertEquals("12 videos · 8 watched", actorDetail(12, 0, 8))
        assertEquals("1 video", actorDetail(1, 0, 0))
        assertEquals("12 videos", actorDetail(12, 0, 0))
    }

    @Test fun actorFoldersMostFirstAndCapped() {
        val videos = (0 until 12).flatMap { folder -> (0..folder).map { video("C:\\Clips\\f$folder\\v$it.mp4") } }
        val folders = actorFolders(videos)
        assertEquals(10, folders.size)
        assertEquals("C:\\Clips\\f11" to 12, folders.first())
        assertEquals("C:\\Clips\\f2" to 3, folders.last())
        assertEquals(folders.map { it.second }.sortedDescending(), folders.map { it.second })
    }

    @Test fun actorFoldersJoinsTheSameFolderWhateverItsCase() {
        val folders = actorFolders(listOf(video("C:\\Clips\\A\\1.mp4"), video("c:\\clips\\a\\2.mp4"), video("\\\\192.0.2.5\\share\\x\\3.mp4")))
        assertEquals(listOf("C:\\Clips\\A" to 2, "\\\\192.0.2.5\\share\\x" to 1), folders)
    }

    @Test fun actorsOnlySelection() {
        assertEquals(listOf("Play all", "Shuffle", "Hide"), selectionActionsFor(setOf(ACTOR), custom = false))
        assertEquals(listOf("Play all", "Shuffle", "Take out…"), selectionActionsFor(setOf(ACTOR), custom = true))
    }

    @Test fun mixedSelectionKeepsTakeOut() {
        val labels = selectionActionsFor(setOf(ACTOR, VIDEO), custom = false)
        assertTrue("Play" in labels)
        assertTrue("Take out" in labels)
        assertFalse("Hide" in labels)
        assertTrue("Take out" in selectionActionsFor(setOf(FOLDER), custom = false))
        assertTrue("Take out" in selectionActionsFor(setOf(ACTOR, FOLDER), custom = true))
    }

    @Test fun anEmptySelectionOffersTheUsualActions() {
        assertTrue("Take out" in selectionActionsFor(emptySet(), custom = false))
    }

    @Test fun theDotAndTheCountComeFromTheMarks() {
        val now = 10_000_000L
        val fresh = video("C:\\Clips\\a.mp4")
        val old = video("C:\\Clips\\b.mp4")
        val marks = LibraryMarks(
            watched = setOf("c:\\clips\\b.mp4"),
            news = NewMarks(firstSeen = mapOf("c:\\clips\\a.mp4" to now - 1000), opened = emptyMap()),
        )
        assertEquals(ActorInfo("2 videos · 1 new", dot = true), actorInfo("set:1|x", listOf(fresh, old), marks, now))
        // Once the actor page was opened after the video arrived, the dot goes but "1 new" stays.
        val opened = marks.copy(news = marks.news.copy(opened = mapOf("set:1|x" to now)))
        assertEquals(ActorInfo("2 videos · 1 new", dot = false), actorInfo("set:1|x", listOf(fresh, old), opened, now))
        assertEquals(ActorInfo("1 video · 1 watched", dot = false), actorInfo("set:1|x", listOf(old), marks, now))
    }

    @Test fun totalLengthIsShownOnlyWhenEveryLengthIsKnown() {
        val a = video("C:\\a.mp4")
        val b = video("C:\\b.mp4")
        val known = mapOf(a.path to 60.0, b.path to 3600.0)
        assertEquals(3660.0, actorTotalLength(listOf(a, b)) { known[it.path] })
        val partial = mapOf(a.path to 60.0)
        assertNull(actorTotalLength(listOf(a, b)) { partial[it.path] })
        assertNull(actorTotalLength(emptyList()) { 1.0 })
    }

    @Test fun detailsListTheFactsInOrder() {
        assertEquals(
            "Owen Pike\n3 videos · 1.5 KB · 1:01:00\nActor in Example\nA video listed in several subfolders is the same file, never a copy.",
            actorDetails("Owen Pike", 3, 1536, 3660.0, "Example"),
        )
        assertEquals(
            "Owen Pike\n1 video\nActor in Example\nA video listed in several subfolders is the same file, never a copy.",
            actorDetails("Owen Pike", 1, 0, null, "Example"),
        )
    }

    @Test fun lettersAreWantedOnlyForALongActorList() {
        assertFalse(lettersWanted(actors = 20, visible = 12))
        assertTrue(lettersWanted(actors = 25, visible = 12))
        assertFalse(lettersWanted(actors = 5, visible = 0))
    }

    @Test fun sectionsGetHeadingsOnlyWhenTwoHaveCards() {
        fun card(key: String) = FolderTile(key, key, "", null, emptyList(), {})
        val folder = card("a")
        val actor = card("b")
        val clip = VideoTile("c", video("C:\\c.mp4"), emptyList())
        assertEquals(listOf<Tile>(actor), sectioned(emptyList(), listOf(actor), emptyList()))
        assertEquals(
            listOf("label:folders", "a", "label:actors", "b", "label:videos", "c"),
            sectioned(listOf(folder), listOf(actor), listOf(clip)).map { it.key },
        )
        assertEquals(listOf("Actors", "Videos"), sectioned(emptyList(), listOf(actor), listOf(clip)).filterIsInstance<LabelTile>().map { it.title })
    }

    @Test fun keysTellTheirKind() {
        assertEquals(ACTOR, tileKindOf("group:owen pike"))
        assertEquals(VIDEO, tileKindOf("file:C:\\a.mp4"))
        assertEquals(FOLDER, tileKindOf("set:1"))
        assertEquals(FOLDER, tileKindOf("direct:\\\\192.0.2.5\\share"))
    }

    @Test fun ctrlFFocusesTheSearchBox() {
        assertTrue(isSearchKey(Key.F, ctrl = true, alt = false, shift = false))
        assertFalse(isSearchKey(Key.F, ctrl = false, alt = false, shift = false))
        assertFalse(isSearchKey(Key.F, ctrl = true, alt = true, shift = false))
        assertFalse(isSearchKey(Key.Equals, ctrl = true, alt = false, shift = false))
        // The View keys keep their own meaning.
        assertEquals(1, viewKeyAction(Key.Equals, ctrl = true, inTextField = false))
    }
}
