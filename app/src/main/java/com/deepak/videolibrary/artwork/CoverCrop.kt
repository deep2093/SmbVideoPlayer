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
     * [zoom] above 1 shrinks the window (closer in, which also gives room to move sideways in a tall picture);
     * below 1 it grows past the picture's edges (further out), and [apply] fills the extra space with a blurred
     * copy of the picture.
     */
    fun rect(width: Int, height: Int, aspect: Float, focusX: Float, focusY: Float, zoom: Float = 1f): CropWindow {
        require(width > 0 && height > 0 && aspect > 0f)
        val imageAspect = width.toFloat() / height
        val fx = focusX.coerceIn(0f, 1f)
        val fy = focusY.coerceIn(0f, 1f)
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        // The largest window of this shape that fits, then scaled by the zoom.
        val fullW = if (imageAspect > aspect) height * aspect else width.toFloat()
        val fullH = if (imageAspect > aspect) height.toFloat() else width / aspect
        val w = (fullW / z).toInt().let { if (z >= 1f) it.coerceIn(1, width) else it.coerceAtLeast(1) }
        val h = (fullH / z).toInt().let { if (z >= 1f) it.coerceIn(1, height) else it.coerceAtLeast(1) }
        return CropWindow(((width - w) * fx).toInt(), ((height - h) * fy).toInt(), w, h)
    }

    const val MIN_ZOOM = 0.5f
    const val MAX_ZOOM = 3f

    /** Zoom for a slider position 0..1, with 1x (no zoom) in the middle: left zooms out, right zooms in. */
    fun zoomForSlider(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        return if (p <= 0.5f) MIN_ZOOM + (1f - MIN_ZOOM) * (p / 0.5f) else 1f + (MAX_ZOOM - 1f) * ((p - 0.5f) / 0.5f)
    }

    /** True when the window reaches past the picture (zoomed out), so the borders need filling. */
    fun overflows(window: CropWindow, width: Int, height: Int): Boolean =
        window.left < 0 || window.top < 0 || window.left + window.width > width || window.top + window.height > height

    /** Returns a new bitmap holding only the chosen window, scaled to at most [maxWidth] wide. */
    fun apply(source: Bitmap, aspect: Float, focusX: Float, focusY: Float, maxWidth: Int, zoom: Float = 1f): Bitmap {
        val r = rect(source.width, source.height, aspect, focusX, focusY, zoom)
        if (overflows(r, source.width, source.height)) return padded(source, r, maxWidth)
        val cropped = Bitmap.createBitmap(source, r.left, r.top, r.width, r.height)
        if (cropped.width <= maxWidth) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, maxWidth, maxOf(1, (maxWidth.toLong() * cropped.height / cropped.width).toInt()), true)
        if (scaled !== cropped && cropped !== source) cropped.recycle()
        return scaled
    }

    /** Zoomed out: the whole picture inside the window, on a soft blurred and darkened copy of itself. */
    private fun padded(source: Bitmap, r: CropWindow, maxWidth: Int): Bitmap {
        val outW = minOf(maxWidth, r.width).coerceAtLeast(1)
        val outH = maxOf(1, (outW.toLong() * r.height / r.width).toInt())
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        // Background: shrink a lot and stretch back up, which blurs cheaply on every Android version.
        val tiny = Bitmap.createScaledBitmap(source, maxOf(1, source.width / 24), maxOf(1, source.height / 24), true)
        val cover = maxOf(outW.toFloat() / tiny.width, outH.toFloat() / tiny.height)
        val bw = tiny.width * cover
        val bh = tiny.height * cover
        canvas.drawBitmap(tiny, null, android.graphics.RectF((outW - bw) / 2f, (outH - bh) / 2f, (outW + bw) / 2f, (outH + bh) / 2f), paint)
        if (tiny !== source) tiny.recycle()
        canvas.drawColor(android.graphics.Color.argb(90, 0, 0, 0))
        // The picture itself, where the window puts it.
        val scale = outW.toFloat() / r.width
        val left = -r.left * scale
        val top = -r.top * scale
        canvas.drawBitmap(source, null, android.graphics.RectF(left, top, left + source.width * scale, top + source.height * scale), paint)
        return out
    }
}
