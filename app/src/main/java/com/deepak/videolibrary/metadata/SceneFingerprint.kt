package com.deepak.videolibrary.metadata

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.thumbs.SmbMediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Read-only OpenSubtitles hash, compatible with Stash OSHASH.
 * It fetches only 64 KiB from each end, not a full copy of the video.
 */
internal object SceneFingerprint {
    private const val SEGMENT = 64 * 1024

    suspend fun oshash(conn: SmbConnection, video: VideoEntity): String = withContext(Dispatchers.IO) {
        require(video.size >= SEGMENT.toLong())
        conn.openFile(video.relativePath).use { file ->
            var sum = video.size
            for (start in longArrayOf(0, video.size - SEGMENT)) {
                val buffer = ByteArray(SEGMENT)
                var done = 0
                while (done < SEGMENT) {
                    currentCoroutineContext().ensureActive()
                    val n = file.read(buffer, start + done, done, SEGMENT - done)
                    if (n <= 0) throw java.io.EOFException("Incomplete fingerprint read")
                    done += n
                }
                val values = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                repeat(SEGMENT / 8) { sum += values.long }
            }
            java.lang.Long.toUnsignedString(sum, 16).padStart(16, '0')
        }
    }

    /**
     * Experimental Android implementation of Stash's 5x5 frame-montage perceptual hash.
     * pHash is for match SUGGESTIONS only until compatibility is confirmed against Stash on real devices.
     * The decoder reads selected SMB regions and may be slow for high bitrate 4K files.
     */
    suspend fun phash(conn: SmbConnection, video: VideoEntity): String? = withContext(Dispatchers.IO) {
        val source = SmbMediaDataSource(conn, video.relativePath, video.size)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { it > 0 } ?: return@withContext null
            val first = retriever.getFrameAtTime((durationMs * 0.05 * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext null
            val height = (160.0 * first.height / first.width).toInt().coerceIn(2, 360)
            val collage = Bitmap.createBitmap(800, height * 5, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(collage)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            fun drawFrame(bitmap: Bitmap, i: Int) {
                val rect = android.graphics.Rect(i % 5 * 160, i / 5 * height, (i % 5 + 1) * 160, (i / 5 + 1) * height)
                canvas.drawBitmap(bitmap, null, rect, paint)
            }
            drawFrame(first, 0)
            first.recycle()
            for (i in 1 until 25) {
                currentCoroutineContext().ensureActive()
                val ms = durationMs * (0.05 + 0.9 * i / 25.0)
                val frame = retriever.getFrameAtTime((ms * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: run { collage.recycle(); return@withContext null }
                drawFrame(frame, i)
                frame.recycle()
            }
            val downscaled = Bitmap.createScaledBitmap(collage, 64, 64, true)
            collage.recycle()
            try { perceptualDct(downscaled) } finally { downscaled.recycle() }
        } finally {
            retriever.release()
            source.close()
        }
    }

    /** Low 8x8 coefficients of a 64x64 DCT-II, thresholded by the median. */
    internal fun perceptualDct(bitmap: Bitmap): String {
        val px = IntArray(64 * 64)
        bitmap.getPixels(px, 0, 64, 0, 0, 64, 64)
        val grey = DoubleArray(64 * 64) { i ->
            val c = px[i]
            0.299 * ((c ushr 16) and 255) + 0.587 * ((c ushr 8) and 255) + 0.114 * (c and 255)
        }
        val cosines = Array(8) { k -> DoubleArray(64) { x -> cos(PI * (x + 0.5) * k / 64.0) } }
        val horizontal = Array(64) { y -> DoubleArray(8) { u ->
            var total = 0.0
            for (x in 0 until 64) total += grey[y * 64 + x] * cosines[u][x]
            total
        } }
        val coeff = DoubleArray(64)
        for (v in 0 until 8) for (u in 0 until 8) {
            var total = 0.0
            for (y in 0 until 64) total += horizontal[y][u] * cosines[v][y]
            coeff[v * 8 + u] = total
        }
        val ordered = coeff.sorted()
        val median = (ordered[31] + ordered[32]) / 2
        var hash = 0L
        coeff.forEachIndexed { i, value ->
            if (value > median) hash = hash or (1L shl (63 - i))
        }
        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }
}
