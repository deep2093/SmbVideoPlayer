package com.deepak.videolibrary.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkResolverTest {
    private val on = ArtworkSettings(enabled = true)
    private fun row(key: String, mode: ArtworkMode, manual: Boolean, image: String? = null) = ArtworkOverride(key, mode, manual, image)

    @Test fun disabledFeatureChangesNothing() {
        val s = ArtworkSettings(enabled = false, videoMode = ArtworkMode.FRAME)
        val own = row("video:1", ArtworkMode.CUSTOM, true, "a.jpg")
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIDEO, own, null, true, s).source)
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.SMB_FOLDER, own, null, false, s).source)
    }

    @Test fun manualImageBeatsEverything() {
        val s = on.copy(videoMode = ArtworkMode.FRAME)
        val own = row("video:1", ArtworkMode.CUSTOM, true, "mine.jpg")
        val d = ArtworkResolver.decide(ArtworkScope.VIDEO, own, null, hasCustomFrame = true, settings = s)
        assertEquals(ArtworkDecision(ArtworkSource.IMAGE, "mine.jpg"), d)
    }

    @Test fun pickedFrameBeatsTheDefault() {
        val s = on.copy(videoMode = ArtworkMode.FRAME)
        assertEquals(ArtworkSource.CUSTOM_FRAME, ArtworkResolver.decide(ArtworkScope.VIDEO, null, null, true, s).source)
    }

    @Test fun defaultsApplyPerScopeIndependently() {
        val s = on.copy(videoMode = ArtworkMode.EXISTING, smbFolderMode = ArtworkMode.COLLAGE, virtualFolderMode = ArtworkMode.FIRST_FRAME)
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIDEO, null, null, false, s).source)
        assertEquals(ArtworkSource.COLLAGE, ArtworkResolver.decide(ArtworkScope.SMB_FOLDER, null, null, false, s).source)
        assertEquals(ArtworkSource.FIRST_FRAME, ArtworkResolver.decide(ArtworkScope.VIRTUAL_FOLDER, null, null, false, s).source)
    }

    @Test fun folderModesNeverApplyToVideos() {
        val s = on.copy(videoMode = ArtworkMode.COLLAGE)
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIDEO, null, null, false, s).source)
    }

    @Test fun fetchedPortraitOnlyShowsWhenPortraitsAreOnForThatScope() {
        val fetched = row("virtual:Ann", ArtworkMode.PORTRAIT, false, "p.jpg")
        val base = on.copy(virtualFolderMode = ArtworkMode.PORTRAIT)
        // No consent: never.
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIRTUAL_FOLDER, fetched, null, false, base).source)
        // Consent but not enabled for folders: never.
        val consentOnly = base.copy(adultConsent = true)
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIRTUAL_FOLDER, fetched, null, false, consentOnly).source)
        // Enabled for folders: shown.
        val folders = consentOnly.copy(portraitsOnFolders = true)
        assertEquals(ArtworkDecision(ArtworkSource.IMAGE, "p.jpg"), ArtworkResolver.decide(ArtworkScope.VIRTUAL_FOLDER, fetched, null, false, folders))
        // Default is not portrait: the fetched row is ignored (kept, not shown).
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIRTUAL_FOLDER, fetched, null, false, folders.copy(virtualFolderMode = ArtworkMode.EXISTING)).source)
    }

    @Test fun performerPortraitsStayOnFoldersAndNeverAppearOnVideos() {
        val fetched = row("virtual:Ann", ArtworkMode.PORTRAIT, false, "p.jpg")
        val s = on.copy(adultConsent = true, portraitsOnFolders = true, portraitsOnVideos = false, videoMode = ArtworkMode.PORTRAIT, virtualFolderMode = ArtworkMode.PORTRAIT)
        assertEquals(ArtworkSource.IMAGE, ArtworkResolver.decide(ArtworkScope.VIRTUAL_FOLDER, fetched, null, false, s).source)
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIDEO, null, fetched, false, s).source)
        // Old saved preferences must not leak performer artwork onto video cards.
        val legacySaved = s.copy(portraitsOnVideos = true)
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIDEO, null, fetched, false, legacySaved).source)
    }

    @Test fun explicitNoneAndExistingOverrides() {
        val s = on.copy(smbFolderMode = ArtworkMode.COLLAGE)
        assertEquals(ArtworkSource.NONE, ArtworkResolver.decide(ArtworkScope.SMB_FOLDER, row("original:a", ArtworkMode.NONE, true), null, false, s).source)
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.SMB_FOLDER, row("original:a", ArtworkMode.EXISTING, true), null, false, s).source)
        assertEquals(ArtworkSource.FIRST_FRAME, ArtworkResolver.decide(ArtworkScope.SMB_FOLDER, row("original:a", ArtworkMode.FIRST_FRAME, true), null, false, s).source)
    }

    @Test fun manualRowWithoutPictureFallsBackToTheDefault() {
        val s = on.copy(smbFolderMode = ArtworkMode.COLLAGE)
        assertEquals(ArtworkSource.COLLAGE, ArtworkResolver.decide(ArtworkScope.SMB_FOLDER, row("original:a", ArtworkMode.CUSTOM, true, null), null, false, s).source)
    }
}
