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

    @Test fun eachSwitchChangesTheResult() {
        val name = "tom cruise, brad pitt - top gun.mp4"
        assertEquals(listOf("Tom Cruise, Brad Pitt"), VirtualNames.of(name, GroupingSwitches(stopAtHyphen = true, splitAtCommas = false, firstTwoWords = false)))
        assertEquals(listOf("Tom Cruise", "Brad Pitt - Top Gun"), VirtualNames.of(name, GroupingSwitches(stopAtHyphen = false, splitAtCommas = true, firstTwoWords = false)))
        assertEquals(listOf("Tom Cruise Movie Night"), VirtualNames.of("tom cruise movie night.mp4", GroupingSwitches(firstTwoWords = false)))
        assertEquals(listOf("Tom Cruise"), VirtualNames.of("tom cruise - top gun.mp4", GroupingSwitches(stopAtHyphen = false, splitAtCommas = false, firstTwoWords = true)))
    }

    @Test fun otherDashesAndCommasCountAsHyphenAndComma() {
        // An en dash right after a name used to be skipped, giving "Mary Major– Night Out" as a second folder.
        assertEquals(listOf("Jane Roe", "Mary Major"), VirtualNames.of("Jane Roe, Mary Major\u2013 Night Out - Studio- 23.07.22.mp4"))
        assertEquals(listOf("Jane Roe", "Mary Major"), VirtualNames.of("Jane Roe, Mary Major- First Day Pt. 3 - Jane Roe, Mary Major(2022.07.31)[2160p].mp4"))
        assertEquals(listOf("Mary Major"), VirtualNames.of("Mary Major \u2014 Scene.mp4"))
        assertEquals(listOf("Mary Major"), VirtualNames.of("Mary Major\u2212Scene.mp4"))
        assertEquals(listOf("Jane Roe", "Mary Major"), VirtualNames.of("Jane Roe\uFF0CMary Major - Scene.mp4"))
    }
}
