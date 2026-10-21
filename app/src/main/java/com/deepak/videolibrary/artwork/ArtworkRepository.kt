package com.deepak.videolibrary.artwork

import android.content.Context
import android.graphics.Bitmap
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.thumbs.ArtworkFiles
import com.deepak.videolibrary.thumbs.CustomThumbs
import com.deepak.videolibrary.thumbs.ImageMemoryCache
import com.deepak.videolibrary.thumbs.ThumbStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The single place the UI, scan jobs and manual overrides go through for cover art.
 *
 * Rows live in a small database but are mirrored in memory, so choosing a cover while scrolling a list is a map
 * lookup. Stored images sit in their own folder and are never touched by "Clear cache" (only manual choices
 * would be lost); the SMB drive is never written to.
 */
class ArtworkRepository(
    private val context: Context,
    private val db: ArtworkDatabase,
    val preferences: ArtworkPreferences,
) {
    private val dao = db.dao()
    private val rows = ConcurrentHashMap<String, ArtworkOverride>()
    private val writeLock = Mutex()
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    private val changes = MutableStateFlow(0)

    /** Increases whenever settings or stored artwork change, so screens rebuild what they show. */
    val revision: StateFlow<Int> = changes.asStateFlow()

    val settings: StateFlow<ArtworkSettings> get() = preferences.settings

    private fun imageDir() = File(context.filesDir, "artwork_images")

    private val loadLock = Mutex()
    @Volatile private var loaded = false

    /** Reads the stored rows once. Safe to call from several places. */
    suspend fun ensureLoaded() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            val all = withContext(Dispatchers.IO) { dao.all() }
            all.forEach { rows[it.key] = it.toModel() }
            loaded = true
            changes.value++
        }
    }

    /** Call after changing settings so covers are rebuilt. */
    fun settingsChanged() {
        changes.value++
    }

    fun row(key: String): ArtworkOverride? = rows[key]

    fun imageFile(name: String?): File? = name?.let { File(imageDir(), it) }?.takeIf { it.isFile }

    // ---- Deciding what to show ----

    /** The decision for a folder (identity such as `virtual:Name` or `original:path`). */
    fun decideFolder(identity: String): ArtworkDecision {
        val scope = ArtworkKeys.scopeOf(identity)
        val decision = ArtworkResolver.decide(scope, rows[identity], null, false, settings.value)
        if (decision.source == ArtworkSource.IMAGE && imageFile(decision.imageName) == null) {
            // The picture is gone (cleared storage): fall back to the existing cover rather than showing nothing.
            return ArtworkDecision(ArtworkSource.LEGACY)
        }
        return decision
    }

    /** The picture to show for a video, or null for the neutral placeholder. */
    fun videoFile(video: VideoEntity): File? {
        val legacy = { ThumbStore.displayFile(context, video.id, video.thumbStatus) }
        val s = settings.value
        if (!s.enabled) return legacy()
        val group = rows[ArtworkKeys.virtualFolder(video.customGroup ?: video.groupKey)]
        val decision = ArtworkResolver.decide(
            ArtworkScope.VIDEO,
            rows[ArtworkKeys.video(video.id)],
            group,
            CustomThumbs.has(context, video.id),
            s,
        )
        return when (decision.source) {
            ArtworkSource.IMAGE -> imageFile(decision.imageName) ?: legacy()
            ArtworkSource.NONE -> null
            ArtworkSource.FRAME -> ThumbStore.thumbFile(context, video.id).takeIf { video.thumbStatus == "DONE" && it.isFile }
            else -> legacy()
        }
    }

    // ---- Changing artwork ----

    /**
     * Stores [image] as the cover of [key]. Manual choices ([manual] true) beat every default; automatic ones
     * (portraits fetched in the background) only apply while the matching default is on.
     */
    suspend fun saveImage(
        key: String,
        mode: ArtworkMode,
        image: Bitmap,
        manual: Boolean,
        providerId: String? = null,
        performerId: String? = null,
        performerName: String? = null,
        focusX: Float = 0.5f,
        focusY: Float = 0.3f,
    ) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val cropped = CoverCrop.apply(image, CoverCrop.CARD_ASPECT, focusX, focusY, IMAGE_WIDTH)
                val name = "${hash(key)}_${System.currentTimeMillis()}_${sequence.incrementAndGet()}.jpg"
                val target = File(imageDir().apply { mkdirs() }, name)
                try {
                    ArtworkFiles.publish(target) { out ->
                        check(cropped.compress(Bitmap.CompressFormat.JPEG, 88, out)) { "Could not encode cover" }
                    }
                } finally {
                    if (cropped !== image) cropped.recycle()
                }
                val previous = rows[key]?.imageName
                val row = ArtworkOverride(key, mode, manual, name, providerId, performerId, performerName, ArtworkOverride.STATUS_OK, System.currentTimeMillis())
                dao.upsert(ArtworkRow.of(row))
                rows[key] = row
                if (previous != null && previous != name) deleteImage(previous)
            }
        }
        changes.value++
    }

    /** Stores a mode that needs no picture (first frame, collage, none, existing) as a manual choice. */
    suspend fun setMode(key: String, mode: ArtworkMode) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val previous = rows[key]?.imageName
                val row = ArtworkOverride(key, mode, true, null, null, null, null, ArtworkOverride.STATUS_OK, System.currentTimeMillis())
                dao.upsert(ArtworkRow.of(row))
                rows[key] = row
                if (previous != null) deleteImage(previous)
            }
        }
        changes.value++
    }

    /** Remembers that an automatic lookup finished without applying anything, so it is not repeated on every scan. */
    suspend fun recordLookup(key: String, status: String) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val existing = rows[key]
                if (existing != null && existing.manual) return@withLock
                val row = (existing ?: ArtworkOverride(key, ArtworkMode.PORTRAIT, false)).copy(lookupStatus = status, updatedAt = System.currentTimeMillis())
                dao.upsert(ArtworkRow.of(row))
                rows[key] = row
            }
        }
    }

    /** Removes everything stored for [keys]: the item goes back to the inherited default. Returns how many had data. */
    suspend fun clear(keys: Collection<String>): Int {
        if (keys.isEmpty()) return 0
        var removed = 0
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                keys.chunked(500).forEach { chunk ->
                    chunk.forEach { key ->
                        rows.remove(key)?.let {
                            removed++
                            it.imageName?.let { name -> deleteImage(name) }
                        }
                    }
                    dao.deleteAll(chunk)
                }
            }
        }
        if (removed > 0) changes.value++
        return removed
    }

    /** Removes only automatically fetched portraits (manual choices are kept). */
    suspend fun clearFetchedPortraits(): Int {
        val keys = rows.values.filter { !it.manual && it.imageName != null }.map { it.key }
        return clear(keys)
    }

    /** True when nothing is stored for this folder yet, so an automatic lookup may be tried. */
    fun needsLookup(identity: String): Boolean = rows[identity] == null

    fun hasOverride(key: String): Boolean = rows[key]?.manual == true

    fun hasStoredData(key: String): Boolean = rows.containsKey(key)

    /** Drops rows and images for videos that no longer exist. */
    suspend fun pruneVideos(liveIds: Set<Long>) {
        val stale = rows.keys.filter { it.startsWith("video:") && (it.removePrefix("video:").toLongOrNull() ?: -1L) !in liveIds }
        clear(stale)
    }

    private fun deleteImage(name: String) {
        val file = File(imageDir(), name)
        file.delete()
        ImageMemoryCache.remove(file.path)
    }

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    companion object {
        const val IMAGE_WIDTH = 640
    }
}
