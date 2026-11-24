package com.skywing.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.skywing.desktop.ui.theme.SkyPalettes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M3 item 1: the design tokens (plan 6.2, Tokens board) and the themes made from them. */
class SkyTokensTest {
    @Test fun theThemesAreThePlansFiveSets() {
        assertEquals(listOf("DARK", "LIGHT", "MIDNIGHT", "OLED", "ADAPTIVE"), skyThemes.map { it.id })
        assertEquals(1, skyThemes.count { it.adaptive != null })
        assertEquals(listOf("LIGHT"), skyThemes.filter { it.light }.map { it.id })
    }

    @Test fun darkHasTheTokensOfThePlanAndTheBoard() {
        val dark = SkyPalettes.Dark
        assertEquals(Color(0xFF111215), dark.base)
        assertEquals(Color(0xFF16181C), dark.layer1)
        assertEquals(Color(0xFF1C1E23), dark.layer2)
        assertEquals(Color(0xFF24272D), dark.layer3)
        assertEquals(Color(0xFF2B2F37), dark.control)
        assertEquals(Color(0x14FFFFFF), dark.stroke)
        assertEquals(Color(0xFFF3F4F6), dark.text)
        assertEquals(Color(0xFFA9AEB8), dark.textSecondary)
        assertEquals(Color(0xFF6CA8FF), dark.accent)
        assertEquals(Color(0xFF0B1220), dark.onAccent)
        assertEquals(Color(0xFF4CC38A), dark.success)
        assertEquals(Color(0xFFFFB547), dark.warning)
        assertEquals(Color(0xFFFF6B6B), dark.danger)
        assertEquals(Color(0x8C000000), dark.scrim)
    }

    @Test fun selectionIsTheAccentAt16PercentAndTheFocusRingIsTheAccent() {
        for (theme in skyThemes) {
            val c = theme.colors
            assertEquals(c.accent.copy(alpha = 0.16f), c.accentSubtle, theme.id)
            assertEquals(c.accent, c.focus, theme.id)
        }
    }

    @Test fun oledIsPureBlackWithANearBlackRail() {
        val oled = skyThemes.first { it.id == "OLED" }.colors
        assertEquals(Color.Black, oled.base)
        assertEquals(Color(0xFF0A0A0B), oled.layer1)
    }

    @Test fun textReadsOnEveryLayerOfEveryTheme() {
        for (theme in skyThemes) {
            val c = theme.colors
            for (layer in listOf(c.base, c.layer1, c.layer2, c.layer3)) {
                assertTrue(contrast(c.text, layer) >= 7.0, "${theme.id}: text on a layer reads ${contrast(c.text, layer)}:1")
                assertTrue(contrast(c.textSecondary, layer) >= 4.5, "${theme.id}: secondary text on a layer reads ${contrast(c.textSecondary, layer)}:1")
            }
            assertTrue(contrast(c.onAccent, c.accent) >= 4.5, "${theme.id}: text on the accent reads ${contrast(c.onAccent, c.accent)}:1")
            assertTrue(contrast(c.accent, c.accentSubtle.compositeOver(c.layer1)) >= 3.0, "${theme.id}: the accent on a selected row")
        }
    }

    @Test fun savedThemesOfEarlierBuildsMoveToTheirNewSet() {
        val moves = mapOf(
            "LIQUID" to "DARK", "SKYWING_CRYSTAL_DARK" to "DARK",
            "SKYWING_AURORA_LIGHT" to "LIGHT", "LIQUID_GLASS" to "LIGHT",
            "AMOLED" to "OLED", "SLATE" to "MIDNIGHT",
            "ADAPTIVE_GLOW" to "ADAPTIVE", "ADAPTIVE_MIDNIGHT" to "ADAPTIVE", "ADAPTIVE_VIVID" to "ADAPTIVE",
        )
        moves.forEach { (old, new) -> assertEquals(new, SkyThemeStore.themeFor(old).id, old) }
        skyThemes.forEach { assertEquals(it.id, SkyThemeStore.themeFor(it.id).id) }
        assertEquals("DARK", SkyThemeStore.themeFor("SOMETHING_ELSE").id)
        assertEquals("DARK", SkyThemeStore.themeFor(null).id)
    }

    @Test fun anAdaptiveAccentChangesOnlyTheAccentColours() {
        val adaptive = skyThemes.first { it.adaptive != null }
        val shown = adaptive.withAccent(Color(0xFFE05050))
        assertEquals(Color(0xFFE05050), shown.accent)
        assertEquals(Color(0xFFE05050).copy(alpha = 0.16f), shown.colors.accentSubtle)
        assertEquals(adaptive.colors.copy(accent = shown.accent, accentSubtle = shown.colors.accentSubtle, focus = shown.accent), shown.colors)
    }

    @Test fun theOldNamesFollowTheNewTokens() {
        val dark = skyThemes.first()
        assertEquals(dark.colors.base, dark.background)
        assertEquals(dark.colors.layer1, dark.surface)
        assertEquals(dark.colors.layer2, dark.card)
        assertEquals(dark.colors.layer3, dark.menu)
        assertEquals(dark.colors.text, dark.ink)
        assertEquals(dark.colors.textSecondary, dark.muted)
        assertEquals(dark.colors.onAccent, dark.onAccent)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.compositeOver(b).luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb)
    }
}
