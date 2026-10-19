package com.deepak.videolibrary.thumbs

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.PowerManager
import com.deepak.videolibrary.data.LibraryDatabase
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbErrors
import com.deepak.videolibrary.smb.SmbProfile
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Creates one cached JPEG per video by grabbing a frame straight from the SMB stream. Only the
 * pieces of the file the decoder asks for are read; nothing is downloaded or changed on the drive.
 */
class ThumbnailGenerator(private val context: Context, db: LibraryDatabase) {

    private val dao = db.dao()

    /** Only one generator runs at a time, so a scan and a manual run never process the same video twice. */
    private val mutex = Mutex()

    private enum class Outcome { DONE, FAILED, TRANSIENT }

    /**
     * Works through videos whose thumbnail is pending. When nothing is pending it either stops
     * (if [shouldStop] says so) or waits for the scanner to find more.
     */
    suspend fun run(profile: SmbProfile, shouldStop: () -> Boolean) {
        mutex.withLock { runLocked(profile, shouldStop) }
    }

    private suspend fun runLocked(profile: SmbProfile, shouldStop: () -> Boolean) {
        ThumbStore.thumbDir(context).mkdirs()
        var conn: SmbConnection? = null
        var transientStreak = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                waitWhileHot()
                val batch = dao.nextPendingThumbs(BATCH)
                if (batch.isEmpty()) {
                    if (shouldStop()) return
                    delay(IDLE_POLL_MS)
                    continue
                }
                for (video in batch) {
                    currentCoroutineContext().ensureActive()
                    val c = conn ?: openConnection(profile) ?: return
                    conn = c
                    when (process(c, video)) {
                        Outcome.DONE, Outcome.FAILED -> transientStreak = 0
                        Outcome.TRANSIENT -> {
                            transientStreak++
                            // Network trouble: videos stay PENDING and are retried on the next run.
                            if (transientStreak >= MAX_TRANSIENT_STREAK) return
                            try {
                                withContext(Dispatchers.IO) { c.reconnect() }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                return
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { conn?.let { runCatching { it.close() } } }
        }
    }

    private suspend fun openConnection(profile: SmbProfile): SmbConnection? {
        val c = SmbConnection(profile)
        return try {
            withContext(Dispatchers.IO) { c.open() }
            c
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { c.close() }
            null
        }
    }

    private suspend fun process(conn: SmbConnection, video: VideoEntity): Outcome {
        var source: SmbMediaDataSource? = null
        try {
            val bitmap = withTimeoutOrNull(PER_VIDEO_TIMEOUT_MS) {
                runInterruptible(Dispatchers.IO) {
                    val ds = SmbMediaDataSource(conn, video.relativePath, video.size)
                    source = ds
                    extractFrame(ds)
                }
            }
            if (bitmap == null) {
                val networkProblem = source?.lastError?.let { SmbErrors.isTransient(it) } ?: false
                if (networkProblem) return Outcome.TRANSIENT
                dao.setThumbStatus(video.id, STATUS_FAILED, source?.lastError?.let {SmbErrors.describe(it)} ?: "Android could not extract a video frame within 90 seconds. The file may use an unsupported codec, contain no video track, or be damaged; Android did not provide an exact cause.")
                return Outcome.FAILED
            }
            val target = ThumbStore.thumbFile(context, video.id)
            withContext(Dispatchers.IO) { save(bitmap, target) }
            bitmap.recycle()
            ImageMemoryCache.remove(target.path)
            dao.setThumbStatus(video.id, STATUS_DONE)
            return Outcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SmbErrors.isTransient(e)) return Outcome.TRANSIENT
            dao.setThumbStatus(video.id, STATUS_FAILED, if(e is IllegalArgumentException || e is IllegalStateException) "Android could not read a video frame (${e.javaClass.simpleName}). Check whether the file plays and whether its video codec is supported." else SmbErrors.describe(e))
            return Outcome.FAILED
        } finally {
            source?.close()
        }
    }

    private fun extractFrame(ds: SmbMediaDataSource): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(ds)
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val t = w
                w = h
                h = t
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val targetW = ThumbStore.THUMB_WIDTH
            val targetH = if (w > 0 && h > 0) maxOf(1, (targetW.toLong() * h / w).toInt()) else 270

            // A frame about 10% in is usually past logos and black intro frames.
            val timeUs = if (durationMs > 0) durationMs * 1000L / 10 else 1_000_000L
            return retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetW, targetH)
                ?: retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetW, targetH)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun save(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    /** Pauses while the phone reports serious heat, so artwork work never makes it worse. */
    private suspend fun waitWhileHot() {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        while (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            currentCoroutineContext().ensureActive()
            delay(30_000)
        }
    }

    private companion object {
        const val BATCH = 10
        const val IDLE_POLL_MS = 3_000L
        const val PER_VIDEO_TIMEOUT_MS = 90_000L
        const val MAX_TRANSIENT_STREAK = 3
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
    }
}
