package com.skywing.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import java.nio.file.Files
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings › App layout holds the picture size new pages start at, and its chips set it. */
class SettingsLayoutPageTest {
    @get:Rule val compose = createComposeRule()

    private val folder = Files.createTempDirectory("skywing-settingslayout")

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    @Test fun thePictureSizeChipsSetTheSizeNewPagesStartAt() {
        val views = LibraryViews(LibraryViewStore(null), FolderPrefs())
        val organizer = Organizer(FolderOrganizerStore(folder.resolve("folders.bin")))
        compose.setContent {
            CompositionLocalProvider(LocalLibraryViews provides views) {
                Column { LayoutPage(organizer) }
            }
        }
        compose.onNodeWithText("Picture size").assertExists()
        compose.onNodeWithText("Large").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(CardSizes.LARGE, views.of("never-set").size)
        compose.onNodeWithText("Small").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(CardSizes.SMALL, views.of("never-set").size)
    }
}
