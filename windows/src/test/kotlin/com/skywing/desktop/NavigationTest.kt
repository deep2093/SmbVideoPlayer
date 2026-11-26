package com.skywing.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.skywing.desktop.ui.adaptive.Adaptive
import com.skywing.desktop.ui.adaptive.InputMode
import com.skywing.desktop.ui.adaptive.LocalAdaptive
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/** M3 item 4 (M3 design brief 5.2): the rail and the top tabs are one tab group with one selected entry. */
class NavigationTest {
    @get:Rule val compose = createComposeRule()

    private val tab = SemanticsMatcher("is a tab") { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
    private val playing = NowPlaying("Clip", paused = true, progress = 0.25f)

    @Test fun theWideRailListsHomeTheSectionsAndSettingsAsTabs() {
        var picked = ""
        compose.setContent { NavRail(wide = true, selected = "Home", nowPlaying = null, onSelect = { picked = it }, onTogglePause = {}) }
        compose.onNodeWithText("Home").assert(tab).assertIsSelected()
        listOf("Folders", "Videos", "Files and drives", "Settings").forEach { compose.onNodeWithText(it).assert(tab).assertIsNotSelected() }
        compose.onNodeWithText("Files and drives").performClick()
        assertEquals("Files", picked)
        compose.onAllNodesWithText("Now playing").assertCountEquals(0)
    }

    @Test fun theNarrowRailShowsShortLabelsButScreenReadersHearTheFullName() {
        compose.setContent { NavRail(wide = false, selected = "Files", nowPlaying = null, onSelect = {}, onTogglePause = {}) }
        compose.onNodeWithText("Files and drives").assert(tab).assertIsSelected()
        compose.onAllNodesWithText("Drives").assertCountEquals(0)
    }

    @Test fun nowPlayingIsATabOfTheSameGroupWhileAVideoIsLoaded() {
        var picked = ""
        compose.setContent { NavRail(wide = false, selected = NOW_PLAYING, nowPlaying = playing, onSelect = { picked = it }, onTogglePause = {}) }
        compose.onNodeWithText("Now playing").assert(tab).assertIsSelected().performClick()
        assertEquals(NOW_PLAYING, picked)
    }

    @Test fun theTopTabsKeepSettingsInTheGroup() {
        var picked = ""
        compose.setContent {
            CompositionLocalProvider(LocalAdaptive provides Adaptive(680.dp, InputMode.Touch)) {
                TopTabs(selected = SETTINGS, nowPlaying = playing, onSelect = { picked = it })
            }
        }
        compose.onNodeWithText("Settings").assert(tab).assertIsSelected()
        compose.onNodeWithText("Now playing").assert(tab).assertIsNotSelected()
        compose.onNodeWithText("Files and drives").assert(tab).assertIsNotSelected().performClick()
        assertEquals("Files", picked)
    }
}
