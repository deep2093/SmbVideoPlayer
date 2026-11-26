package com.skywing.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M3 design brief 5.6: Compact always fits, down to the 640 dp minimum window. Found by the M3 runner: at 640 and
 * 680 dp the Videos page cut "Play all" to "P". A page header's actions go under its title when the line is too
 * narrow for both, and every button keeps its own width.
 */
class HeaderLineTest {
    @get:Rule val compose = createComposeRule()

    /** An opened folder's header, with the actions MatchPage gives it. */
    @Composable
    private fun OpenedFolder() = FolderHeader("A folder with quite a long name", "12 videos", {}) {
        SkyButton("Select", {}, primary = false, icon = SkyIcon.Check)
        SkyField("", {}, "Search here", Modifier.width(220.dp), leading = SkyIcon.Search)
        SkyIconButton(SkyIcon.ZoomOut, "Smaller cards", {})
        SkyIconButton(SkyIcon.ZoomIn, "Larger cards", {})
        SkyIconButton(SkyIcon.CropPortrait, "Show taller portrait cards", {})
        SkyMenuButton(SkyIcon.MoreVert, "Folder options", emptyList())
        SkyButton("Play all", {}, Modifier.testTag("play"), icon = SkyIcon.Play)
    }

    /** The header inside a window's content of [width], with a free-standing Play all to compare against. */
    private fun show(width: Dp, header: @Composable () -> Unit) = compose.setContent {
        Column {
            Box(Modifier.width(width).testTag("line")) { header() }
            SkyButton("Play all", {}, Modifier.testTag("reference"), icon = SkyIcon.Play)
        }
    }

    private fun node(tag: String): SemanticsNode = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    private fun assertPlayAllWhole() {
        val play = node("play")
        val line = node("line").boundsInRoot
        assertEquals(node("reference").size.width, play.size.width, "Play all keeps its own width")
        assertTrue(play.boundsInRoot.right <= line.right + 0.5f, "Play all ends inside the line: ${play.boundsInRoot} in $line")
    }

    @Test fun anOpenedFolderPutsItsActionsUnderTheTitleAtTheNarrowestWindow() {
        // 640 dp less the page's own padding.
        show(608.dp) { OpenedFolder() }
        assertPlayAllWhole()
        val title = compose.onNodeWithText("A folder with quite a long name").fetchSemanticsNode().boundsInRoot
        assertTrue(node("play").boundsInRoot.top >= title.bottom, "the actions are under the title")
    }

    @Test fun anOpenedFolderKeepsOneLineWhenThereIsRoom() {
        show(1100.dp) { OpenedFolder() }
        assertPlayAllWhole()
        val title = compose.onNodeWithText("A folder with quite a long name").fetchSemanticsNode().boundsInRoot
        assertTrue(node("play").boundsInRoot.top < title.bottom, "the actions stay on the title's line")
        assertEquals(node("line").boundsInRoot.right, node("play").boundsInRoot.right, 0.5f, "Play all ends the line")
    }

    /** The Videos page's second line: the source chips, then Sort, the card sizes, Select and Play all. */
    @Composable
    private fun VideosToolbar() = HeaderLine(lead = { SkyChipBar(listOf("All", "This PC", "Network"), 0, {}) }) {
        SkyButton("Sort: Newest", {}, primary = false, icon = SkyIcon.Sort)
        SkyIconButton(SkyIcon.ZoomOut, "Smaller cards", {})
        SkyIconButton(SkyIcon.ZoomIn, "Larger cards", {})
        SkyIconButton(SkyIcon.CropPortrait, "Show taller portrait cards", {})
        SkyButton("Select", {}, primary = false, icon = SkyIcon.Check)
        SkyButton("Play all", {}, Modifier.testTag("play"), icon = SkyIcon.Play)
    }

    private fun chips() = compose.onNodeWithText("Network", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun theVideosToolbarPutsItsActionsUnderTheChipsAtTheNarrowestWindow() {
        show(608.dp) { VideosToolbar() }
        assertPlayAllWhole()
        assertTrue(node("play").boundsInRoot.top >= chips().bottom, "the actions are under the chips")
    }

    @Test fun theVideosToolbarKeepsOneLineWhenThereIsRoom() {
        show(1100.dp) { VideosToolbar() }
        assertPlayAllWhole()
        assertTrue(node("play").boundsInRoot.top < chips().bottom, "the actions stay on the chips' line")
        assertEquals(node("line").boundsInRoot.right, node("play").boundsInRoot.right, 0.5f, "Play all ends the line")
    }
}
