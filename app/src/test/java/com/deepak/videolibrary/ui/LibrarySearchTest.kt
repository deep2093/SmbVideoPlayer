package com.deepak.videolibrary.ui

import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.smb.SmbEntry
import org.junit.Assert.*
import org.junit.Test

class LibrarySearchTest {
    private fun video(path: String, group: String = "films", id: Long = 0L) = VideoEntity(
        id = id, relativePath = path, name = path.substringAfterLast('/'), parentDir = path.substringBeforeLast('/', ""),
        size = 100, modified = 0, groupKey = group, lastSeenSession = 0, addedAt = 0)

    @Test fun matchesInsideNameIgnoringCaseAndOuterSpaces() {
        assertTrue(matchesFilename("2024 - Tom Cruise - scene.MP4", " cruise "))
        assertTrue(matchesFilename("A TOM CRUISE interview.mp4", "tom cruise"))
        assertFalse(matchesFilename("Cruising.mp4", "cruise"))
        assertTrue(matchesFilename("100%_fun.mp4", "%_"))
        assertFalse(matchesFilename("anything.mp4", "%"))
    }
    @Test fun folderSearchIncludesMatchingFilesAndAllVideosInMatchingFoldersWithoutDuplicates() {
        val a = video("a/Interview - Cruise.mp4", "interviews")
        val b = video("b/Top Gun.mp4", "Tom Cruise")
        val c = video("c/Other.mp4", "other")
        assertEquals(listOf(a, b), folderSearchVideos(listOf(a, b, c, a), "cruise"))
        assertEquals(emptyList<VideoEntity>(), folderSearchVideos(listOf(c), "cruise"))
    }
    @Test fun originalHierarchyUsesPathBoundariesAndKeepsSameNamedDirectoriesSeparate() {
        val a = video("Films/Action/One.mp4")
        val b = video("Films/Drama/Two.mp4")
        val c = video("FilmsExtra/Action/Three.mp4")
        assertEquals(listOf("Films/Action", "Films/Drama"), originalChildren(listOf(a,b,c), "Films").map { it.path })
        assertEquals(listOf("Films", "FilmsExtra"), originalChildren(listOf(a,b,c), "").map { it.path })
        assertFalse(isWithinFolder("FilmsExtra/Action", "Films"))
        assertTrue(isWithinFolder("Films/Action", "Films"))
        assertEquals(emptyList<OriginalFolder>(), originalChildren(listOf(a), "Films/Action"))
    }
    @Test fun explorerKeepsDistinctUnindexedVideosAndReusesIndexedMetadata() {
        val entry = SmbEntry("Cruise.mp4", false, 200, 1)
        val hits = listOf(ExplorerHit("one/Cruise.mp4", entry), ExplorerHit("two/Cruise.mp4", entry))
        val known = video(hits[0].path, id = 42)
        val queue = hits.map { explorerVideo(it, mapOf(known.relativePath to known)) }.distinctBy { it.relativePath }
        assertEquals(2, queue.size)
        assertEquals(42L, queue[0].id)
        assertEquals("two", queue[1].parentDir)
        assertEquals(200L, queue[1].size)
        assertTrue(isPlayable(entry))
        assertFalse(isPlayable(entry.copy(isDirectory = true)))
        assertFalse(isPlayable(entry.copy(name = "Cruise.txt")))
    }
}
