package com.deepak.videolibrary.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupKeyTest {
    @Test fun configurableDelimitersPreserveDefaultAndAllowHyphens() {
        assertEquals("tom cruise",GroupKey.fromFileName("Tom Cruise - Interview, HD.mp4"))
        assertEquals("tom cruise - interview",GroupKey.fromFileName("Tom Cruise - Interview, HD.mp4",GroupingRule.COMMA_ONLY))
        assertEquals("tom cruise - interview, hd",GroupKey.fromFileName("Tom Cruise - Interview, HD.mp4",GroupingRule.FULL_NAME))
    }
    @Test fun commaUsesFullLeadingPhrase() {
        assertEquals("roger marting", GroupKey.fromFileName("Roger Marting, alex.mkv"))
    }

    @Test fun numberedSequelsShareFolder() {
        assertEquals("avatar", GroupKey.fromFileName("Avatar 1.mkv"))
        assertEquals("avatar", GroupKey.fromFileName("Avatar 2.mkv"))
    }

    @Test fun leadingReleaseTagsAreIgnored() {
        assertEquals("avatar", GroupKey.fromFileName("[1080p] Avatar - Extended.mkv"))
        assertEquals("avatar", GroupKey.fromFileName("[WEB-DL][1080p] Avatar.mkv"))
        assertEquals("avatar", GroupKey.fromFileName("(2009) Avatar.mkv"))
    }

    @Test fun phraseIsKeptBeforeComma() {
        assertEquals("tom cruise", GroupKey.fromFileName("Tom Cruise - Interview.mp4"))
        assertEquals("brad pitt collection", GroupKey.fromFileName("Brad Pitt Collection 01.mkv"))
    }

    @Test fun hyphenAndCommaDelimiters() {
        assertEquals("roger martin", GroupKey.fromFileName("Roger Martin- Alex.mkv"))
        assertEquals("roger martin", GroupKey.fromFileName("Roger Martin, Alex- Adam.mkv"))
        assertEquals("roger martin", GroupKey.fromFileName("Roger Martin- Alex, Adam.mkv"))
    }

    @Test fun caseInsensitive() {
        assertEquals(GroupKey.fromFileName("AVATAR 3.mkv"), GroupKey.fromFileName("avatar 4.mkv"))
    }

    @Test fun nothingMeaningfulFallsBack() {
        assertEquals(GroupKey.FALLBACK, GroupKey.fromFileName("[1080p].mkv"))
        assertEquals(GroupKey.FALLBACK, GroupKey.fromFileName("---.mp4"))
    }
}
