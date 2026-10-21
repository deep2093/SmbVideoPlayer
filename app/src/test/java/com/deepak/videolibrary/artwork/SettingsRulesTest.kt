package com.deepak.videolibrary.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val bad = ArtworkSettings(customEndpoints = mapOf(ProviderIds.STASH_DB to "http://x.example.com", "theporndb" to "https://api.example.org", "other" to "https://a.example.org"))
        assertEquals(emptyMap<String, String>(), ArtworkPreferences.sanitize(bad).customEndpoints)
        val good = ArtworkSettings(customEndpoints = mapOf(ProviderIds.STASH_DB to "https://stash.example.org/graphql"))
        assertEquals(mapOf(ProviderIds.STASH_DB to "https://stash.example.org/graphql"), ArtworkPreferences.sanitize(good).customEndpoints)
    }

    @Test fun providerOrderAlwaysListsEveryProviderOnce() {
        // ThePornDB was removed: an order saved by an older version keeps only StashDB.
        assertEquals(listOf(ProviderIds.STASH_DB), ArtworkPreferences.sanitizeOrder(listOf("theporndb", "junk", ProviderIds.STASH_DB, ProviderIds.STASH_DB)))
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

    @Test fun zoomGivesTallPhotosRoomToMoveSideways() {
        // At zoom 1 a tall photo fills the 3:4 width, so left/right changes nothing.
        assertEquals(CoverCrop.rect(1000, 2000, 3f / 4f, 0f, 0f), CoverCrop.rect(1000, 2000, 3f / 4f, 1f, 0f))
        // Zoomed 2x the window is half as wide and can slide from the left edge to the right edge.
        val left = CoverCrop.rect(1000, 2000, 3f / 4f, 0f, 0f, zoom = 2f)
        val right = CoverCrop.rect(1000, 2000, 3f / 4f, 1f, 0f, zoom = 2f)
        assertEquals(CropWindow(0, 0, 500, 666), left)
        assertEquals(CropWindow(500, 0, 500, 666), right)
    }

    @Test fun sliderMiddleIsNoZoomAndZoomOutReachesPastTheEdges() {
        assertEquals(1f, CoverCrop.zoomForSlider(0.5f), 0.0001f)
        assertEquals(CoverCrop.MIN_ZOOM, CoverCrop.zoomForSlider(0f), 0.0001f)
        assertEquals(CoverCrop.MAX_ZOOM, CoverCrop.zoomForSlider(1f), 0.0001f)
        val out = CoverCrop.rect(1000, 2000, 3f / 4f, 0.5f, 0.5f, zoom = 0.5f)
        assertTrue(CoverCrop.overflows(out, 1000, 2000))
        assertFalse(CoverCrop.overflows(CoverCrop.rect(1000, 2000, 3f / 4f, 0.5f, 0.5f), 1000, 2000))
    }

    @Test fun keysMapToScopes() {
        assertEquals(ArtworkScope.VIDEO, ArtworkKeys.scopeOf(ArtworkKeys.video(5)))
        assertEquals(ArtworkScope.SMB_FOLDER, ArtworkKeys.scopeOf(ArtworkKeys.originalFolder("a/b")))
        assertEquals(ArtworkScope.VIRTUAL_FOLDER, ArtworkKeys.scopeOf(ArtworkKeys.virtualFolder("x")))
        assertEquals(ArtworkScope.VIRTUAL_FOLDER, ArtworkKeys.scopeOf(ArtworkKeys.matchFolder(3)))
    }
}
