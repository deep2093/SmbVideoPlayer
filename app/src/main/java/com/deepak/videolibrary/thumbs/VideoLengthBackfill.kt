package com.deepak.videolibrary.thumbs

import android.content.Context
import android.media.MediaMetadataRetriever
import com.deepak.videolibrary.data.LibraryDatabase
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbErrors
import com.deepak.videolibrary.smb.SmbProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs after a library scan: reads the length of videos that have none saved yet (thumbnails made before lengths
 * were kept). Each video is opened once, over one connection, and only to read its header; a video that already has
 * a length, or one that could not be read before, is never opened again.
 */
class VideoLengthBackfill(private val context: Context, db: LibraryDatabase) {
    private val dao = db.dao()

    /** Returns how many lengths were saved. Stops quietly if the connection fails; the next scan carries on. */
    suspend fun run(profile: SmbProfile): Int {
        val missing = ArrayList<com.deepak.videolibrary.data.VideoEntity>()
        for (block in dao.allIdPaths().chunked(200)) {
            dao.videosByPaths(block.map { it.relativePath }).forEach { video ->
                // Videos still waiting for a thumbnail get their length from that; nothing to read twice.
                if (video.thumbStatus == ThumbnailGenerator.STATUS_DONE && video.size > 0 &&
                    VideoDurations.get(context, video) == null && !VideoDurations.wasTried(context, video)
                ) missing += video
            }
        }
        if (missing.isEmpty()) return 0
        var saved = 0
        val conn = SmbConnection(profile)
        try {
            withContext(Dispatchers.IO) { conn.open() }
            for (video in missing) {
                currentCoroutineContext().ensureActive()
                val length = try {
                    withTimeoutOrNull(PER_VIDEO_MS) { read(conn, video) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (SmbErrors.isTransient(e)) return saved
                    null
                }
                if (length != null && length > 0) {
                    VideoDurations.remember(context, video, length)
                    saved++
                } else {
                    VideoDurations.markTried(context, video)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Could not connect: leave everything for the next scan.
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { conn.close() } }
        }
        return saved
    }

    private suspend fun read(conn: SmbConnection, video: com.deepak.videolibrary.data.VideoEntity): Long? =
        withContext(Dispatchers.IO) {
            runInterruptible {
                val retriever = MediaMetadataRetriever()
                var source: SmbMediaDataSource? = null
                try {
                    val ds = SmbMediaDataSource(conn, video.relativePath, video.size)
                    source = ds
                    retriever.setDataSource(ds)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }
                } finally {
                    runCatching { retriever.release() }
                    runCatching { source?.close() }
                }
            }
        }

    private companion object {
        const val PER_VIDEO_MS = 20_000L
    }
}
