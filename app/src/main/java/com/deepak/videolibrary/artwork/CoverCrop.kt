package com.deepak.videolibrary.artwork

import android.graphics.Bitmap

/** Cropping an image to a card shape while letting the user slide the visible part (faces are usually near the top). */
/** A window inside an image, in pixels. */
data class CropWindow(val left: Int, val top: Int, val width: Int, val height: Int)

object CoverCrop {
    /** Landscape cards and thumbnails are 16:9. */
    const val CARD_ASPECT = 16f / 9f

    /** Taller cards (a page can switch to them) are 3:4. */
    const val TALL_ASPECT = 3f / 4f

    /**
     * The part of a [width] x [height] image to keep for [aspect] (width / height). [focusX] and [focusY] are 0..1
     * and say where the kept window sits within the room available: 0 = start, 0.5 = centre, 1 = end.
     */
    fun rect(width: Int, height: Int, aspect: Float, focusX: Float, focusY: Float): CropWindow {
        require(width > 0 && height > 0 && aspect > 0f)
        val imageAspect = width.toFloat() / height
        val fx = focusX.coerceIn(0f, 1f)
        val fy = focusY.coerceIn(0f, 1f)
        return if (imageAspect > aspect) {
            val w = (height * aspect).toInt().coerceIn(1, width)
            val x = ((width - w) * fx).toInt()
            CropWindow(x, 0, w, height)
        } else {
            val h = (width / aspect).toInt().coerceIn(1, height)
            val y = ((height - h) * fy).toInt()
            CropWindow(0, y, width, h)
        }
    }

    /** Returns a new bitmap holding only the chosen window, scaled to at most [maxWidth] wide. */
    fun apply(source: Bitmap, aspect: Float, focusX: Float, focusY: Float, maxWidth: Int): Bitmap {
        val r = rect(source.width, source.height, aspect, focusX, focusY)
        val cropped = Bitmap.createBitmap(source, r.left, r.top, r.width, r.height)
        if (cropped.width <= maxWidth) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, maxWidth, maxOf(1, (maxWidth.toLong() * cropped.height / cropped.width).toInt()), true)
        if (scaled !== cropped && cropped !== source) cropped.recycle()
        return scaled
    }
}
