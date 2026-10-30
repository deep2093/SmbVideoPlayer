package com.deepak.videolibrary.player

import org.junit.Assert.*
import org.junit.Test

class OutputFramePacerTest {
    @Test fun targetsNinetyOnHighRefreshDisplays() {
        for (hz in listOf(90, 120, 144)) {
            val pacer = OutputFramePacer(90.0)
            assertEquals("Display $hz Hz", 90, (0 until hz).count { pacer.shouldDraw(it * 1_000_000_000L / hz) })
        }
    }

    @Test fun targetsSixtyAcrossCommonDisplays() {
        for (hz in listOf(60, 90, 120, 144)) {
            val pacer = OutputFramePacer()
            val drawn = (0 until hz).count { pacer.shouldDraw(it * 1_000_000_000L / hz) }
            assertEquals("Display $hz Hz", 60, drawn)
        }
    }

    @Test fun missedVsyncDoesNotCauseBurstOrPermanentDrift() {
        val pacer = OutputFramePacer()
        assertTrue(pacer.shouldDraw(0))
        assertTrue(pacer.shouldDraw(100_000_000))
        assertFalse(pacer.shouldDraw(101_000_000))
        assertTrue(pacer.shouldDraw(116_666_667))
    }

    @Test fun displaySlowerThanTargetDrawsEveryRefresh() {
        val pacer = OutputFramePacer()
        assertEquals(50, (0 until 50).count { pacer.shouldDraw(it * 20_000_000L) })
    }
}
