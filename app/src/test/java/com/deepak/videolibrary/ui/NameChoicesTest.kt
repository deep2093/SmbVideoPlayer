package com.deepak.videolibrary.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NameChoicesTest {
    @Test fun choicesSplitTheNameAtCommasAndHyphens() {
        assertEquals(
            listOf("Women Seeking Women 77", "Jelena Jensen", "Magdalene St Michaels", "Women Seeking Women 77- Jelena Jensen, Magdalene St Michaels"),
            nameChoices("Women Seeking Women 77- Jelena Jensen, Magdalene St Michaels"),
        )
    }

    @Test fun fileExtensionsAndShortPartsAreLeftOut() {
        assertEquals(listOf("tom cruise", "movie", "tom cruise- movie"), nameChoices("Movies/tom cruise- movie.mp4"))
        assertEquals(listOf("Holiday"), nameChoices("Holiday"))
    }
}
