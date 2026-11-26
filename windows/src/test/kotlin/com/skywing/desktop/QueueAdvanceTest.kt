package com.skywing.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueAdvanceTest {
    @Test fun movesOnWhenAutoNext() {
        assertEquals(1, QueueAdvance.next(0, 3, true))
    }

    @Test fun lastVideoHasNoNext() {
        assertNull(QueueAdvance.next(2, 3, true))
    }

    @Test fun singleVideoHasNoNext() {
        assertNull(QueueAdvance.next(0, 1, true))
    }

    @Test fun staysWhenNotAutoNext() {
        assertNull(QueueAdvance.next(0, 3, false))
    }

    @Test fun endAdvancesWhenNothingChanged() {
        assertEquals(1, QueueAdvance.afterEnd(0, 3, true, sameOpen = true, sameFile = true, onPlayerPage = true))
    }

    @Test fun endDoesNothingWhenOpenCountMoved() {
        assertNull(QueueAdvance.afterEnd(0, 3, true, sameOpen = false, sameFile = true, onPlayerPage = true))
    }

    @Test fun endDoesNothingWhenFileChanged() {
        assertNull(QueueAdvance.afterEnd(0, 3, true, sameOpen = true, sameFile = false, onPlayerPage = true))
    }

    @Test fun endDoesNothingWhenPlayerWasLeft() {
        assertNull(QueueAdvance.afterEnd(0, 3, true, sameOpen = true, sameFile = true, onPlayerPage = false))
    }
}
