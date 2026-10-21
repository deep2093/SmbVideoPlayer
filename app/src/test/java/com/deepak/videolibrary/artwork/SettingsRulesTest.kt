package com.deepak.videolibrary.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsRulesTest {
    @Test fun everythingIsOffByDefault() {
        val s = ArtworkSettings()
        assertFalse(s.enabled)
        assertFalse(s.adultConsent)
        assertFalse(s.portraitsOnFolders)
        assertFalse(s.portraitsOnVideos)
        assertFalse(s.autoFetchOnScan)
        assertEquals(ArtworkMode.EXISTING, s.videoMode)
        assertEquals(ArtworkMode.EXISTING, s.smbFolderMode)
        assertEquals(ArtworkMode.EXISTING, s.virtualFolderMode)
    }

    @Test fun portraitsAndAutoFetchNeverStayOnWithoutConsent() {
        val messy = ArtworkSettings(enabled = true, adultConsent = false, portraitsOnFolders = true, portraitsOnVideos = true, autoFetchOnScan = true)
        val clean = ArtworkPreferences.sanitize(messy)
        assertFalse(clean.portraitsOnFolders)
        assertFalse(clean.portraitsOnVideos)
        assertFalse(clean.autoFetchOnScan)
    }

    @Test fun autoFetchNeedsAPortraitScope() {
        val s = ArtworkSettings(enabled = true, adultConsent = true, autoFetchOnScan = true)
        assertFalse(ArtworkPreferences.sanitize(s).autoFetchOnScan)
        assertEquals(true, ArtworkPreferences.sanitize(s.copy(portraitsOnFolders = true)).autoFetchOnScan)
    }

    @Test fun badEndpointsAndUnknownProvidersAreDropped() {
        val s = ArtworkSettings(customEndpoints = mapOf(ProviderIds.STASH_DB to "http://x.example.com", ProviderIds.THE_PORN_DB to "https://api.example.org", "other" to "https://a.example.org"))
        assertEquals(mapOf(ProviderIds.THE_PORN_DB to "https://api.example.org"), ArtworkPreferences.sanitize(s).customEndpoints)
    }

    @Test fun providerOrderAlwaysListsEveryProviderOnce() {
        assertEquals(listOf(ProviderIds.STASH_DB, ProviderIds.THE_PORN_DB), ArtworkPreferences.sanitizeOrder(listOf(ProviderIds.STASH_DB, "junk", ProviderIds.STASH_DB)))
    }

    @Test fun cropWindowKeepsTheRequestedPartAndAspect() {
        // Tall portrait photo into a 16:9 card: full width, a slice of the height positioned by focus.
        val top = CoverCrop.rect(1000, 2000, 16f / 9f, 0.5f, 0f)
        assertEquals(CropWindow(0, 0, 1000, 562), top)
        val bottom = CoverCrop.rect(1000, 2000, 16f / 9f, 0.5f, 1f)
        assertEquals(CropWindow(0, 2000 - 562, 1000, 562), bottom)
        // Wide image: full height, slice of the width.
        val wide = CoverCrop.rect(4000, 1000, 16f / 9f, 0f, 0.5f)
        assertEquals(CropWindow(0, 0, 1777, 1000), wide)
        val centred = CoverCrop.rect(1600, 900, 16f / 9f, 0.5f, 0.5f)
        assertEquals(CropWindow(0, 0, 1600, 900), centred)
    }

    @Test fun keysMapToScopes() {
        assertEquals(ArtworkScope.VIDEO, ArtworkKeys.scopeOf(ArtworkKeys.video(5)))
        assertEquals(ArtworkScope.SMB_FOLDER, ArtworkKeys.scopeOf(ArtworkKeys.originalFolder("a/b")))
        assertEquals(ArtworkScope.VIRTUAL_FOLDER, ArtworkKeys.scopeOf(ArtworkKeys.virtualFolder("x")))
        assertEquals(ArtworkScope.VIRTUAL_FOLDER, ArtworkKeys.scopeOf(ArtworkKeys.matchFolder(3)))
    }
}
