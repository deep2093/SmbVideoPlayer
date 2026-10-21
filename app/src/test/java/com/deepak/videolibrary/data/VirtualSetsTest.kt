package com.deepak.videolibrary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualSetsTest {
    private fun video(path: String, group: String? = null, size: Long = 1) = VideoEntity(
        relativePath = path, name = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/', ""),
        size = size, modified = 1, groupKey = "x", customGroup = group, lastSeenSession = 1, addedAt = 1,
    )

    private val set = VirtualSet("1", "Hollywood", listOf("Movies"))

    @Test fun oneVideoAppearsInEverySubfolderItNames() {
        val groups = VirtualGroups.build(set, listOf(video("Movies/tom cruise, brad pitt - a.mp4"), video("Movies/Brad Pitt - b.mp4")))
        assertEquals(listOf("Brad Pitt", "Tom Cruise"), VirtualGroups.sorted(groups, "NAME", false).map { it.title })
        assertEquals(2, groups.first { it.title == "Brad Pitt" }.videos.size)
        assertEquals(1, groups.first { it.title == "Tom Cruise" }.videos.size)
    }

    @Test fun namesThatDifferOnlyByCaseAreOneFolderAndNothingIsDuplicated() {
        val groups = VirtualGroups.build(set, listOf(video("Movies/TOM CRUISE - a.mp4"), video("Movies/tom cruise - b.mp4")))
        assertEquals(1, groups.size)
        assertEquals("Tom Cruise", groups.single().title)
        assertEquals(2, groups.single().videos.size)
    }

    @Test fun yourRenamesAndHiddenFoldersSurviveARescan() {
        val edited = set.copy(renames = mapOf("tom cruise" to "Tommy"), hidden = setOf("brad pitt"))
        val groups = VirtualGroups.build(edited, listOf(video("Movies/Tom Cruise, Brad Pitt - a.mp4")))
        assertEquals(listOf("Tommy"), groups.map { it.title })
        // Renaming to another folder's name joins the two.
        val merged = VirtualGroups.build(set.copy(renames = mapOf("brad pitt" to "Tom Cruise")), listOf(video("Movies/Tom Cruise - a.mp4"), video("Movies/Brad Pitt - b.mp4")))
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().videos.size)
    }

    @Test fun aVideoFiledByHandStaysOnlyInThatFolder() {
        val groups = VirtualGroups.build(set, listOf(video("Movies/Tom Cruise, Brad Pitt - a.mp4", group = "Favourites")))
        assertEquals(listOf("Favourites"), groups.map { it.title })
    }

    @Test fun sortingByCountAndSize() {
        val groups = VirtualGroups.build(set, listOf(video("Movies/A - 1.mp4", size = 5), video("Movies/B - 1.mp4", size = 1), video("Movies/B - 2.mp4", size = 1)))
        assertEquals("B", VirtualGroups.sorted(groups, "COUNT", true).first().title)
        assertEquals("A", VirtualGroups.sorted(groups, "SIZE", true).first().title)
    }

    @Test fun storeKeepsEverythingThroughJson() {
        val original = listOf(VirtualSet("a", "Hollywood", listOf("Movies/Action", ""), mapOf("tom cruise" to "Tommy"), setOf("brad pitt"), listOf("Downloads/Tom Cruise - x.mp4")), VirtualSet("b", "Empty"))
        val back = VirtualSetStore.decode(VirtualSetStore.encode(original))
        assertEquals(original, back)
        assertTrue(VirtualSetStore.decode("not json").isEmpty())
        assertTrue(VirtualSetStore.decode(null).isEmpty())
    }
}
