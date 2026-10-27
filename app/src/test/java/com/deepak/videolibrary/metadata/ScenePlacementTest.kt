package com.deepak.videolibrary.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePlacementTest {
    @Test fun virtualGeneratedSubfolderUsesPerformerByDefault() {
        val settings = SceneMetadataSettings()
        assertEquals(FolderCoverChoice.PERFORMER, settings.folderChoice("vset:my-set:Tom Cruise"))
        assertEquals(FolderCoverChoice.ORIGINAL, settings.folderChoice("vset:my-set"))
        assertTrue(settings.virtualGeneratedVideoScenes)
    }

    @Test fun virtualOnlyOverridesNeverChangeOtherSections() {
        val settings = SceneMetadataSettings(
            generatedFolderCover = FolderCoverChoice.SCENE,
            virtualGeneratedFolderCover = FolderCoverChoice.PERFORMER,
            virtualGeneratedVideoScenes = true,
        )
        assertEquals(FolderCoverChoice.PERFORMER, settings.folderChoice("vset:some-set:group"))
        assertEquals(FolderCoverChoice.SCENE, settings.folderChoice("virtual:Another group"))
        assertEquals(FolderCoverChoice.ORIGINAL, settings.folderChoice("original:Videos"))
    }

    @Test fun enablingSceneCoverInVirtualGeneratedFoldersRequestsPosterCache() {
        val settings = SceneMetadataSettings(
            videoPosters = false, virtualGeneratedVideoScenes = true,
            virtualGeneratedFolderCover = FolderCoverChoice.PERFORMER,
        )
        assertTrue(settings.virtualGeneratedVideoScenes)
        assertEquals(FolderCoverChoice.PERFORMER, settings.folderChoice("vset:group:performer"))
    }
}
