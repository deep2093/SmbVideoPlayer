package com.deepak.videolibrary.thumbs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.deepak.videolibrary.data.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Builds the cover image for a virtual folder from the thumbnails of its videos:
 * one video -> that thumbnail, two -> two panels, three or more -> a three-panel collage
 * from the first, middle and last video. Covers are cached on disk and only rebuilt when the
 * chosen videos change.
 */
class CoverBuilder(private val context: Context, db: LibraryDatabase) {
    private val dao = db.dao()
    private val gate = Semaphore(2)
    suspend fun <T> exclusive(action: suspend () -> T): T {
        gate.acquire()
        try { gate.acquire() } catch (e: Throwable) { gate.release(); throw e }
        return try { action() } finally { gate.release(); gate.release() }
    }
    private val groupLocks = Array(32) { Mutex() }
    private fun lock(key: String) = groupLocks[(key.hashCode() and Int.MAX_VALUE) % groupLocks.size]

    suspend fun build(group: String): File? = gate.withPermit {
        lock("virtual:$group").withLock { withContext(Dispatchers.IO) { compose("virtual:$group", dao.doneIdsForGroup(group)) } }
    }

    suspend fun buildOriginal(path: String, ids: List<Long>): File? = gate.withPermit {
        lock("original:$path").withLock { withContext(Dispatchers.IO) { compose("original:$path", ids) } }
    }

    private fun compose(group: String, ids: List<Long>): File? {
        if (ids.isEmpty()) return null

        val picks = when (ids.size) {
            1 -> listOf(ids[0])
            2 -> ids
            else -> listOf(ids.first(), ids[ids.size / 2], ids.last())
        }

        val dir = ThumbStore.coverDir(context).apply { mkdirs() }
        val prefix = java.security.MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(group.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
        val fingerprint = picks.joinToString("-") { id ->
            val file = ThumbStore.thumbFile(context, id)
            "$id-${file.lastModified()}-${file.length()}"
        }
        val target = File(dir, "${prefix}_$fingerprint.jpg")
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

            // Publish first; retain the previous cover if writing fails.
            ArtworkFiles.publish(target) { output ->
                check(out.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "Could not encode folder artwork" }
            }
            dir.listFiles { f -> f != target && f.name.startsWith("${prefix}_") }?.forEach { it.delete() }
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
