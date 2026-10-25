package com.skywing.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortSettingsTest {
    @Test fun droppedPicturesGiveTheirAddress() {
        assertEquals("https://cdn.example.com/a.jpg", pictureInHtml("""<html><body><img alt="x" src="https://cdn.example.com/a.jpg"></body></html>"""))
        assertEquals("https://encrypted-tbn0.gstatic.com/images?q=a&s=1", pictureInHtml("""<img src='https://encrypted-tbn0.gstatic.com/images?q=a&amp;s=1'>"""))
        // A Google result link leads to the picture itself.
        assertEquals("https://site.example/pic.png", pictureAddress("https://www.google.com/imgres?imgurl=https%3A%2F%2Fsite.example%2Fpic.png&imgrefurl=https%3A%2F%2Fsite.example%2F"))
        assertEquals("data:image/png;base64,AAAA", pictureAddress("data:image/png;base64,AAAA"))
        assertEquals("https://a.example/b.gif", pictureAddress("# comment\r\nhttps://a.example/b.gif\r\n"))
        assertNull(pictureAddress("just some text"))
        assertNull(pictureInHtml("<p>no picture</p>"))
    }

    @Test fun sharesAreRescannedFromTheirRoot() {
        assertEquals("""\\192.168.71.249\g""", LibrarySync.shareRootOf("""\\192.168.71.249\g\1\a.mp4"""))
        assertNull(LibrarySync.shareRootOf("""C:\Videos\a.mp4"""))
        assertNull(LibrarySync.shareRootOf("""\\server"""))
        assertEquals("never", LibrarySync.ago(0))
        assertEquals("3 h ago", LibrarySync.ago(1_000L, now = 1_000L + 3 * 3_600_000L))
    }

    @Test fun adaptiveColoursFollowTheCovers() {
        val style = skyThemes.first { it.id == "ADAPTIVE_GLOW" }.adaptive!!
        assertEquals(style.fallback, adaptivePalette(emptyList(), style))
        assertEquals(style.fallbackAccent, adaptiveAccent(emptyList(), style))
        val palette = adaptivePalette(listOf(Color(0xFFE03030), Color(0xFF3040E0)), style)
        assertEquals(3, palette.size)
        // The most colourful sample wins; an all-grey picture gives its average.
        val red = Color(0xFFE03030).toArgb()
        assertEquals(red, CoverColors.pickMain(intArrayOf(Color(0xFF808080).toArgb(), red)))
        val grey = CoverColors.pickMain(intArrayOf(Color(0xFF404040).toArgb(), Color(0xFF606060).toArgb()))!!
        assertTrue(Color(grey).red in 0.30f..0.33f)
    }
}
