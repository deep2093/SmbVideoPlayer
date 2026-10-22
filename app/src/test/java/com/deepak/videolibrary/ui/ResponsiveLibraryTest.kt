package com.deepak.videolibrary.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLibraryTest {
    @Test fun aPhoneUsesTheColumnsYouChose() {
        for (preset in LibraryLayout.presets) assertEquals(preset.toInt(), LibraryLayout.fittedColumns(preset, 390 - 32))
    }

    @Test fun aVeryNarrowScreenStillFits() {
        assertEquals(1, LibraryLayout.fittedColumns("5", 60))
        assertEquals(3, LibraryLayout.fittedColumns("5", 3 * 56 + 2 * 12))
    }

    @Test fun aTabletRespectsTheSavedDensity() {
        assertEquals(3, LibraryLayout.fittedColumns("3", 1280 - 204 - 32))
        assertEquals(5, LibraryLayout.fittedColumns("5", 1280 - 204 - 32))
    }

    @Test fun everyMultiColumnLayoutKeepsCardsAtLeastTheMinimumWidth() {
        for (width in 1..1800) for (preset in LibraryLayout.presets) {
            val count = LibraryLayout.fittedColumns(preset, width)
            assertTrue(count in 1..LibraryLayout.columns(preset))
            if (count > 1) assertTrue((width - (count - 1) * 12).toFloat() / count >= LibraryLayout.MIN_TILE_DP)
        }
    }
}
