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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

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
    suspend fun run(profile: SmbProfile, shouldStop: () -> Boolean): Boolean = mutex.withLock { runLocked(profile, shouldStop) }

    suspend fun <T> exclusive(action: suspend () -> T): T = mutex.withLock { action() }

    private suspend fun runLocked(profile: SmbProfile, shouldStop: () -> Boolean): Boolean {
        ThumbStore.thumbDir(context).mkdirs()
        // One SMB connection per worker; workers never share a connection.
        val connections = arrayOfNulls<SmbConnection>(WORKERS)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                waitWhileHot()
                val batch = dao.nextPendingThumbs(BATCH)
                if (batch.isEmpty()) {
                    if (shouldStop()) return true
                    delay(IDLE_POLL_MS)
                    continue
                }
                if (!processBatch(profile, batch, connections)) return false
            }
        } finally {
            withContext(NonCancellable) { connections.forEach { c -> c?.let { runCatching { it.close() } } } }
        }
    }

    /**
     * Works through [batch] with up to [WORKERS] parallel connections. Returns false when the
     * run should stop (the server cannot be reached or keeps failing); unfinished videos stay
     * PENDING and are retried on the next run.
     */
    private suspend fun processBatch(profile: SmbProfile, batch: List<VideoEntity>, connections: Array<SmbConnection?>): Boolean {
        val queue = java.util.concurrent.ConcurrentLinkedQueue(batch)
        val transientStreak = java.util.concurrent.atomic
            .AtomicInteger(0)
        val abort = java.util.concurrent.atomic
            .AtomicBoolean(false)
        coroutineScope {
            repeat(WORKERS) { worker ->
                launch {
                    while (!abort.get()) {
                        currentCoroutineContext().ensureActive()
                        val video = queue.poll() ?: break
                        val c = connections[worker] ?: openConnection(profile)?.also { connections[worker] = it }
                        if (c == null) {
                            abort.set(true)
                            break
                        }
                        when (process(c, video)) {
                            Outcome.DONE, Outcome.FAILED -> transientStreak.set(0)
                            Outcome.TRANSIENT -> {
                                // Network trouble: the video stays PENDING and is retried on the next run.
                                if (transientStreak.incrementAndGet() >= MAX_TRANSIENT_STREAK) {
                                    abort.set(true)
                                    break
                                }
                                try {
                                    withContext(Dispatchers.IO) { c.reconnect() }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    abort.set(true)
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }
        return !abort.get()
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
        val source = AtomicReference<SmbMediaDataSource?>()
        val frame = AtomicReference<Bitmap?>()
        val timedOut = java.util.concurrent.atomic
            .AtomicBoolean(false)
        try {
            val bitmap = coroutineScope {
                val extraction = async(Dispatchers.IO) {
                    runInterruptible {
                        val ds = SmbMediaDataSource(conn, video.relativePath, video.size)
                        source.set(ds)
                        try {
                            extractFrame(ds)?.also { frame.set(it) }
                        } finally {
                            ds.close()
                        }
                    }
                }
                val watchdog = launch(Dispatchers.IO) {
                    delay(PER_VIDEO_TIMEOUT_MS)
                    timedOut.set(true)
                    source.get()?.close()
                }
                try {
                    val result = withTimeoutOrNull(PER_VIDEO_TIMEOUT_MS) { extraction.await() to true }
                    if (result == null) timedOut.set(true)
                    result?.first
                } finally {
                    watchdog.cancel()
                    if (!extraction.isCompleted) {
                        withContext(NonCancellable + Dispatchers.IO) { source.get()?.close() }
                        extraction.cancel()
                    }
                }
            }
            if (bitmap == null) {
                // A deadline without a decoder result is retryable, not proof of a bad file.
                if (source.get()?.lastError?.let { SmbErrors.isTransient(it) } == true || timedOut.get()) return Outcome.TRANSIENT
                dao.setThumbStatus(
                    video.id,
                    STATUS_FAILED,
                    source.get()?.lastError?.let { SmbErrors.describe(it) }
                        ?: "Android could not extract a video frame within 90 seconds. The file may use an unsupported codec, contain no video track, or be damaged; Android did not provide an exact cause.",
                )
                return Outcome.FAILED
            }
            val target = ThumbStore.thumbFile(context, video.id)
            withContext(Dispatchers.IO) { save(bitmap, target) }
            frame.getAndSet(null)?.recycle()
            ImageMemoryCache.remove(target.path)
            dao.setThumbStatus(video.id, STATUS_DONE)
            return Outcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (timedOut.get() || SmbErrors.isTransient(e)) return Outcome.TRANSIENT
            dao.setThumbStatus(
                video.id,
                STATUS_FAILED,
                if (e is IllegalArgumentException ||
                    e is IllegalStateException
                ) {
                    "Android could not read a video frame (${e.javaClass.simpleName}). Check whether the file plays and whether its video codec is supported."
                } else {
                    SmbErrors.describe(e)
                },
            )
            return Outcome.FAILED
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { source.get()?.close() }
            frame.getAndSet(null)?.recycle()
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
        ArtworkFiles.publish(target) { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)) { "Could not encode thumbnail" }
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

        /** Parallel SMB connections used for artwork; two allow overlapping IO and extraction; throughput is device-dependent. */
        const val WORKERS = 2
        const val IDLE_POLL_MS = 3_000L
        const val PER_VIDEO_TIMEOUT_MS = 90_000L
        const val MAX_TRANSIENT_STREAK = 3
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
    }
}
