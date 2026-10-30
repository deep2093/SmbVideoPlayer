package com.deepak.videolibrary.player

import org.junit.Assert.*
import org.junit.Test

class MotionReliabilityTest {
    @Test fun cutIsRejectedEvenWhenMatcherHasNoConfidentVectors() {
        for (strength in 0..2) {
            val decision = MotionReliability.decide(100, 0, 90, strength)
            assertTrue(decision.sceneCut)
            assertTrue(decision.holdFrame)
        }
    }

    @Test fun cleanMotionImmediatelyRecoversAfterCut() {
        MotionReliability.decide(100, 90, 90, 1)
        assertFalse(MotionReliability.decide(100, 0, 0, 1).holdFrame)
    }

    @Test fun qualityModesHaveDifferentFallbackThresholds() {
        assertTrue(MotionReliability.decide(100, 5, 0, 0).holdFrame)
        assertFalse(MotionReliability.decide(100, 5, 0, 1).holdFrame)
        assertTrue(MotionReliability.decide(100, 40, 0, 1).holdFrame)
        assertFalse(MotionReliability.decide(100, 40, 0, 2).holdFrame)
    }
}
