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
import kotlinx.coroutines.flow.map
import com.deepak.videolibrary.artwork.online.PerformerCandidate
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
        zoom: Float = 1f,
    ) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val name = "${hash(key)}_${System.currentTimeMillis()}_${sequence.incrementAndGet()}.jpg"
                val target = File(imageDir().apply { mkdirs() }, name)
                // One picture per card shape (landscape 16:9 and taller 3:4), cut around the same focus point.
                val cropped = CoverCrop.apply(image, CoverCrop.CARD_ASPECT, focusX, focusY, IMAGE_WIDTH, zoom)
                try {
                    ArtworkFiles.publish(target) { out ->
                        check(cropped.compress(Bitmap.CompressFormat.JPEG, 88, out)) { "Could not encode cover" }
                    }
                } finally {
                    if (cropped !== image) cropped.recycle()
                }
                val tall = CoverCrop.apply(image, CoverCrop.TALL_ASPECT, focusX, focusY, TALL_WIDTH, zoom)
                try {
                    ArtworkFiles.publish(tallFile(target)) { out ->
                        check(tall.compress(Bitmap.CompressFormat.JPEG, 88, out)) { "Could not encode cover" }
                    }
                } finally {
                    if (tall !== image) tall.recycle()
                }
                val previous = rows[key]?.imageName
                val row = ArtworkOverride(key, mode, manual, name, providerId, performerId, performerName, ArtworkOverride.STATUS_OK, System.currentTimeMillis())
                dao.upsert(ArtworkRow.of(row))
                rows[key] = row
                // A chosen picture settles any suggestions that were waiting for this item.
                dao.deleteCandidates(listOf(key))
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
                    dao.deleteCandidates(chunk)
                }
            }
        }
        if (removed > 0) changes.value++
        return removed
    }

    /** Keys equal to [prefix] or below it as a path (`original:a` covers `original:a/b`). */
    private fun keysUnder(prefix: String): List<String> = rows.keys.filter { it == prefix || it.startsWith("$prefix/") }

    /**
     * Follows a rename or move made in the app: the stored cover of [from] (and, for SMB folders, of its sub-folders)
     * moves to [to]. A manual cover already stored at the destination wins.
     */
    suspend fun move(from: String, to: String) {
        if (from == to) return
        var changed = false
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                for (old in keysUnder(from)) {
                    val row = rows.remove(old) ?: continue
                    val newKey = to + old.removePrefix(from)
                    dao.delete(old)
                    dao.deleteCandidates(listOf(old))
                    changed = true
                    val existing = rows[newKey]
                    if (existing != null && existing.manual) {
                        row.imageName?.let { deleteImage(it) }
                        continue
                    }
                    existing?.imageName?.let { deleteImage(it) }
                    val moved = row.copy(key = newKey)
                    dao.upsert(ArtworkRow.of(moved))
                    rows[newKey] = moved
                }
            }
        }
        if (changed) changes.value++
    }

    /** Removes the stored cover of [prefix] and, for SMB folders, of everything below it. */
    suspend fun clearUnder(prefix: String): Int = clear(keysUnder(prefix))

    /** Keys of [prefix] itself and of the keys written as "[prefix]:…" (a Virtual folder and its subfolders). */
    fun keysStartingWith(prefix: String): List<String> = rows.keys.filter { it == prefix || it.startsWith("$prefix:") }

    // ---- Suggestions waiting for the user ----

    /** Folders with suggestions to choose from (newest information from the database). */
    val reviewQueue: kotlinx.coroutines.flow.Flow<List<ArtworkOverride>> =
        dao.observeReview().map { list -> list.map { it.toModel() } }

    suspend fun suggestions(key: String): List<PerformerCandidate> = withContext(Dispatchers.IO) {
        dao.candidates(key).map {
            PerformerCandidate(it.providerId, it.providerName, it.performerId, it.name, it.disambiguation, it.imageUrl, it.previewUrl)
        }
    }

    /**
     * Keeps [candidates] for [key] so the user can pick one later, and marks the folder as waiting for review
     * (or as having no match when the list is empty). Never replaces a manual choice.
     */
    suspend fun saveSuggestions(key: String, candidates: List<PerformerCandidate>) {
        val existing = rows[key]
        if (existing != null && existing.manual) return
        withContext(Dispatchers.IO) {
            dao.deleteCandidates(listOf(key))
            val keep = candidates.filter { !it.imageUrl.isNullOrBlank() }.distinctBy { it.providerId to it.performerId }.take(MAX_SUGGESTIONS)
            dao.insertCandidates(
                keep.mapIndexed { i, c ->
                    ArtworkCandidateRow(key, c.providerId, c.performerId, c.providerName, c.name, c.disambiguation, c.imageUrl, c.previewUrl, i)
                },
            )
        }
        val hasAny = candidates.any { !it.imageUrl.isNullOrBlank() }
        recordLookup(key, if (hasAny) ArtworkOverride.STATUS_REVIEW else ArtworkOverride.STATUS_NO_MATCH)
        changes.value++
    }

    /** The user skipped a folder in the review list: forget its suggestions and do not suggest again automatically. */
    suspend fun skipSuggestions(key: String) {
        withContext(Dispatchers.IO) { dao.deleteCandidates(listOf(key)) }
        recordLookup(key, ArtworkOverride.STATUS_NO_MATCH)
        changes.value++
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
        val tall = tallFile(file)
        tall.delete()
        ImageMemoryCache.remove(tall.path)
    }

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    companion object {
        const val IMAGE_WIDTH = 640
        const val TALL_WIDTH = 480
        const val MAX_SUGGESTIONS = 12

        /** The 3:4 version saved next to a chosen picture. */
        fun tallFile(file: File): File = File(file.parentFile, file.nameWithoutExtension + "_t.jpg")

        /**
         * The picture to show on a card of the given shape: for taller cards the 3:4 version when one exists (chosen
         * pictures have one); otherwise the picture itself, which the card crops from its centre.
         */
        fun forShape(file: File?, tall: Boolean): File? {
            if (!tall || file == null) return file
            val variant = tallFile(file)
            return if (variant.isFile) variant else file
        }
    }
}
