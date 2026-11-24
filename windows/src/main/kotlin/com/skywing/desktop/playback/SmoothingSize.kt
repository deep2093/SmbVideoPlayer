package com.skywing.desktop.playback

/** A size in physical pixels. */
internal data class PixelSize(val width: Int, val height: Int) {
    override fun toString() = "$width × $height"

    companion object {
        fun of(width: Int?, height: Int?): PixelSize? =
            if (width != null && height != null && width > 0 && height > 0) PixelSize(width, height) else null
    }
}

/**
 * "Smooth at screen size" (plan 5.7 item 4): a video larger than the screen is shrunk to fit it before the motion
 * search, so smoothing works on fewer pixels. The screen's size is its current mode in physical pixels, in a window
 * too, so resizing the window never rebuilds the filter.
 */
internal object SmoothingSize {
    /**
     * The size to smooth a video stored at [video] and shown at [shown] (its display aspect) on a [screen]: it fits
     * inside the screen at its own aspect, both sides shrunk alike and rounded down to even numbers, which 4:2:0 video
     * needs. Null when the video already fits: it is never scaled up.
     */
    fun fit(video: PixelSize, shown: PixelSize, screen: PixelSize): PixelSize? {
        // The scale is num/den, from the side that reaches the screen's edge first.
        val widthFirst = screen.width.toLong() * shown.height <= screen.height.toLong() * shown.width
        val num = if (widthFirst) screen.width.toLong() else screen.height.toLong()
        val den = if (widthFirst) shown.width.toLong() else shown.height.toLong()
        if (num >= den) return null
        fun even(side: Int) = (side * num / den).let { it - it % 2 }.toInt()
        return PixelSize.of(even(video.width), even(video.height))
    }

    /** The report's "Smoothing size:" line. [size] is what smoothing runs at, or null for the video's own size. */
    fun line(on: Boolean, size: PixelSize?, video: PixelSize?, screen: PixelSize?): String {
        val screenText = screen?.let { "screen $it" } ?: "screen size not reported"
        if (on && size != null) return "Smoothing size: $size ($screenText)"
        val why = if (on) screenText else "Smooth at screen size is off"
        return "Smoothing size: the video's own" + (video?.let { ", $it" } ?: "") + " ($why)"
    }
}
