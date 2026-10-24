package com.deepak.videolibrary

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SkyWingAppearanceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun allFourChoicesPersistAndApplyWithoutRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val previous = prefs.getString("theme", "CHARCOAL")
        var observed = ""
        var generation by mutableIntStateOf(0)
        try {
            rule.setContent {
                key(generation) {
                    VideoLibraryTheme {
                        val active = LocalLibraryTheme.current.id
                        SideEffect { observed = active }
                        Column(Modifier.verticalScroll(rememberScrollState())) { ThemeSettings() }
                    }
                }
            }
            skyWingGlassThemes.forEach { theme ->
                rule.onNodeWithText(theme.label).performScrollTo().performClick()
                rule.runOnIdle {
                    assertEquals(theme.id, prefs.getString("theme", null))
                    assertEquals(theme.id, observed)
                    generation++ // Recreate the theme composition, forcing a fresh preference read.
                }
                rule.runOnIdle { assertEquals(theme.id, observed) }
            }
        } finally {
            prefs.edit().putString("theme", previous).commit()
        }
    }

    @Test fun adaptiveIconsAndBrandResolveInBothSystemModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.applicationInfo.loadLabel(context.packageManager).toString().startsWith("SkyWing"))
        listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { mode ->
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            val themed = context.createConfigurationContext(config)
            assertNotNull(themed.getDrawable(R.mipmap.ic_launcher))
            assertNotNull(themed.getDrawable(R.mipmap.ic_launcher_round))
            assertNotNull(themed.getDrawable(R.drawable.ic_skywing_monochrome))
        }
    }
}
