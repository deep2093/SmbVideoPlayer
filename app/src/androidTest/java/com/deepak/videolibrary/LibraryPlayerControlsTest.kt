package com.deepak.videolibrary

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.deepak.videolibrary.ui.LibraryPlayerControls
import com.deepak.videolibrary.ui.VideoLibraryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryPlayerControlsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun transportHasTapAlternativesAndSeekStaysInsideMedia() {
        var toggles = 0
        var seek = -1L
        var next = 0
        var previous = 0
        var tracks = 0
        var options = 0
        rule.setContent {
            VideoLibraryTheme {
                LibraryPlayerControls(
                    title = "Playback test.mp4", position = 25_000, duration = 30_000,
                    isPlaying = true, canNext = true,
                    onBack = {}, onTogglePlayback = { toggles++ }, onSeek = { seek = it },
                    onPrevious = { previous++ }, onNext = { next++ },
                    onTracks = { tracks++ }, onOptions = { options++ },
                )
            }
        }
        rule.onNodeWithContentDescription("Pause").performClick()
        rule.onNodeWithContentDescription("Forward 10 seconds").performClick()
        rule.runOnIdle { assertEquals(1, toggles); assertEquals(30_000L, seek) }
        rule.onNodeWithContentDescription("Rewind 10 seconds").performClick()
        rule.runOnIdle { assertEquals(15_000L, seek) }
        rule.onNodeWithContentDescription("Previous video").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Next video").performClick()
        rule.onNodeWithContentDescription("Audio and subtitles").performClick()
        rule.onNodeWithContentDescription("Playback options").performClick()
        rule.onNodeWithContentDescription("Playback position").assertExists()
        rule.runOnIdle {
            assertEquals(0, previous)
            assertEquals(1, next)
            assertEquals(1, tracks)
            assertEquals(1, options)
        }
    }
}
