package com.deepak.videolibrary.thumbs

import org.junit.Assert.assertEquals
import org.junit.Test

class KeptArtworkTest {
    @Test fun aVideoThatComesBackWithANewIdGetsItsChoicesBack() {
        val ledger = mapOf(5L to "Movies/a.mp4", 6L to "Movies/b.mp4", 7L to "Movies/gone.mp4")
        val live = mapOf("Movies/a.mp4" to 42L, "Movies/b.mp4" to 6L)
        // a.mp4 returned as 42; b.mp4 never left; gone.mp4 is still away and is kept for later.
        assertEquals(listOf(5L to 42L), KeptArtwork.returning(ledger, setOf(6L, 42L), live))
    }

    @Test fun ledgerSurvivesJson() {
        val map = mapOf(1L to "A/b c.mp4", 2L to "x.mkv")
        assertEquals(map, KeptArtwork.decode(KeptArtwork.encode(map)))
        assertEquals(emptyMap<Long, String>(), KeptArtwork.decode("broken"))
    }
}
