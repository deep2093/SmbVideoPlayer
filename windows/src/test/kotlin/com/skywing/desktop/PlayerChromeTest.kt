package com.skywing.desktop

import org.junit.Test
import kotlin.test.*

class PlayerChromeTest {
    @Test fun inputRestartsTheTwoSecondDeadline() {
        val clock = PlayerChromeClock()
        clock.activity(100)
        assertTrue(clock.visible(2099, true, false))
        assertFalse(clock.visible(2100, true, false))
        clock.activity(3000)
        assertTrue(clock.visible(4999, true, false))
        assertFalse(clock.visible(5000, true, false))
    }
    @Test fun pauseSeekingHoverAndMenusKeepControlsVisibleThenAllowAFullGracePeriod() {
        val clock = PlayerChromeClock()
        assertTrue(clock.visible(10000, true, true))
        assertTrue(clock.visible(11999, true, false))
        assertFalse(clock.visible(12000, true, false))
        assertTrue(clock.visible(20000, false, false))
        assertTrue(clock.visible(21999, true, false))
    }

    // M3 item 10: a click or tap on the video hides the bars at once in full screen, even while something holds them.
    @Test fun aClickOnTheVideoHidesShownBarsAtOnce() {
        val clock = PlayerChromeClock()
        clock.activity(1000)
        assertTrue(clock.visible(1500, true, false))
        clock.videoClick(1500)
        assertFalse(clock.visible(1500, true, false))
        assertFalse(clock.visible(1600, true, true), "paused or hovered, the bars stay hidden after the click")
    }

    @Test fun aClickOnTheVideoShowsHiddenBars() {
        val clock = PlayerChromeClock()
        clock.activity(1000)
        assertFalse(clock.visible(4000, true, false))
        clock.videoClick(4000)
        assertTrue(clock.visible(4000, true, false))
    }

    @Test fun theTapThatShowsTheBarsDoesNotAlsoHideThem() {
        // A tap moves the hidden pointer (seen by the poll) and clicks (seen by mpv): one tap, bars shown.
        val clock = PlayerChromeClock()
        clock.activity(1000)
        assertFalse(clock.visible(4000, true, false))
        clock.pointerMoved(4000)
        clock.videoClick(4080)
        assertTrue(clock.visible(4100, true, false))
    }

    @Test fun thePointerMoveOfTheHidingTapDoesNotBringTheBarsBack() {
        val clock = PlayerChromeClock()
        clock.activity(1000)
        clock.videoClick(1500)
        clock.pointerMoved(1580)
        assertFalse(clock.visible(1600, true, false))
        clock.pointerMoved(2000)
        assertTrue(clock.visible(2000, true, false), "a later move shows them again")
    }
}
