package com.deepak.videolibrary.artwork

import com.deepak.videolibrary.artwork.online.AutoMatch
import com.deepak.videolibrary.artwork.online.EndpointPolicy
import com.deepak.videolibrary.artwork.online.ImageUrlPolicy
import com.deepak.videolibrary.artwork.online.NameCleaner
import com.deepak.videolibrary.artwork.online.PerformerCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameRulesTest {
    @Test fun videoNameTakesTheTextBeforeCommaOrDash() {
        assertEquals("tom cruise", NameCleaner.forVideo("tom cruise- movie.mp4"))
        assertEquals("tom cruise", NameCleaner.forVideo("tom cruise, brad pitt- movie.mkv"))
        assertEquals("tom cruise", NameCleaner.forVideo("tom cruise - movie"))
        assertEquals("Jane Doe", NameCleaner.forVideo("Jane_Doe.scene1.mp4".replace(".scene1", "")))
        assertEquals("", NameCleaner.forVideo("2024-01-01.mp4"))
    }

    @Test fun videoNameIgnoresFolders() {
        assertEquals("tom cruise", NameCleaner.forVideo("Movies/Action/tom cruise- movie.mp4"))
    }

    @Test fun folderUsesTheWholeNameWhenOn() {
        assertEquals("Tom Cruise, Brad Pitt - Movies", NameCleaner.forFolder("Tom Cruise, Brad Pitt - Movies", whole = true))
        assertEquals("Example Performer", NameCleaner.forFolder("Videos/Example_Performer/", whole = true))
        assertEquals("Tom Cruise", NameCleaner.forFolder("Tom Cruise, Brad Pitt - Movies", whole = false))
    }

    @Test fun settingsChooseTheRulePerScope() {
        val s = ArtworkSettings()
        assertEquals("tom cruise", s.searchTerm(ArtworkScope.VIDEO, "tom cruise, brad pitt- movie.mp4"))
        assertEquals("tom cruise, brad pitt- movie", s.searchTerm(ArtworkScope.VIRTUAL_FOLDER, "tom cruise, brad pitt- movie").trim())
        val off = s.copy(videoUseFirstName = false, folderUseWholeName = false)
        assertEquals("tom cruise", off.searchTerm(ArtworkScope.SMB_FOLDER, "tom cruise, brad pitt- movie"))
    }

    @Test fun normalizeIgnoresCaseAccentsAndPunctuation() {
        assertEquals("rene o brien", NameCleaner.normalize("René  O'Brien"))
    }

    private fun candidate(name: String, hint: String? = null, image: String? = "https://cdn.example.com/a.jpg") =
        PerformerCandidate("p", "P", name.hashCode().toString(), name, hint, image, image)

    @Test fun autoApplyNeedsOneExactMatch() {
        assertTrue(AutoMatch.evaluate("Tom Cruise", listOf(candidate("Tom Cruise"), candidate("Tom Hardy"))) is AutoMatch.Result.Match)
        assertTrue(AutoMatch.evaluate("Tom Cruise", listOf(candidate("Tom Cruise"), candidate("tom  cruise"))) is AutoMatch.Result.Ambiguous)
        assertTrue(AutoMatch.evaluate("Tom Cruise", listOf(candidate("Tom Cruise", hint = "the other one"))) is AutoMatch.Result.Ambiguous)
        assertTrue(AutoMatch.evaluate("Tom Cruise", listOf(candidate("Tom Cruiser"))) is AutoMatch.Result.NoMatch)
        assertTrue(AutoMatch.evaluate("Tom Cruise", listOf(candidate("Tom Cruise", image = null))) is AutoMatch.Result.NoMatch)
        assertTrue(AutoMatch.evaluate("Al", listOf(candidate("Al"))) is AutoMatch.Result.NoMatch)
    }

    @Test fun imageAddressesMustBeSafeHttps() {
        assertTrue(ImageUrlPolicy.isAllowed("https://cdn.example.com/p/1.jpg"))
        assertFalse(ImageUrlPolicy.isAllowed("http://cdn.example.com/p/1.jpg"))
        assertFalse(ImageUrlPolicy.isAllowed("file:///sdcard/1.jpg"))
        assertFalse(ImageUrlPolicy.isAllowed("javascript:alert(1)"))
        assertFalse(ImageUrlPolicy.isAllowed("https://192.168.1.5/a.jpg"))
        assertFalse(ImageUrlPolicy.isAllowed("https://localhost/a.jpg"))
        assertFalse(ImageUrlPolicy.isAllowed("https://user:pw@cdn.example.com/a.jpg"))
        assertFalse(ImageUrlPolicy.isAllowed("https://cdn.example.com:8443/a.jpg"))
        assertFalse(ImageUrlPolicy.isAllowed(null))
        assertFalse(ImageUrlPolicy.isAllowed(""))
    }

    @Test fun customApiAddressesAreHttpsOnly() {
        assertTrue(EndpointPolicy.isAllowed("https://stash.example.org/graphql"))
        assertTrue(EndpointPolicy.isAllowed("https://192.168.1.20:9999/graphql"))
        assertFalse(EndpointPolicy.isAllowed("http://stash.example.org/graphql"))
        assertFalse(EndpointPolicy.isAllowed("https://user:pw@stash.example.org/graphql"))
        assertFalse(EndpointPolicy.isAllowed("stash.example.org"))
        assertEquals("https://stash.example.org/graphql", EndpointPolicy.normalize(" https://stash.example.org/graphql/ "))
        assertEquals(null, EndpointPolicy.normalize("ftp://x.example.org"))
    }

    @Test fun videoSearchTriesTheCommaNameThenTheHyphenName() {
        assertEquals(listOf("tom cruise", "tom cruise, brad pitt"), NameCleaner.videoTerms("tom cruise, brad pitt - movie.mp4"))
        assertEquals(listOf("tom cruise"), NameCleaner.videoTerms("tom cruise - movie.mp4"))
    }
}
