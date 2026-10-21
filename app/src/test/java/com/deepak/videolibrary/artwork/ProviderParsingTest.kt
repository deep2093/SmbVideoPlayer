package com.deepak.videolibrary.artwork

import com.deepak.videolibrary.artwork.online.ProviderException
import com.deepak.videolibrary.artwork.online.ProviderFailure
import com.deepak.videolibrary.artwork.online.StashDbProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderParsingTest {
    @Test fun stashDbPrefersPortraitImages() {
        val json = """{"data":{"queryPerformers":{"performers":[
            {"id":"x","name":"Jane Doe","disambiguation":null,"images":[
              {"url":"https://cdn.example.com/wide.jpg","width":1920,"height":1080},
              {"url":"https://cdn.example.com/tall.jpg","width":800,"height":1200},
              {"url":"http://bad.example.com/tall2.jpg","width":2000,"height":3000}]}
        ]}}}"""
        val list = StashDbProvider.parse(json)
        assertEquals(1, list.size)
        assertEquals("https://cdn.example.com/tall.jpg", list[0].imageUrl)
        assertNull(list[0].disambiguation)
        // Every usable photo is offered, best first; the insecure one is left out.
        assertEquals(listOf("https://cdn.example.com/tall.jpg", "https://cdn.example.com/wide.jpg"), list[0].images)
    }

    @Test fun stashDbAuthErrorsAreReported() {
        try {
            StashDbProvider.parse("""{"errors":[{"message":"Unauthorized: invalid api key"}],"data":null}""")
            fail("expected a failure")
        } catch (e: ProviderException) {
            assertEquals(ProviderFailure.UNAUTHORIZED, e.failure)
        }
    }
}
