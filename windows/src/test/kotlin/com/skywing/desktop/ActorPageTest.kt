package com.skywing.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actor pages: what Resume picks, how the videos sort, the words of the header, its fold, and the actor list. */
class ActorPageTest {
    private fun video(path: String, modified: Long = 1) = WindowsMediaEntry(path, "s", "PC", "Videos", 1, modified)

    private fun record(entry: WindowsMediaEntry, position: Double, duration: Double, updated: Long) =
        WindowsWatchRecord(entry.path, position, duration, updated)

    private fun history(vararg records: WindowsWatchRecord) = records.associateBy { it.path.lowercase() }

    private fun actor(title: String) = ActorRef(title.lowercase(), title, "vset:s:" + title.lowercase(), "set:s|" + title.lowercase(), emptyList())

    @Test fun resumePicksMostRecentHalfWatched() {
        val older = video("C:\\Clips\\Older.mp4")
        val newer = video("C:\\Clips\\Newer.mp4")
        val finished = video("C:\\Clips\\Finished.mp4")
        val choice = pickResume(
            listOf(older, newer, finished),
            history(record(older, 100.0, 1000.0, 10), record(newer, 400.0, 1000.0, 20), record(finished, 1000.0, 1000.0, 30)),
        )
        assertEquals(ResumeChoice.Resume(newer, 400.0, 600.0), choice)
    }

    @Test fun resumeIgnoresHistoryOfVideosNotInTheActor() {
        val mine = video("C:\\Clips\\Mine.mp4")
        val other = video("C:\\Clips\\Other.mp4")
        val choice = pickResume(listOf(mine), history(record(mine, 50.0, 500.0, 10), record(other, 50.0, 500.0, 99)))
        assertEquals(ResumeChoice.Resume(mine, 50.0, 450.0), choice)
    }

    @Test fun noHistoryGivesNewest() {
        val old = video("C:\\Clips\\Old.mp4", modified = 10)
        val new = video("C:\\Clips\\New.mp4", modified = 30)
        val middle = video("C:\\Clips\\Middle.mp4", modified = 20)
        assertEquals(ResumeChoice.Newest(new), pickResume(listOf(old, new, middle), emptyMap()))
    }

    @Test fun noVideosGivesNone() {
        assertEquals(ResumeChoice.None, pickResume(emptyList(), emptyMap()))
    }

    @Test fun upNextAfterCurrent() {
        val a = video("C:\\Clips\\A.mp4")
        val b = video("C:\\Clips\\B.mp4")
        val c = video("C:\\Clips\\C.mp4")
        assertEquals(b, upNext(listOf(a, b, c), a))
        assertEquals(c, upNext(listOf(a, b, c), b))
    }

    @Test fun upNextFirstWhenNoCurrent() {
        val a = video("C:\\Clips\\A.mp4")
        val b = video("C:\\Clips\\B.mp4")
        val gone = video("C:\\Clips\\Gone.mp4")
        assertEquals(a, upNext(listOf(a, b), null))
        assertEquals(a, upNext(listOf(a, b), gone))
    }

    @Test fun upNextNoneAfterLast() {
        val a = video("C:\\Clips\\A.mp4")
        val b = video("C:\\Clips\\B.mp4")
        assertEquals(null, upNext(listOf(a, b), b))
        assertEquals(null, upNext(emptyList(), null))
    }

    @Test fun longestPutsUnknownLast() {
        val short = video("C:\\Clips\\Short.mp4")
        val unknown = video("C:\\Clips\\Unknown.mp4")
        val long = video("C:\\Clips\\Long.mp4")
        val lengths = mapOf(short.path to 60.0, long.path to 3600.0)
        assertEquals(listOf(long, short, unknown), sortVideos(listOf(short, unknown, long), VideoSort.LONGEST) { lengths[it.path] })
    }

    @Test fun newestUsesModified() {
        val a = video("C:\\Clips\\A.mp4", modified = 10)
        val b = video("C:\\Clips\\B.mp4", modified = 30)
        val c = video("C:\\Clips\\C.mp4", modified = 20)
        assertEquals(listOf(b, c, a), sortVideos(listOf(a, b, c), VideoSort.NEWEST) { null })
    }

    @Test fun nameSortIgnoresCase() {
        val bravo = video("C:\\Clips\\bravo.mp4")
        val alpha = video("C:\\Clips\\Alpha.mp4")
        val charlie = video("C:\\Clips\\Charlie.mp4")
        assertEquals(listOf(alpha, bravo, charlie), sortVideos(listOf(charlie, bravo, alpha), VideoSort.NAME) { null })
    }

    @Test fun lineLeavesOutZeros() {
        assertEquals("24 videos · 9 watched · 2 new", actorLine(24, 9, 2))
        assertEquals("1 video", actorLine(1, 0, 0))
        assertEquals("3 videos · 1 new", actorLine(3, 0, 1))
        assertEquals("3 videos · 3 watched", actorLine(3, 3, 0))
    }

    @Test fun timeLeft() {
        assertEquals("23 min left", timeLeftText(1380.0))
        assertEquals("1 h 5 min left", timeLeftText(3900.0))
        assertEquals("under a minute left", timeLeftText(40.0))
        assertEquals("1 h left", timeLeftText(3600.0))
    }

    @Test fun foldValues() {
        assertEquals(0f, headerFold(0, 0, 200))
        assertEquals(0.5f, headerFold(0, 100, 200))
        assertEquals(1f, headerFold(1, 0, 200))
        assertEquals(1f, headerFold(0, 500, 200))
    }

    @Test fun filterIsCaseInsensitive() {
        val actors = listOf(actor("Owen Pike"), actor("Mara Vale"), actor("Ivo Pine"))
        assertEquals(listOf("Owen Pike", "Ivo Pine"), filterActors(actors, "PI").map { it.title })
        assertEquals(actors, filterActors(actors, "  "))
        assertTrue(filterActors(actors, "zz").isEmpty())
    }

    @Test fun neighboursAtTheEnds() {
        val actors = listOf(actor("Alpha"), actor("Bravo"), actor("Charlie"))
        assertEquals(null to actors[1], neighbours(actors, "alpha"))
        assertEquals(actors[0] to actors[2], neighbours(actors, "bravo"))
        assertEquals(actors[1] to null, neighbours(actors, "charlie"))
        assertEquals(null to null, neighbours(actors, "missing"))
    }

    @Test fun onActorPageFlag() {
        val hub = HubState()
        assertFalse(hub.onActorPage)
        hub.setId = "virtual"
        assertFalse(hub.onActorPage)
        hub.setGroup = "owen pike"
        assertTrue(hub.onActorPage)
        hub.drivePath = "C:\\Clips"
        assertFalse(hub.onActorPage)

        val own = HubState()
        own.ownId = "custom"
        own.ownGroup = "mara vale"
        assertTrue(own.onActorPage)
    }

    @Test fun photoKeyIsNamedByThePage() {
        assertEquals("actor-photo:set:s|owen pike", actorPhotoKey("set:s|owen pike"))
    }

    @Test fun playListIsTheGridList() {
        val first = video("C:\\Clips\\Harbour Walk.mp4")
        val seen = video("C:\\Clips\\Harbour Night.mp4")
        val other = video("C:\\Clips\\Market.mp4")
        val sorted = listOf(first, seen, other)
        val watched = setOf(seen.path.lowercase())
        // The current sort is kept; the search ignores case; Hide watched leaves out watched videos only when it is on.
        assertEquals(listOf(first, seen), actorPlayList(sorted, "harbour", hideWatched = false, watched = watched))
        assertEquals(listOf(first, other), actorPlayList(sorted, "", hideWatched = true, watched = watched))
        assertEquals(sorted, actorPlayList(sorted, "", hideWatched = false, watched = watched))
        assertTrue(actorPlayList(sorted, "beach", hideWatched = false, watched = watched).isEmpty())
    }

    @Test fun renameOnThePageFollowsTheActorUnlessItJoinsAnother() {
        val identities = listOf("owen pike", "mara vale")
        assertEquals("owen hale", followRename("owen pike", "owen hale", identities))
        // Joined to another actor: this page is gone, so the grid shows.
        assertEquals(null, followRename("owen pike", "mara vale", identities))
        // Only the case changed, or nothing was saved: the page stays as it is.
        assertEquals(null, followRename("owen pike", "owen pike", identities))
        assertEquals(null, followRename("owen pike", null, identities))
    }

    @Test fun emptyPlayListDisablesPlayAllAndShuffleInTheMenu() {
        val state = ActorMenuState("Example", true, false, true, listOf("C:\\Clips" to 3), ActorRemoval.HIDE)
        val actions = ActorMenuActions({}, {}, {}, {}, {}, {}, {}, {}, {})
        val off = actorMenu(state.copy(canPlay = false), actions).filter { it.label == "Play all" || it.label == "Shuffle" }
        assertEquals(listOf(false, false), off.map { it.enabled })
        val on = actorMenu(state, actions).filter { it.label == "Play all" || it.label == "Shuffle" }
        assertEquals(listOf(true, true), on.map { it.enabled })
    }
}
