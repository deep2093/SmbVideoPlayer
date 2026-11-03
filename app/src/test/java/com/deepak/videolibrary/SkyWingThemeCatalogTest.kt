package com.deepak.videolibrary

import com.deepak.videolibrary.ui.DEFAULT_THEME
import com.deepak.videolibrary.ui.darkThemes
import com.deepak.videolibrary.ui.libraryThemes
import com.deepak.videolibrary.ui.lightThemes
import org.junit.Assert.*
import org.junit.Test

/** The Pixallery theme set (4 Nov 2026): six themes, Pixallery's ids, Liquid Day by default. */
class SkyWingThemeCatalogTest {
    @Test fun pixalleryThemesOnlyWithStableIds() {
        assertEquals(libraryThemes.size, libraryThemes.map { it.id }.toSet().size)
        assertEquals(
            listOf("PEARL_MIST", "AURORA_GLASS_LIGHT", "LIQUID_DAY", "CRYSTAL_GLASS_LIGHT", "ADAPTIVE_MIDNIGHT", "ADAPTIVE_VIVID"),
            libraryThemes.map { it.id },
        )
        assertEquals(
            listOf("Warm Light", "Luminous Light", "Liquid Day", "Clear Light", "Photo Night", "Vivid Night"),
            libraryThemes.map { it.label },
        )
        assertTrue(libraryThemes.any { it.id == DEFAULT_THEME })
        assertEquals(4, lightThemes.size)
        assertEquals(2, darkThemes.size)
        assertTrue(darkThemes.all { it.adaptive != null })
        assertTrue(libraryThemes.all { it.glass && it.wallpaper.size >= 2 && it.accentFill != null && it.panelFill != null })
        // Three pale light themes with dark text; Clear Light and the dark themes are deep with white text.
        assertEquals(listOf("PEARL_MIST", "AURORA_GLASS_LIGHT", "LIQUID_DAY"), libraryThemes.filter { it.light }.map { it.id })
    }

    @Test fun oldSkyWingThemeIdsAreGone() {
        val ids = libraryThemes.map { it.id }
        listOf("CHARCOAL", "AMOLED", "SLATE", "FOREST", "PLUM", "CLARITY_DARK", "CLARITY_LIGHT", "LIQUID", "LIQUID_GLASS", "ADAPTIVE_GLOW")
            .forEach { assertFalse(it, it in ids) }
    }
}
