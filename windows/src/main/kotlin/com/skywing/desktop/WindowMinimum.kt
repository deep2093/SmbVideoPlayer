package com.skywing.desktop

import java.awt.Dimension
import kotlin.math.ceil

/**
 * Keeps the main window at least 640 × 480 dp at any Windows scaling (plan 6.8 rule 5). Java gives Windows its
 * minimum in raw screen pixels (JDK-8221452), so at 150 % the window could be dragged to about 427 × 320 dp.
 */
internal object WindowMinimum {
    /**
     * The smallest outer window Windows should allow, in screen pixels: the visible window at 640 × 480 dp for
     * [scale], rounded up, plus the invisible resize borders ([borderWidth], [borderHeight]), never larger than the
     * work area ([workWidth], [workHeight]), so maximising and full screen are never blocked.
     */
    fun trackSize(scale: Double, borderWidth: Int, borderHeight: Int, workWidth: Int, workHeight: Int): Dimension =
        Dimension(
            minOf(ceil(WindowSizing.MIN_WIDTH * scale).toInt() + borderWidth, workWidth),
            minOf(ceil(WindowSizing.MIN_HEIGHT * scale).toInt() + borderHeight, workHeight),
        )
}
