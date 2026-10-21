package com.deepak.videolibrary.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/**
 * Joins up to four chosen pictures into one square cover. The square is then positioned for the landscape and
 * taller card shapes like any other picture.
 *
 * Layouts: 1 picture fills the square; 2 sit side by side; 3 are one tall picture on the left with two stacked on
 * the right; 4 make a 2 x 2 grid. Each picture is cut to its tile around the upper part, where faces usually are.
 */
object PhotoCollage {
    const val MAX_PHOTOS = 4
    private const val SIZE = 1200
    private const val GAP = 6

    /** Tile rectangles for [count] pictures inside a [size] square. */
    fun tiles(count: Int, size: Int = SIZE, gap: Int = GAP): List<Rect> {
        val half = (size - gap) / 2
        val right = half + gap
        return when (count.coerceIn(1, MAX_PHOTOS)) {
            1 -> listOf(Rect(0, 0, size, size))
            2 -> listOf(Rect(0, 0, half, size), Rect(right, 0, size, size))
            3 -> listOf(Rect(0, 0, half, size), Rect(right, 0, size, half), Rect(right, right, size, size))
            else -> listOf(Rect(0, 0, half, half), Rect(right, 0, size, half), Rect(0, right, half, size), Rect(right, right, size, size))
        }
    }

    fun compose(pictures: List<Bitmap>): Bitmap {
        require(pictures.isNotEmpty())
        val shown = pictures.take(MAX_PHOTOS)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        tiles(shown.size).zip(shown).forEach { (tile, picture) ->
            val window = CoverCrop.rect(picture.width, picture.height, tile.width().toFloat() / tile.height(), 0.5f, 0.25f)
            val src = Rect(window.left, window.top, window.left + window.width, window.top + window.height)
            canvas.drawBitmap(picture, src, tile, paint)
        }
        return out
    }
}
