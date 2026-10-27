package com.deepak.videolibrary.artwork

import com.deepak.videolibrary.artwork.online.AutoMatch
import com.deepak.videolibrary.artwork.online.PerformerCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitSelectionTest {
    private val base = ArtworkSettings(
        enabled = true,
        adultConsent = true,
        portraitsOnFolders = true,
        smbFolderMode = ArtworkMode.PORTRAIT,
        virtualFolderMode = ArtworkMode.PORTRAIT,
    )

    @Test fun automaticIsTheDefaultForBothFolderKinds() {
        val s = ArtworkSettings()
        assertEquals(PortraitSelection.AUTOMATIC, s.selectionFor(ArtworkScope.SMB_FOLDER))
        assertEquals(PortraitSelection.AUTOMATIC, s.selectionFor(ArtworkScope.VIRTUAL_FOLDER))
    }

    @Test fun selectionIsSetPerFolderKind() {
        val s = base.withSelection(ArtworkScope.SMB_FOLDER, PortraitSelection.MANUAL).withSelection(ArtworkScope.VIRTUAL_FOLDER, PortraitSelection.CUSTOM)
        assertEquals(PortraitSelection.MANUAL, s.selectionFor(ArtworkScope.SMB_FOLDER))
        assertEquals(PortraitSelection.CUSTOM, s.selectionFor(ArtworkScope.VIRTUAL_FOLDER))
    }

    @Test fun customNeverLooksUpInTheBackground() {
        val s = base.withSelection(ArtworkScope.SMB_FOLDER, PortraitSelection.CUSTOM)
        assertFalse(s.portraitLookupWanted(ArtworkScope.SMB_FOLDER))
        assertTrue(s.portraitLookupWanted(ArtworkScope.VIRTUAL_FOLDER))
        assertTrue(s.withSelection(ArtworkScope.SMB_FOLDER, PortraitSelection.MANUAL).portraitLookupWanted(ArtworkScope.SMB_FOLDER))
    }

    @Test fun lookupNeedsConsentAndAPortraitDefault() {
        assertFalse(base.copy(adultConsent = false).portraitLookupWanted(ArtworkScope.SMB_FOLDER))
        assertFalse(base.copy(enabled = false).portraitLookupWanted(ArtworkScope.SMB_FOLDER))
        assertFalse(base.copy(smbFolderMode = ArtworkMode.COLLAGE).portraitLookupWanted(ArtworkScope.SMB_FOLDER))
        assertFalse(base.portraitLookupWanted(ArtworkScope.VIDEO))
    }

    @Test fun olderVideoPortraitSettingsDoNotFetchOrDisplayPerformerPhotos() {
        val s = ArtworkSettings(enabled = true, adultConsent = true, portraitsOnVideos = true, videoMode = ArtworkMode.PORTRAIT)
        // An older saved setting never enables video-card portraits, nor redundant
        // folder lookups to satisfy a video-only portrait preference.
        assertFalse(s.portraitLookupWanted(ArtworkScope.VIRTUAL_FOLDER))
        assertFalse(s.portraitLookupWanted(ArtworkScope.SMB_FOLDER))
        val fetched = ArtworkOverride("virtual:Ann", ArtworkMode.PORTRAIT, false, "p.jpg")
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIRTUAL_FOLDER, fetched, null, false, s).source)
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIDEO, null, fetched, false, s).source)
    }

    @Test fun automaticThumbnailStaysWhenNothingWasChosen() {
        // Waiting for review (no picture yet) never hides the automatic thumbnail.
        val waiting = ArtworkOverride("virtual:Ann", ArtworkMode.PORTRAIT, false, null, lookupStatus = ArtworkOverride.STATUS_REVIEW)
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIRTUAL_FOLDER, waiting, null, false, base).source)
        assertEquals(ArtworkSource.LEGACY, ArtworkResolver.decide(ArtworkScope.VIDEO, null, waiting, false, base.copy(portraitsOnVideos = true, videoMode = ArtworkMode.PORTRAIT)).source)
    }

    private fun c(name: String) = PerformerCandidate("p", "P", name, name, null, "https://cdn.example.com/$name.jpg", null)

    @Test fun suggestionsAreRankedBestFirst() {
        val ranked = AutoMatch.ranked("Ann Lee", listOf(c("Bob Smith"), c("Ann Leeds"), c("ann lee"), c("Jo Ann Lee")))
        assertEquals(listOf("ann lee", "Ann Leeds", "Jo Ann Lee", "Bob Smith"), ranked.map { it.name })
    }
}
