package com.deepak.videolibrary.thumbs

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads single frames from one video on the SMB share for the thumbnail picker.
 *
 * - Uses the same Android frame reader ([MediaMetadataRetriever]) and ranged SMB reads
 *   ([SmbMediaDataSource]) as automatic thumbnails: only the parts of the file the decoder asks for are
 *   read, never the whole video.
 * - One SMB connection and one opened file for the whole picker session; it is reopened only after a
 *   network failure or a timeout.
 * - Requests run one at a time off the main thread. Cancelling a request (a newer slider position)
 *   interrupts its reads, so the decoder gives up early instead of finishing an outdated frame.
 * - [close] releases the decoder, the file and the connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameGrabber(
    private val profile: SmbProfile,
    private val path: String,
    private val size: Long,
) {
    /** What the picker needs to know about the video. [durationMs] is 0 when the file reports no length. */
    data class Info(val durationMs: Long, val width: Int, val height: Int)

    class FrameException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val lane = Dispatchers.IO.limitedParallelism(1)
    private val lock = Mutex()
    private val closed = AtomicBoolean(false)

    private var connection: SmbConnection? = null

    @Volatile private var source: SmbMediaDataSource? = null
    private var retriever: MediaMetadataRetriever? = null
    private var info: Info? = null

    /** Opens the video and reads its length and size. */
    suspend fun open(): Info = lock.withLock { withContext(lane) { ensureOpen() } }

    /**
     * The frame at [timeMs], at most [maxWidth] pixels wide with the aspect ratio kept and rotation applied.
     * [exact] picks the precise frame (slower, for the final choice); otherwise the nearest key frame
     * (fast, while scrubbing). Returns null when the decoder produced no frame.
     */
    suspend fun frame(timeMs: Long, exact: Boolean, maxWidth: Int, timeoutMs: Long = FRAME_TIMEOUT_MS): Bitmap? =
        lock.withLock {
            withContext(lane) {
                val current = ensureOpen()
                val r = retriever ?: throw FrameException("The video is not open")
                val (w, h) = scaledSize(current, maxWidth)
                val timedOut = AtomicBoolean(false)
                val bitmap = coroutineScope {
                    // A slow network must not hang the picker: closing the source makes pending reads fail.
                    val watchdog = launch(Dispatchers.IO) {
                        delay(timeoutMs)
                        timedOut.set(true)
                        source?.close()
                    }
                    try {
                        runInterruptible {
                            r.getScaledFrameAtTime(
                                timeMs.coerceAtLeast(0) * 1000L,
                                if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                w,
                                h,
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // An outdated request was interrupted mid-read; the decoder's state is unknown, so the
                        // next request reopens the file (same connection).
                        releaseDecoder()
                        throw e
                    } finally {
                        watchdog.cancel()
                    }
                }
                val failed = timedOut.get() || source?.lastError != null
                if (bitmap == null && failed) {
                    // The stream is in an unknown state now; start fresh on the next request.
                    val error = source?.lastError
                    releaseDecoder()
                    throw FrameException(
                        if (timedOut.get()) {
                            "The server took too long to send this part of the video. Try again or pick another moment."
                        } else {
                            "The connection to the server was interrupted. Try again."
                        },
                        error,
                    )
                }
                bitmap
            }
        }

    private fun ensureOpen(): Info {
        if (closed.get()) throw FrameException("The picker was closed")
        val known = info
        if (known != null && retriever != null) return known
        val conn = connection ?: SmbConnection(profile).also {
            it.open()
            connection = it
        }
        val ds = try {
            SmbMediaDataSource(conn, path, size)
        } catch (e: Exception) {
            // The connection may have dropped since the last frame; reconnect once.
            conn.reconnect()
            SmbMediaDataSource(conn, path, size)
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ds)
        } catch (e: Exception) {
            runCatching { r.release() }
            ds.close()
            throw FrameException(
                "Android can't read frames from this file. It may use a format only the VLC player supports, or be damaged.",
                e,
            )
        }
        source = ds
        retriever = r
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) {
            val t = w
            w = h
            h = t
        }
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        return Info(duration, w, h).also { info = it }
    }

    private fun releaseDecoder() {
        source?.close()
        source = null
        runCatching { retriever?.release() }
        retriever = null
    }

    /** Releases everything. Safe to call more than once and from any thread. */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Abort any read in progress first, so the lock below is free quickly.
        source?.close()
        withContext(NonCancellable) {
            lock.withLock {
                withContext(lane) {
                    releaseDecoder()
                    runCatching { connection?.close() }
                    connection = null
                }
            }
        }
    }

    /** Closes in the background, for use from a dispose callback. */
    fun closeAsync() {
        cleanupScope.launch { close() }
    }

    companion object {
        const val FRAME_TIMEOUT_MS = 45_000L
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Target size for [maxWidth], keeping the aspect ratio; 16:9 when the size is unknown. */
        internal fun scaledSize(info: Info, maxWidth: Int): Pair<Int, Int> {
            val w = if (info.width > 0) minOf(maxWidth, info.width) else maxWidth
            val h = if (info.width > 0 && info.height > 0) maxOf(1, (w.toLong() * info.height / info.width).toInt()) else w * 9 / 16
            return w to h
        }
    }
}
