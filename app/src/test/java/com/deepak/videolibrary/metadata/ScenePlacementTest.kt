package com.deepak.videolibrary.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePlacementTest {
    @Test fun onlyNameFoldersInsideVirtualFoldersGetPerformerPortraits() {
        val settings = SceneMetadataSettings()
        assertEquals(FolderCoverChoice.PERFORMER, settings.folderChoice("vset:my-set:Tom Cruise"))
        // The Virtual folder you named yourself, and folders inside it that you made, are not searched.
        assertEquals(FolderCoverChoice.ORIGINAL, settings.folderChoice("vset:my-set"))
        assertTrue(settings.virtualGeneratedVideoScenes)
    }

    @Test fun customFoldersGetNothingAutomatic() {
        val settings = SceneMetadataSettings()
        assertEquals(FolderCoverChoice.ORIGINAL, settings.folderChoice("own:12"))
        assertEquals(FolderCoverChoice.ORIGINAL, settings.folderChoice("match:3"))
        assertEquals(FolderCoverChoice.ORIGINAL, settings.folderChoice("virtual:Another group"))
    }

    @Test fun originalFoldersAlwaysLookForMoviePosters() {
        assertEquals(FolderCoverChoice.MOVIE, SceneMetadataSettings().folderChoice("original:Tiger/Heat"))
        assertTrue(SceneMetadataSettings().needsMoviePosters())
    }

    @Test fun foldersNeverUseScenePosters() {
        assertFalse(SceneMetadataSettings().needsFolderScenePosters())
    }
}
