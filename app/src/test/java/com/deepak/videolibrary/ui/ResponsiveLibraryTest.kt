package com.deepak.videolibrary.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLibraryTest {
    @Test fun aPhoneKeepsReadableArtworkEvenWithFiveColumnsSaved() {
        assertEquals(2, LibraryLayout.fittedColumns("5", 390 - 32))
        assertEquals(1, LibraryLayout.fittedColumns("5", 320 - 32))
    }

    @Test fun aTabletRespectsTheSavedDensity() {
        assertEquals(3, LibraryLayout.fittedColumns("3", 1280 - 204 - 32))
        assertEquals(5, LibraryLayout.fittedColumns("5", 1280 - 204 - 32))
    }

    @Test fun everyMultiColumnLayoutKeepsArtworkAtLeast148dpWide() {
        for (width in 1..1800) for (preset in LibraryLayout.presets) {
            val count = LibraryLayout.fittedColumns(preset, width)
            assertTrue(count in 1..LibraryLayout.columns(preset))
            if (count > 1) assertTrue((width - (count - 1) * 12).toFloat() / count >= 148)
        }
    }
}
