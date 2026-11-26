package com.skywing.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import java.nio.file.Files
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings › Library holds the actor page layout (spec, Settings › Library), and its chips set it. */
class SettingsLibraryPageTest {
    @get:Rule val compose = createComposeRule()

    private val folder = Files.createTempDirectory("skywing-settingslibrary")

    @Test fun theActorPageLayoutChipsAreOnTheLibraryPageAndSetTheLayout() {
        val views = LibraryViews(LibraryViewStore(null), FolderPrefs())
        val library = LibraryModel(WindowsMediaIndex(folder.resolve("media-index.bin")))
        val organizer = Organizer(FolderOrganizerStore(folder.resolve("folders.bin")))
        compose.setContent {
            CompositionLocalProvider(LocalLibraryViews provides views) {
                Column { LibraryPage(library, organizer, onOpenFiles = {}) }
            }
        }
        compose.onNodeWithText("Actor page layout").assertExists()
        compose.onNodeWithText("Studio").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(ActorLayout.STUDIO, views.data.actorLayout)
        compose.onNodeWithText("Header").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(ActorLayout.HEADER, views.data.actorLayout)
    }
}
