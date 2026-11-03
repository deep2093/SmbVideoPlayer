package com.deepak.videolibrary.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePlacementTest {
    private val before = SceneMetadataSettings(
        movieSections = setOf(SceneMetadataSettings.SECTION_ORIGINAL),
        portraitSections = setOf(SceneMetadataSettings.SECTION_VIRTUAL),
    )

    @Test fun updatedAppKeepsMoviePostersInOriginalAndPortraitsInVirtual() {
        assertEquals(FolderCoverChoice.MOVIE, before.folderChoice("original:Tiger/Heat"))
        assertEquals(FolderCoverChoice.PERFORMER, before.folderChoice("vset:my-set:Tom Cruise"))
        // The Virtual folder you named yourself is never searched.
        assertEquals(FolderCoverChoice.ORIGINAL, before.folderChoice("vset:my-set"))
        assertEquals(FolderCoverChoice.ORIGINAL, before.folderChoice("own:12"))
        assertEquals(FolderCoverChoice.ORIGINAL, before.folderChoice("match:3"))
        assertEquals(FolderCoverChoice.ORIGINAL, before.folderChoice("virtual:Another group"))
        assertTrue(before.needsMoviePosters())
        assertTrue(before.virtualGeneratedVideoScenes)
    }

    @Test fun newInstallHasNothingAutomatic() {
        val fresh = SceneMetadataSettings()
        assertEquals(FolderCoverChoice.ORIGINAL, fresh.folderChoice("original:Tiger/Heat"))
        assertEquals(FolderCoverChoice.ORIGINAL, fresh.folderChoice("vset:my-set:Tom Cruise"))
        assertFalse(fresh.needsMoviePosters())
        assertFalse(fresh.portraitsWanted)
    }

    @Test fun sectionsCanBeChosen() {
        val custom = SceneMetadataSettings(
            movieSections = setOf(SceneMetadataSettings.SECTION_VIRTUAL),
            portraitSections = setOf(SceneMetadataSettings.SECTION_CUSTOM, SceneMetadataSettings.SECTION_VIRTUAL),
        )
        assertEquals(FolderCoverChoice.PERFORMER, custom.folderChoice("own:12"))
        assertEquals(FolderCoverChoice.PERFORMER, custom.folderChoice("match:3"))
        // Both on: the movie poster first (the portrait is used while there is none).
        assertEquals(FolderCoverChoice.MOVIE, custom.folderChoice("vset:my-set:Heat"))
        assertTrue(custom.portraitsFor("vset:my-set:Heat"))
        assertEquals(FolderCoverChoice.ORIGINAL, custom.folderChoice("original:Tiger/Heat"))
    }

    @Test fun foldersNeverUseScenePosters() {
        assertFalse(SceneMetadataSettings().needsFolderScenePosters())
    }
}
