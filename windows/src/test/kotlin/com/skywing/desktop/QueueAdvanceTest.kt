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
}
