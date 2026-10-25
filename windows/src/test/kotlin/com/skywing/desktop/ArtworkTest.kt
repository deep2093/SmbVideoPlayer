package com.skywing.desktop

import com.skywing.desktop.artwork.ArtworkKeys
import com.skywing.desktop.artwork.ArtworkMode
import com.skywing.desktop.artwork.ArtworkPreferences
import com.skywing.desktop.artwork.ArtworkRepository
import com.skywing.desktop.artwork.ArtworkScope
import com.skywing.desktop.artwork.ArtworkSettings
import com.skywing.desktop.artwork.AutoMatch
import com.skywing.desktop.artwork.CoverCrop
import com.skywing.desktop.artwork.NameCleaner
import com.skywing.desktop.artwork.PerformerCandidate
import com.skywing.desktop.artwork.ProviderException
import com.skywing.desktop.artwork.ProviderFailure
import com.skywing.desktop.artwork.StashDb
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface

class ArtworkTest {
    private fun candidate(name: String, id: String = name, image: String? = "https://cdn.stashdb.org/$id.jpg", hint: String? = null) =
        PerformerCandidate(StashDb.ID, StashDb.NAME, id, name, hint, image, image)

    @Test fun stashDbAnswersAreRead() {
        val body = """{"data":{"queryPerformers":{"performers":[
            {"id":"a1","name":"Jane Doe","disambiguation":"","images":[
                {"url":"https://cdn.stashdb.org/wide.jpg","width":800,"height":400},
                {"url":"https://cdn.stashdb.org/tall.jpg","width":400,"height":600}]},
            {"id":"","name":"No id","images":[]}]}}}"""
        val found = StashDb.parse(body)
        assertEquals(1, found.size)
        assertEquals("Jane Doe", found[0].name)
        assertEquals(null, found[0].disambiguation)
        // Portrait-shaped photos come first.
        assertEquals("https://cdn.stashdb.org/tall.jpg", found[0].imageUrl)
        assertEquals(2, found[0].images.size)
    }

    @Test fun stashDbErrorsBecomeFailures() {
        val e = assertFailsWith<ProviderException> { StashDb.parse("""{"errors":[{"message":"Unauthorized"}]}""") }
        assertEquals(ProviderFailure.UNAUTHORIZED, e.failure)
        assertFailsWith<ProviderException> { StashDb.parse("not json") }
    }

    @Test fun onlyOneClearMatchIsAppliedByItself() {
        assertIs<AutoMatch.Result.Match>(AutoMatch.evaluate("Jane Doe", listOf(candidate("Jane Doe"), candidate("Jane Doer"))))
        assertEquals(AutoMatch.Result.Ambiguous, AutoMatch.evaluate("Jane Doe", listOf(candidate("Jane Doe", "1"), candidate("jane doe", "2"))))
        assertEquals(AutoMatch.Result.Ambiguous, AutoMatch.evaluate("Jane Doe", listOf(candidate("Jane Doe", hint = "the other one"))))
        assertEquals(AutoMatch.Result.NoMatch, AutoMatch.evaluate("Jane Doe", listOf(candidate("Jane Doe", image = null))))
        assertEquals(AutoMatch.Result.NoMatch, AutoMatch.evaluate("Jo", listOf(candidate("Jo"))))
        assertEquals(listOf("Jane Doe", "Jane Doer", "Mary Jane Doe"), AutoMatch.ranked("jane doe", listOf(candidate("Mary Jane Doe"), candidate("Jane Doer"), candidate("Jane Doe"))).map { it.name })
    }

    @Test fun namesAreCleanedForSearching() {
        assertEquals("Jane Doe", NameCleaner.clean("Jane_Doe, Ann Lee - Scene 1080p.mp4"))
        assertEquals(listOf("Jane Doe", "Jane Doe, Ann Lee"), NameCleaner.videoTerms("Jane Doe, Ann Lee - Scene.mkv"))
        assertEquals("", NameCleaner.clean("12345.mp4"))
        assertTrue("Jane Doe" in NameCleaner.choices("Jane Doe - Scene.mp4"))
    }

    @Test fun cropWindowStaysInsideThePicture() {
        val wide = CoverCrop.rect(1000, 500, 3f / 4f, 0f, 0f)
        assertEquals(0, wide.left)
        assertEquals(500, wide.height)
        assertEquals(375, wide.width)
        val right = CoverCrop.rect(1000, 500, 3f / 4f, 1f, 0.5f)
        assertEquals(1000, right.left + right.width)
        val zoomed = CoverCrop.rect(1000, 500, 3f / 4f, 0.5f, 0.5f, zoom = 2f)
        assertTrue(zoomed.width < wide.width)
        assertEquals(1f, CoverCrop.zoomForSlider(0.5f))
    }

    @Test fun settingsNeverKeepPortraitsWithoutConsent() {
        val s = ArtworkSettings(adultConsent = false, portraitsOnFolders = true, portraitsOnVideos = true, autoFetchOnScan = true, collageTiles = 9, customEndpoint = "http://x").sanitized()
        assertFalse(s.portraitsOnFolders || s.portraitsOnVideos || s.autoFetchOnScan)
        assertEquals(6, s.collageTiles)
        assertEquals("", s.customEndpoint)
        assertFalse(ArtworkSettings(adultConsent = true, portraitsOnFolders = true).portraitLookupWanted(ArtworkScope.SMB_FOLDER))
        assertTrue(ArtworkSettings(adultConsent = true, portraitsOnFolders = true, smbFolderMode = ArtworkMode.PORTRAIT).portraitLookupWanted(ArtworkScope.SMB_FOLDER))
    }

    @Test fun lookedUpPicturesNeverReplaceChosenOnes() = runBlocking {
        val root = Files.createTempDirectory("skywing-art")
        val node = Preferences.userRoot().node("com/skywing/desktop/test-artwork-" + System.nanoTime())
        try {
            val repo = ArtworkRepository(root, ArtworkPreferences(node))
            val picture = Surface.makeRasterN32Premul(120, 90).makeImageSnapshot()
            val key = ArtworkKeys.virtualFolder("jane doe")
            repo.saveImage(key, ArtworkMode.CUSTOM, picture, manual = true)
            val chosen = repo.row(key)!!.imageName
            assertTrue(repo.imageFile(chosen)!!.isFile)
            assertTrue(ArtworkRepository.tallFile(repo.imageFile(chosen)!!).isFile)
            repo.saveImage(key, ArtworkMode.PORTRAIT, picture, manual = false, candidate = candidate("Jane Doe"))
            assertEquals(chosen, repo.row(key)!!.imageName)
            assertTrue(repo.row(key)!!.manual)
            // A looked-up portrait elsewhere is cleared by "Clear looked-up portraits"; the chosen one stays.
            val other = ArtworkKeys.matchFolder("m1")
            repo.saveImage(other, ArtworkMode.PORTRAIT, picture, manual = false, candidate = candidate("Ann Lee"))
            assertEquals(1, repo.clearFetchedPortraits())
            assertEquals(null, repo.row(other))
            assertNotEquals(null, repo.row(key))
            // Stored rows come back after a restart.
            assertEquals(chosen, ArtworkRepository(root, ArtworkPreferences(node)).row(key)!!.imageName)
        } finally {
            node.removeNode()
            root.toFile().deleteRecursively()
        }
    }
}
