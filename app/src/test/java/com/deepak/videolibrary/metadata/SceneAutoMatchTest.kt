package com.deepak.videolibrary.metadata

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneAutoMatchTest {
    private val order = listOf("tpdb", "stashdb")
    private fun match(provider: String, id: String, title: String, method: String) =
        SceneMatch(provider, JSONObject().put("id", id).put("title", title), method)

    @Test fun bothProvidersNamingTheSameFileUseThePreferredOne() {
        val picked = SceneAutoMatch.choose(
            listOf(match("stashdb", "s1", "Summer Nights", "OSHASH"), match("tpdb", "t1", "Summer Nights", "OSHASH")),
            "x.mp4", order,
        )
        assertEquals("t1", picked?.id)
    }

    @Test fun aProviderNamingTwoScenesForOneFileNeedsReview() {
        val picked = SceneAutoMatch.choose(
            listOf(match("tpdb", "t1", "One", "OSHASH"), match("tpdb", "t2", "Two", "OSHASH")), "x.mp4", order,
        )
        assertNull(picked)
    }

    @Test fun aTitleThatIsWholeInTheFileNameIsApplied() {
        val picked = SceneAutoMatch.choose(
            listOf(match("stashdb", "s1", "Summer Nights", "TITLE"), match("tpdb", "t1", "Summer Nights", "TITLE")),
            "Studio.Summer.Nights.2021.1080p.mp4", order,
        )
        assertEquals("t1", picked?.id)
    }

    @Test fun twoDifferentFittingTitlesNeedReview() {
        val picked = SceneAutoMatch.choose(
            listOf(match("tpdb", "t1", "Summer Nights", "TITLE"), match("tpdb", "t2", "Summer Nights Two", "TITLE")),
            "Summer Nights Two Extended.mp4", order,
        )
        assertNull(picked)
        assertNull(
            SceneAutoMatch.choose(
                listOf(match("tpdb", "t1", "Summer Nights", "TITLE"), match("tpdb", "t3", "Nights Out", "TITLE")),
                "Summer Nights Out.mp4", order,
            ),
        )
    }

    @Test fun partialTitlesAndPhashAreNotAppliedAutomatically() {
        assertNull(SceneAutoMatch.choose(listOf(match("tpdb", "t1", "Summer Nights Extended", "TITLE")), "Summer Nights.mp4", order))
        assertNull(SceneAutoMatch.choose(listOf(match("tpdb", "t1", "Summer Nights", "PHASH")), "Summer Nights.mp4", order))
        assertNull(SceneAutoMatch.choose(listOf(match("tpdb", "t1", "Part 1", "TITLE")), "Part 1.mp4", order))
    }
}
