package com.deepak.videolibrary.thumbs

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Thumbnails the user picked by hand. They take priority over the automatic thumbnail everywhere.
 *
 * - Stored in their own folder (`files/custom_thumbs`), separate from the automatic thumbnail cache, so
 *   "Clear cache", automatic regeneration and rescans never remove them. Only Reset (or deleting the video)
 *   removes one.
 * - Keyed by the library's stable video id, like automatic thumbnails.
 * - Each save gets a new file name (`<id>_<time>.jpg`) so every screen reloads the new image at once; the
 *   previous file for that video is then deleted, so there is never more than one per video.
 */
object CustomThumbs {
    const val WIDTH = 640
    private const val QUALITY = 85

    /** One index per library storage folder: video id -> its custom thumbnail file. */
    private val indexes = ConcurrentHashMap<String, ConcurrentHashMap<Long, File>>()

    private val changes = MutableStateFlow(0)

    /** Increases whenever any custom thumbnail is saved or removed, so screens can refresh. */
    val revision: StateFlow<Int> = changes.asStateFlow()

    fun dir(context: Context) = File(context.filesDir, "custom_thumbs")

    /** Parses `<id>_<time>.jpg`; null for anything else. */
    internal fun parse(name: String): Pair<Long, Long>? {
        if (!name.endsWith(".jpg")) return null
        val parts = name.removeSuffix(".jpg").split('_')
        if (parts.size != 2) return null
        val id = parts[0].toLongOrNull() ?: return null
        val stamp = parts[1].toLongOrNull() ?: return null
        return id to stamp
    }

    private fun index(context: Context): ConcurrentHashMap<Long, File> {
        val folder = dir(context)
        return indexes.getOrPut(folder.path) { load(folder) }
    }

    /** Reads the folder once; if a crash ever left two files for one video, the newest wins and the rest go. */
    private fun load(folder: File): ConcurrentHashMap<Long, File> {
        val newest = HashMap<Long, Pair<Long, File>>()
        folder.listFiles()?.forEach { file ->
            val (id, stamp) = parse(file.name) ?: return@forEach
            val current = newest[id]
            if (current == null || stamp > current.first) {
                current?.second?.delete()
                newest[id] = stamp to file
            } else {
                file.delete()
            }
        }
        return ConcurrentHashMap(newest.mapValues { it.value.second })
    }

    /** Forgets the in-memory index so the next call re-reads the folder, as after an app restart (tests). */
    internal fun dropIndexForTest() = indexes.clear()

    /** Loads the index ahead of time (call from a background thread). */
    fun preload(context: Context) {
        index(context)
    }

    fun fileFor(context: Context, videoId: Long): File? = if (videoId <= 0) null else index(context)[videoId]

    fun has(context: Context, videoId: Long): Boolean = fileFor(context, videoId) != null

    /**
     * Saves [frame] as the custom thumbnail of [videoId], scaled to [WIDTH] wide with its aspect ratio kept.
     * Blocking (encode and disk); call off the main thread. The previous custom thumbnail survives a failure.
     */
    @Synchronized
    fun save(context: Context, videoId: Long, frame: Bitmap): File {
        require(videoId > 0) { "Only library videos can have a custom thumbnail" }
        val scaled = if (frame.width > WIDTH) {
            Bitmap.createScaledBitmap(frame, WIDTH, maxOf(1, (WIDTH.toLong() * frame.height / frame.width).toInt()), true)
        } else {
            frame
        }
        val target = File(dir(context), "${videoId}_${System.currentTimeMillis()}.jpg")
        try {
            ArtworkFiles.publish(target) { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, output)) { "Could not encode thumbnail" }
            }
        } finally {
            if (scaled !== frame) scaled.recycle()
        }
        val previous = index(context).put(videoId, target)
        if (previous != null && previous != target) {
            previous.delete()
            ImageMemoryCache.remove(previous.path)
        }
        changes.value++
        return target
    }

    /** Removes the custom thumbnail of [videoId], if any, so the automatic one shows again. */
    @Synchronized
    fun reset(context: Context, videoId: Long): Boolean {
        val removed = index(context).remove(videoId) ?: return false
        removed.delete()
        ImageMemoryCache.remove(removed.path)
        changes.value++
        return true
    }

    /** Deletes custom thumbnails of videos that were deleted. */
    @Synchronized
    fun forget(context: Context, videoIds: Collection<Long>) {
        var any = false
        videoIds.forEach { id ->
            index(context).remove(id)?.let {
                it.delete()
                ImageMemoryCache.remove(it.path)
                any = true
            }
        }
        if (any) changes.value++
    }

    /** Deletes custom thumbnails whose video is no longer in the library. Blocking. */
    @Synchronized
    fun prune(context: Context, liveIds: Set<Long>) {
        val stale = index(context).keys.filter { it !in liveIds }
        if (stale.isNotEmpty()) forget(context, stale)
    }
}
