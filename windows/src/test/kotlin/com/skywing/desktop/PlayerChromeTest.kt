package com.skywing.desktop

import org.junit.Test
import kotlin.test.*

class PlayerChromeTest {
    @Test fun inputRestartsTwoAndAHalfSecondDeadline() {
        val clock = PlayerChromeClock()
        clock.activity(100)
        assertTrue(clock.visible(2599, true, false))
        assertFalse(clock.visible(2600, true, false))
        clock.activity(3000)
        assertTrue(clock.visible(5499, true, false))
        assertFalse(clock.visible(5500, true, false))
    }
    @Test fun pauseSeekingHoverAndMenusKeepControlsVisibleThenAllowAFullGracePeriod() {
        val clock = PlayerChromeClock()
        assertTrue(clock.visible(10000, true, true))
        assertTrue(clock.visible(12499, true, false))
        assertFalse(clock.visible(12500, true, false))
        assertTrue(clock.visible(20000, false, false))
        assertTrue(clock.visible(22499, true, false))
    }
}
