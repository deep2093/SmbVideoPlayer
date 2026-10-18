package com.deepak.videolibrary.thumbs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.deepak.videolibrary.data.LibraryDatabase
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Builds the cover image for a virtual folder from the thumbnails of its videos:
 * one video -> that thumbnail, two -> two panels, three or more -> a three-panel collage
 * from the first, middle and last video. Covers are cached on disk and only rebuilt when the
 * chosen videos change.
 */
class CoverBuilder(private val context: Context, db: LibraryDatabase) {

    private val dao = db.dao()
    private val gate = Semaphore(2)

    suspend fun build(group: String): File? = gate.withPermit {
        withContext(Dispatchers.IO) { compose(group) }
    }

    private suspend fun compose(group: String): File? {
        val ids = dao.doneIdsForGroup(group)
        if (ids.isEmpty()) return null

        val picks = when (ids.size) {
            1 -> listOf(ids[0])
            2 -> ids
            else -> listOf(ids.first(), ids[ids.size / 2], ids.last())
        }

        val dir = ThumbStore.coverDir(context).apply { mkdirs() }
        val prefix = group.hashCode().toUInt().toString(16)
        val target = File(dir, "${prefix}_${picks.joinToString("-")}.jpg")
        if (target.exists()) return target

        val bitmaps = picks.mapNotNull { id ->
            ThumbStore.thumbFile(context, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        }
        if (bitmaps.isEmpty()) return null

        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val half = (WIDTH - GAP) / 2
            when (bitmaps.size) {
                1 -> draw(canvas, bitmaps[0], Rect(0, 0, WIDTH, HEIGHT), paint)
                2 -> {
                    draw(canvas, bitmaps[0], Rect(0, 0, half, HEIGHT), paint)
                    draw(canvas, bitmaps[1], Rect(WIDTH - half, 0, WIDTH, HEIGHT), paint)
                }
                else -> {
                    draw(canvas, bitmaps[0], Rect(0, 0, half, HEIGHT), paint)
                    draw(canvas, bitmaps[1], Rect(WIDTH - half, 0, WIDTH, (HEIGHT - GAP) / 2), paint)
                    draw(canvas, bitmaps[2], Rect(WIDTH - half, (HEIGHT + GAP) / 2, WIDTH, HEIGHT), paint)
                }
            }

            // Remove older covers of this folder, then write the new one.
            dir.listFiles { f -> f.name.startsWith("${prefix}_") }?.forEach { it.delete() }
            val tmp = File(dir, target.name + ".tmp")
            FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } finally {
            bitmaps.forEach { it.recycle() }
            out.recycle()
        }
        return target.takeIf { it.exists() }
    }

    /** Draws [bmp] into [dst], cropping from the centre so nothing is stretched. */
    private fun draw(canvas: Canvas, bmp: Bitmap, dst: Rect, paint: Paint) {
        val dstRatio = dst.width().toFloat() / dst.height()
        val srcRatio = bmp.width.toFloat() / bmp.height
        val src = if (srcRatio > dstRatio) {
            val w = (bmp.height * dstRatio).toInt()
            val x = (bmp.width - w) / 2
            Rect(x, 0, x + w, bmp.height)
        } else {
            val h = (bmp.width / dstRatio).toInt()
            val y = (bmp.height - h) / 2
            Rect(0, y, bmp.width, y + h)
        }
        canvas.drawBitmap(bmp, src, dst, paint)
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val GAP = 4
    }
}
