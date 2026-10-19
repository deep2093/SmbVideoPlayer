package com.deepak.videolibrary.ui

import com.deepak.videolibrary.data.VideoEntity
import org.junit.Assert.*
import org.junit.Test

class LibraryPresentationTest {
    private fun video(path: String, name: String = path, added: Long = 0, size: Long = 0) = VideoEntity(
        relativePath = path,
        name = name,
        parentDir = "",
        size = size,
        modified = 0,
        groupKey = "",
        lastSeenSession = 0,
        addedAt = added,
    )

    @Test fun originalFolderLabelsStopAtFirstDelimiterAndPreserveCase() {
        assertEquals("Tom Cruise", originalFolderLabel("Movies/Tom Cruise- movie"))
        assertEquals("Tom Cruise", originalFolderLabel("Movies/Tom Cruise, Brad Pitt- movie"))
        assertEquals("Help Wanted", originalFolderLabel("1/Allherluv/Help Wanted"))
        assertEquals("Actor 2", originalFolderLabel("Actor 2"))
        assertEquals("tom", originalFolderLabel("tom-cruise, other"))
    }

    @Test fun legacyPresetsMigrateWithoutChangingColumnCount() {
        listOf("2×2", "2×3", "3×3", "3×4", "4×3", "4×4", "5×4", "5×5").forEach {
            assertEquals(it.substringBefore('×'), LibraryLayout.normalize(it))
        }
        assertEquals("2", LibraryLayout.normalize(null, "2"))
        assertEquals("3", LibraryLayout.normalize("invalid"))
        LibraryLayout.presets.forEach { assertEquals(it.toInt(), LibraryLayout.columns(it)) }
        assertEquals(0, LibraryLayout.presets.size % 2)
    }

    @Test fun continueExcludesHiddenUnstartedAndCompletedMedia() {
        assertFalse(canContinue(15_000, 120_000, false))
        assertTrue(canContinue(15_001, 120_000, false))
        assertFalse(canContinue(60_000, 120_000, true))
        assertFalse(canContinue(110_000, 120_000, false))
        assertTrue(canContinue(60_000, 0, false)) // Duration not discovered yet.
        assertFalse(canContinue(120_001, 120_000, false))
    }

    @Test fun sortMatchesQueueOrderAndDoesNotMutateInput() {
        val input = listOf(video("b", "Beta", added = 20, size = 5), video("a", "alpha", added = 10, size = 50))
        assertEquals(listOf("a", "b"), VideoSort.NAME.arrange(input, false).map { it.relativePath })
        assertEquals(listOf("b", "a"), VideoSort.NEWEST.arrange(input, true).map { it.relativePath })
        assertEquals(listOf("a", "b"), VideoSort.SIZE.arrange(input, true).map { it.relativePath })
        assertEquals("b", input.first().relativePath)
    }

    @Test fun equalNamesHaveDeterministicOrdering() {
        val input = listOf(video("z/movie.mp4", "Movie"), video("a/movie.mp4", "movie"))
        assertEquals(
            "a/movie.mp4",
            VideoSort.NAME
                .arrange(input, false)
                .first()
                .relativePath,
        )
    }
}
