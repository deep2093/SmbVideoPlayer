package com.deepak.videolibrary.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualNamesTest {
    @Test fun commasBeforeTheFirstHyphenMakeOneFolderEach() {
        assertEquals(listOf("Tom Cruise", "Brad Pitt", "Leonardo Dicaprio"), VirtualNames.of("tom cruise, brad pitt, leonardo dicaprio - movie.mp4"))
    }

    @Test fun textAfterTheFirstHyphenIsIgnored() {
        assertEquals(listOf("Tom Cruise"), VirtualNames.of("Tom Cruise - Interview, HD - part 2.mkv"))
    }

    @Test fun withoutAHyphenEveryCommaNameCounts() {
        assertEquals(listOf("Shah Rukh Khan", "Salman Khan"), VirtualNames.of("shah rukh khan, salman khan.mp4"))
    }

    @Test fun noHyphenAndNoCommaUsesTheFirstTwoWords() {
        assertEquals(listOf("Tom Cruise"), VirtualNames.of("tom cruise movie night.mp4"))
    }

    @Test fun capitalisationIsOneStyleAndDuplicatesMerge() {
        assertEquals(listOf("Tom Cruise"), VirtualNames.of("TOM CRUISE, tom  cruise - x.mp4"))
        assertEquals("Tom Cruise", VirtualNames.display("TOM CRUISE"))
        assertEquals(VirtualNames.identity("Tom Cruise"), VirtualNames.identity("tom   CRUISE"))
    }

    @Test fun leadingTagsAndBlankNamesAreHandled() {
        assertEquals(listOf("Tom Cruise"), VirtualNames.of("[1080p] Tom Cruise - x.mp4"))
        assertEquals(listOf("#"), VirtualNames.of("- x.mp4"))
        assertEquals(listOf("A", "B"), VirtualNames.of("a,, b - x.mp4"))
    }
}
