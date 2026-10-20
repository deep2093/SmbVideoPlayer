package com.deepak.videolibrary.ui

import com.deepak.videolibrary.smb.SmbEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerSortTest {
    private fun hit(name: String, dir: Boolean = false, size: Long = 0, modified: Long = 0) =
        ExplorerHit("root/$name", SmbEntry(name, dir, size, modified))

    private val hits = listOf(
        hit("b.mp4", size = 30, modified = 1),
        hit("Zeta", dir = true, modified = 9),
        hit("a.mkv", size = 10, modified = 3),
        hit("alpha", dir = true, modified = 2),
        hit("c.avi", size = 20, modified = 2),
    )

    private fun names(list: List<ExplorerHit>) = list.map { it.entry.name }

    @Test fun nameAscendingKeepsFoldersFirstAndIgnoresCase() {
        assertEquals(listOf("alpha", "Zeta", "a.mkv", "b.mp4", "c.avi"), names(sortExplorerHits(hits, "NAME", false)))
    }

    @Test fun nameDescendingStillKeepsFoldersFirst() {
        assertEquals(listOf("Zeta", "alpha", "c.avi", "b.mp4", "a.mkv"), names(sortExplorerHits(hits, "NAME", true)))
    }

    @Test fun sizeAndDateSortFilesWithinTheirGroup() {
        assertEquals(listOf("alpha", "Zeta", "a.mkv", "c.avi", "b.mp4"), names(sortExplorerHits(hits, "SIZE", false)))
        assertEquals(listOf("Zeta", "alpha", "a.mkv", "c.avi", "b.mp4"), names(sortExplorerHits(hits, "DATE", true)))
    }

    @Test fun typeSortsByExtension() {
        assertEquals(listOf("alpha", "Zeta", "c.avi", "a.mkv", "b.mp4"), names(sortExplorerHits(hits, "TYPE", false)))
    }

    @Test fun sectionLettersUseTheFirstLetterOrHash() {
        assertEquals("A", sectionLetter("abigail morris"))
        assertEquals("Z", sectionLetter("  zeta.mp4"))
        assertEquals("#", sectionLetter("19th birthday"))
        assertEquals("B", sectionLetter("_book club"))
        assertEquals("#", sectionLetter(""))
    }
}
