package com.skywing.desktop

import com.skywing.desktop.automation.MotionStatus
import com.skywing.desktop.automation.holdingRate
import com.skywing.desktop.playback.SmoothingCapabilities
import com.skywing.desktop.playback.SmoothingMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The automation tests and the handheld runner read SkyWing's motion status line through [MotionStatus]. */
class MotionStatusTest {
    @Test fun everyReasonSmoothingCannotStartStopsTheWaitWithThatReason() {
        val decoded = SmoothingCapabilities(sourceFps = 24.0, gpuOutput = true, hardwareDecode = true)
        listOf(
            decoded.copy(sourceFps = 120.0),
            decoded.copy(hdrVideo = true),
            decoded.copy(hardwareDecode = false),
            decoded.copy(sourceFps = 23.06080436706543),
        ).forEach { caps ->
            val reason = assertNotNull(caps.unavailable(SmoothingMode.MOTION), "SkyWing gives no reason for $caps")
            assertTrue(MotionStatus.LINE.containsMatchIn(reason), "The status line \"$reason\" isn't recognised")
            val failure = assertFailsWith<AssertionError> { holdingRate(reason) }
            assertEquals("Motion smoothing did not start: $reason", failure.message)
        }
    }

    @Test fun aWaitForHoldingNamesTheStatusSkyWingShows() {
        assertEquals(59.94, holdingRate("Holding 59.94 fps · 0 drops in last minute"))
        // 0.1.101 shows this for a rate it can't smooth, and a time-out has to say so.
        val waiting = assertFailsWith<AssertionError> { holdingRate("Waiting for source frame rate") }
        assertEquals("SkyWing shows “Waiting for source frame rate”", waiting.message)
    }
}
