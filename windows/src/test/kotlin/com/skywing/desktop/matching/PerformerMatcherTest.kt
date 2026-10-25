package com.skywing.desktop.matching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerformerMatcherTest {
    private val melody = PerformerRecord("sample", "1", "Melody Marks")
    private val cassie = PerformerRecord("sample", "2", "Cassie2Sassi")
    private val matcher = PerformerMatcher(listOf(melody, cassie))

    @Test fun dottedFilenameWithExactName() {
        val result = matcher.match("hanging.out.with.melody.marks.during.winter.break.mp4")
        assertEquals("Melody Marks", result.first().performer.name)
        assertEquals(MatchKind.EXACT, result.first().kind)
        assertTrue(result.first().automatic)
    }

    @Test fun oneLetterTypoRequiresReview() {
        val result = matcher.match("hanging.out.with.melody.mark.during.winter.brake.mp4")
        assertEquals(MatchKind.FUZZY_REVIEW, result.first().kind)
        assertFalse(result.first().automatic)
    }

    @Test fun unusualDigitName() {
        assertEquals("Cassie2Sassi", matcher.match("cassie2sassi.scene.mkv").first().performer.name)
    }

    @Test fun compactNameRequiresReview() {
        val result = matcher.match("hangoutwithmelodymarks.mp4")
        assertEquals(MatchKind.COMPACT_REVIEW, result.first().kind)
        assertFalse(result.first().automatic)
    }

    @Test fun noGuessForUnknownNames() {
        assertTrue(matcher.match("hanging.with.random.person.mp4").isEmpty())
    }

    @Test fun duplicateCanonicalNameIsNotAutoApplied() {
        val matches = PerformerMatcher(listOf(melody, melody.copy(provider = "other", id = "99")))
            .match("melody.marks.scene.mp4")
        assertTrue(matches.none { it.automatic })
    }
}
