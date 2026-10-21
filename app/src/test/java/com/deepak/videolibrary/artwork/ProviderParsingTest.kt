package com.deepak.videolibrary.artwork

import com.deepak.videolibrary.artwork.online.ProviderException
import com.deepak.videolibrary.artwork.online.ProviderFailure
import com.deepak.videolibrary.artwork.online.StashDbProvider
import com.deepak.videolibrary.artwork.online.ThePornDbProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderParsingTest {
    @Test fun thePornDbResultsAreParsed() {
        val json = """{"data":[
            {"id":"a1","name":"Jane Doe","disambiguation":"born 1990","image":"https://cdn.example.com/full.jpg","thumbnail":"https://cdn.example.com/t.jpg","face":"https://cdn.example.com/f.jpg"},
            {"_id":"b2","name":"John Roe","image":"http://insecure.example.com/x.jpg","posters":[{"url":"https://cdn.example.com/poster.jpg"}]},
            {"name":"No Id"}
        ]}"""
        val list = ThePornDbProvider.parse(json)
        assertEquals(2, list.size)
        assertEquals("a1", list[0].performerId)
        assertEquals("born 1990", list[0].disambiguation)
        assertEquals("https://cdn.example.com/full.jpg", list[0].imageUrl)
        assertEquals("https://cdn.example.com/t.jpg", list[0].previewUrl)
        // The insecure address is skipped; the poster is used instead.
        assertEquals("https://cdn.example.com/poster.jpg", list[1].imageUrl)
        assertNull(list[1].disambiguation)
    }

    @Test fun thePornDbEmptyAndBrokenResponses() {
        assertTrue(ThePornDbProvider.parse("""{"data":[]}""").isEmpty())
        try {
            ThePornDbProvider.parse("not json")
            fail("expected a failure")
        } catch (e: ProviderException) {
            assertEquals(ProviderFailure.BAD_RESPONSE, e.failure)
        }
    }

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
