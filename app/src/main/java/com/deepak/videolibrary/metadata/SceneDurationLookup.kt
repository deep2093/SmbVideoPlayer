package com.deepak.videolibrary.metadata

import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.thumbs.SmbMediaDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * Read-only duration probe used only for visible metadata review items.
 * It never downloads the complete video and keeps a small bounded in-memory cache.
 */
internal object SceneDurationLookup {
    private val cache = LruCache<String, Long>(240)
    private val reads = Semaphore(2)

    suspend fun durationMs(service: ServiceLocator, video: VideoEntity): Long? {
        if (video.size <= 0) return null
        val cacheKey = "${service.libraryId}:${video.relativePath}:${video.size}:${video.modified}"
        synchronized(cache) { cache.get(cacheKey) }?.let { return it.takeIf { value -> value > 0L } }
        reads.acquire()
        return try {
            synchronized(cache) { cache.get(cacheKey) }?.let { return it.takeIf { value -> value > 0L } }
            val duration = withContext(Dispatchers.IO) {
                val conn = SmbConnection(service.settings.toProfile(service.settings.load()))
                var source: SmbMediaDataSource? = null
                val retriever = MediaMetadataRetriever()
                try {
                    conn.open()
                    val openSource = SmbMediaDataSource(conn, video.relativePath, video.size)
                    source = openSource
                    retriever.setDataSource(openSource)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        ?.takeIf { it > 0L }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } finally {
                    runCatching { retriever.release() }
                    runCatching { source?.close() }
                    runCatching { conn.close() }
                }
            }
            synchronized(cache) { cache.put(cacheKey, duration ?: 0L) }
            duration
        } finally {
            reads.release()
        }
    }
}

internal fun sceneDurationLabel(milliseconds: Long?): String {
    if (milliseconds == null || milliseconds <= 0) return "Length unavailable"
    val seconds = milliseconds / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, remainder)
        else "%d:%02d".format(java.util.Locale.ROOT, minutes, remainder)
}
