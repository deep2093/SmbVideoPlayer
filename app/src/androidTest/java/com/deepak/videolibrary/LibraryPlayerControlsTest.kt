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
    @Test fun unknownLengthDisablesSeekingButKeepsEngineActions() {
        var seeks = 0
        var pip = 0
        var queue = 0
        rule.setContent {
            VideoLibraryTheme {
                LibraryPlayerControls(title = "Unknown duration", position = 0, duration = 0,
                    isPlaying = false, canPlay = false, onBack = {}, onTogglePlayback = {},
                    onSeek = { seeks++ }, onTracks = {}, onOptions = {},
                    onPip = { pip++ }, onQueue = { queue++ })
            }
        }
        rule.onNodeWithContentDescription("Play").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Forward 10 seconds").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Rewind 10 seconds").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Picture in picture").performClick()
        rule.onNodeWithContentDescription("Playback queue").performClick()
        rule.runOnIdle { assertEquals(0, seeks); assertEquals(1, pip); assertEquals(1, queue) }
    }

}
