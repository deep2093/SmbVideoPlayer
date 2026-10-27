package com.deepak.videolibrary.artwork

import com.deepak.videolibrary.artwork.online.TpdbMovies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TpdbMoviesTest {
    private val body = """{"data":[
        {"id":"m1","title":"Summer Nights","date":"2021-06-01","site":{"name":"Studio A"},
         "poster":"https://cdn.example.com/p1.jpg",
         "posters":{"large":"https://cdn.example.com/p1-large.jpg","small":"https://cdn.example.com/p1-small.jpg","full":"https://cdn.example.com/p1-full.jpg"}},
        {"id":"m2","title":"Summer Nights 2","poster":"https://cdn.example.com/p2.jpg"},
        {"id":"m3","title":"No Picture","poster":"http://insecure.example.com/x.jpg"}
    ]}"""

    @Test fun parsesMoviesAndPrefersTheLargePosterOverTheFullOriginal() {
        val movies = TpdbMovies.parse(body)
        assertEquals(3, movies.size)
        assertEquals("https://cdn.example.com/p1-large.jpg", movies[0].posterUrl)
        assertEquals("https://cdn.example.com/p1-small.jpg", movies[0].previewUrl)
        assertEquals("2021", movies[0].year)
        assertEquals("Studio A", movies[0].site)
        assertEquals("https://cdn.example.com/p2.jpg", movies[1].posterUrl)
        assertNull(movies[2].posterUrl)
    }

    @Test fun folderNameMustBeTheWholeTitle() {
        val movies = TpdbMovies.parse(body)
        assertEquals("m1", TpdbMovies.confidentForFolder("summer.nights", movies)?.id)
        assertNull(TpdbMovies.confidentForFolder("Summer", movies))
    }

    @Test fun fileNameMatchNeedsEveryTitleWord() {
        val movies = TpdbMovies.parse(body)
        assertEquals("m1", TpdbMovies.confidentForFile("StudioA.Summer.Nights.2021.1080p.mp4", movies)?.id)
        assertNull(TpdbMovies.confidentForFile("Winter.Days.mp4", movies))
    }
}
