package com.deepak.videolibrary.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePlacementTest {
    @Test fun virtualCustomAndGeneratedFoldersUsePerformerPortraits() {
        val settings = SceneMetadataSettings()
        assertEquals(FolderCoverChoice.PERFORMER, settings.folderChoice("vset:my-set:Tom Cruise"))
        assertEquals(FolderCoverChoice.PERFORMER, settings.folderChoice("vset:my-set"))
        assertEquals(FolderCoverChoice.PERFORMER, settings.folderChoice("virtual:Another group"))
        assertEquals(FolderCoverChoice.PERFORMER, settings.folderChoice("own:12"))
        assertTrue(settings.virtualGeneratedVideoScenes)
    }

    @Test fun originalFoldersKeepTheirCoverUnlessMoviePostersAreOn() {
        assertEquals(FolderCoverChoice.ORIGINAL, SceneMetadataSettings().folderChoice("original:Tiger/Heat"))
        val on = SceneMetadataSettings(originalFolderCover = FolderCoverChoice.MOVIE)
        assertEquals(FolderCoverChoice.MOVIE, on.folderChoice("original:Tiger/Heat"))
        assertTrue(on.needsMoviePosters())
    }

    @Test fun foldersNeverUseScenePosters() {
        val settings = SceneMetadataSettings(originalFolderCover = FolderCoverChoice.SCENE)
        assertEquals(FolderCoverChoice.ORIGINAL, settings.folderChoice("original:Tiger"))
        assertFalse(settings.needsFolderScenePosters())
    }
}
