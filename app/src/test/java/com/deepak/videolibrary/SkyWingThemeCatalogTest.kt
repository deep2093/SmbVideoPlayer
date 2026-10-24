package com.deepak.videolibrary

import com.deepak.videolibrary.ui.libraryThemes
import com.deepak.videolibrary.ui.skyWingGlassThemes
import org.junit.Assert.*
import org.junit.Test

class SkyWingThemeCatalogTest {
    @Test fun savedGlassIdsStillResolveWithoutDuplicateChoices() {
        assertEquals(libraryThemes.size, libraryThemes.map { it.id }.toSet().size)
        assertEquals(setOf("LIQUID", "LIQUID_GLASS", "SKYWING_AURORA_LIGHT", "SKYWING_CRYSTAL_DARK"), skyWingGlassThemes.map { it.id }.toSet())
        assertEquals("Aurora Glass · Dark", libraryThemes.single { it.id == "LIQUID" }.label)
        assertEquals("Crystal Glass · Light", libraryThemes.single { it.id == "LIQUID_GLASS" }.label)
        assertEquals(2, skyWingGlassThemes.count { it.light })
        assertTrue(skyWingGlassThemes.all { it.glass && it.wallpaper.size >= 2 })
        assertTrue(libraryThemes.map { it.id }.containsAll(listOf("CHARCOAL", "AMOLED", "SLATE", "FOREST", "PLUM", "ADAPTIVE_GLOW", "ADAPTIVE_MIDNIGHT", "ADAPTIVE_VIVID")))
    }
}
