package com.skywing.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkyUiLogicTest {
    @Test fun themesKeepTheAndroidIds() {
        val ids = skyThemes.map { it.id }
        assertEquals(listOf("LIQUID", "SKYWING_AURORA_LIGHT", "SKYWING_CRYSTAL_DARK", "LIQUID_GLASS"), ids)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(skyThemes.all { it.wallpaper.size >= 2 })
    }

    @Test fun sizesAndClocksAreReadable() {
        assertEquals("", formatSize(0))
        assertEquals("512 B", formatSize(512))
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("1:15", formatClock(75.0))
        assertEquals("1:01:01", formatClock(3661.0))
        assertEquals("0:00", formatClock(-4.0))
    }

    @Test fun watchedFractionUsesTheSavedPosition() {
        val entry = WindowsMediaEntry("C:\\videos\\a.mp4", "PC", "PC", "This PC", 10, 5)
        val history = mapOf("c:\\videos\\a.mp4" to WindowsWatchRecord("C:\\videos\\a.mp4", 30.0, 120.0, 1L))
        assertEquals(0.25f, watchedFraction(history, entry))
        assertNull(watchedFraction(emptyMap(), entry))
        assertNotNull(skyThemes.firstOrNull { it.light })
    }

    @Test fun selectionTogglesAndClears() {
        val selection = SelectionState()
        selection.toggle("a")
        selection.toggle("b")
        selection.toggle("a")
        assertEquals(setOf("b"), selection.paths)
        assertTrue(selection.active)
        selection.clear()
        assertTrue(selection.paths.isEmpty())
        assertEquals(false, selection.active)
    }
}
