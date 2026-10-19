package com.deepak.videolibrary.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun preservesUnfinishedVideoAndRestartsCompletedVideo() {
        assertEquals(45_000L, PlaybackPolicy.resumePosition(45_000L, 180_000L))
        assertEquals(0L, PlaybackPolicy.resumePosition(175_000L, 180_000L))
        assertEquals(0L, PlaybackPolicy.resumePosition(180_000L, 180_000L))
        assertEquals(0L, PlaybackPolicy.resumePosition(-10L, 0L))
        assertEquals(45_000L, PlaybackPolicy.resumePosition(45_000L, 0L))
    }
}
