package com.deepak.videolibrary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeptAssignmentsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store(now: Long = 1_000_000L) = KeptAssignments(folder.root.resolve("kept.json")) { now }

    @Test fun samePathGetsItsFolderBackOnce() {
        val kept = store()
        kept.remember(listOf(AssignmentRow("Films/a.mkv", "a.mkv", 10, "Favourites")))
        assertEquals("Favourites", kept.claim("Films/a.mkv", "a.mkv", 10))
        assertNull(kept.claim("Films/a.mkv", "a.mkv", 10))
    }

    @Test fun caseOnlyRenameMatches() {
        val kept = store()
        kept.remember(listOf(AssignmentRow("Films/A.mkv", "A.mkv", 10, "Favourites")))
        assertEquals("Favourites", kept.claim("films/a.mkv", "a.mkv", 10))
    }

    @Test fun renamedFileMatchesByNameAndSizeOnlyWhenUnique() {
        val kept = store()
        kept.remember(listOf(AssignmentRow("Old/a.mkv", "a.mkv", 10, "One")))
        assertEquals("One", kept.claim("New/a.mkv", "a.mkv", 10))
        kept.remember(listOf(AssignmentRow("X/b.mkv", "b.mkv", 5, "Two"), AssignmentRow("Y/b.mkv", "b.mkv", 5, "Three")))
        // Two remembered files with this name and size: no guess.
        assertNull(kept.claim("Z/b.mkv", "b.mkv", 5))
        assertNull(kept.claim("Z/c.mkv", "c.mkv", 5))
    }

    @Test fun survivesRestartAndForgetsOldEntries() {
        store(now = 1_000L).remember(listOf(AssignmentRow("a.mkv", "a.mkv", 1, "G")))
        assertEquals(1, store(now = 2_000L).size())
        assertEquals(0, store(now = 1_000L + KeptAssignments.MAX_AGE_MS + 1).size())
    }

    @Test fun damagedFileIsIgnored() {
        folder.root.resolve("kept.json").writeText("not json")
        assertNull(store().claim("a.mkv", "a.mkv", 1))
    }
}
