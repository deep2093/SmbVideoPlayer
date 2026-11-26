package com.skywing.desktop

import java.awt.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals

/** The 640 × 480 dp minimum (plan 6.8 rule 5) in screen pixels, as Windows is given it through WM_GETMINMAXINFO. */
class WindowMinimumTest {
    @Test fun at150PercentTheVisibleWindowIs960By720Pixels() =
        assertEquals(Dimension(960 + 22, 720 + 11), WindowMinimum.trackSize(1.5, 22, 11, 2560, 1528))

    @Test fun theMinimumFollowsTheScaleItIsGiven() {
        assertEquals(Dimension(640 + 14, 480 + 7), WindowMinimum.trackSize(1.0, 14, 7, 1920, 1032))
        assertEquals(Dimension(800 + 18, 600 + 9), WindowMinimum.trackSize(1.25, 18, 9, 2400, 1550))
        assertEquals(Dimension(1280 + 28, 960 + 14), WindowMinimum.trackSize(2.0, 28, 14, 3840, 2080))
    }

    @Test fun aPartPixelRoundsUpSoTheWindowIsNeverUnder640Dp() {
        assertEquals(Dimension(1120, 840), WindowMinimum.trackSize(1.75, 0, 0, 3840, 2080))
        assertEquals(Dimension(747, 560), WindowMinimum.trackSize(1.166, 0, 0, 3840, 2080))
    }

    @Test fun theMinimumNeverExceedsTheWorkArea() =
        assertEquals(Dimension(800, 552), WindowMinimum.trackSize(1.5, 22, 11, 800, 552))

    @Test fun noBordersMeansJustTheScaledMinimum() =
        assertEquals(Dimension(960, 720), WindowMinimum.trackSize(1.5, 0, 0, 2560, 1528))
}
