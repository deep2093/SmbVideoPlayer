package com.deepak.videolibrary.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartOneTest {
    @Test fun recognisesFirstParts() {
        listOf("movie.pt1.mp4", "movie pt.1.mp4", "movie pt 1.mp4", "Movie_Part1.mp4", "movie part 1.mp4", "Movie Part One.mp4", "movie-PT01.mp4")
            .forEach { assertTrue(it, PartOne.isFirstPart(it)) }
    }

    @Test fun ignoresOtherParts() {
        listOf("movie.pt2.mp4", "movie pt10.mp4", "movie part 12.mp4", "movie.mp4", "script1.mp4")
            .forEach { assertFalse(it, PartOne.isFirstPart(it)) }
    }

    @Test fun firstPartComesFirstAndOthersKeepOrder() {
        assertEquals(listOf("b pt1", "a", "c pt2"), PartOne.firstPartsFirst(listOf("a", "b pt1", "c pt2")) { it })
    }
}
