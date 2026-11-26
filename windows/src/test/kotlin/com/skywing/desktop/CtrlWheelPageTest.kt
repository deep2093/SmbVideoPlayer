package com.skywing.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule

/** Ctrl+wheel changes the page the grid shows now, also after the page changes inside the same grid. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class CtrlWheelPageTest {
    @get:Rule val compose = createComposeRule()

    private val folder = Files.createTempDirectory("skywing-ctrlwheel")

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    @Test fun onlyTheShownPagesSizeChanges() {
        val views = LibraryViews(LibraryViewStore(null), FolderPrefs())
        val organizer = Organizer(FolderOrganizerStore(folder.resolve("folders.bin")))
        var page by mutableStateOf("own:1")
        compose.setContent {
            CompositionLocalProvider(LocalLibraryViews provides views) {
                TileGrid(emptyList(), LazyGridState(), organizer, page, emptyMap(), { _, _ -> }, Modifier.fillMaxSize().testTag("grid"), emptyText = "Nothing")
            }
        }
        compose.waitForIdle()
        wheelUp()
        assertEquals(210, views.of("own:1").size)
        page = "own:1|group"
        compose.waitForIdle()
        wheelUp()
        assertEquals(210, views.of("own:1").size)
        assertEquals(230, views.of("own:1|group").size)
        assertNull(views.data.pages["own:2"])
    }

    private fun wheelUp() {
        val grid = compose.onNodeWithTag("grid")
        grid.performKeyInput { keyDown(Key.CtrlLeft) }
        grid.performMouseInput { scroll(-1f) }
        grid.performKeyInput { keyUp(Key.CtrlLeft) }
        compose.waitForIdle()
    }
}
