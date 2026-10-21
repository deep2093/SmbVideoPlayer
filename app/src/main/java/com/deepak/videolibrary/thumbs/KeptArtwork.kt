package com.deepak.videolibrary.thumbs

import android.content.Context
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.artwork.ArtworkKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Keeps the thumbnails and covers you chose for videos when they leave the library (hidden, a folder taken out of
 * a scan, a drive offline) and gives them back when the same file returns, even though it then has a new id. So a
 * removed video never needs a new thumbnail or artwork. Settings > Artwork & thumbnails can reset everything.
 */
object KeptArtwork {
    /** Videos no longer in the library whose choices are kept; the oldest go first beyond this. */
    private const val MAX_KEPT = 3000

    private fun prefs(context: Context, libraryId: String) =
        context.applicationContext.getSharedPreferences("kept_artwork-$libraryId", Context.MODE_PRIVATE)

    internal fun decode(text: String?): Map<Long, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(text)
            o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { id -> o.optString(k).takeIf { it.isNotBlank() }?.let { id to it } } }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    internal fun encode(map: Map<Long, String>): String = JSONObject().apply { map.forEach { (id, path) -> put(id.toString(), path) } }.toString()

    /**
     * Pairs old ids with the new id of the same file: (old id -> new id) for every kept video that is back.
     * [ledger] is old id -> path; [live] is path -> current id.
     */
    internal fun returning(ledger: Map<Long, String>, liveIds: Set<Long>, live: Map<String, Long>): List<Pair<Long, Long>> =
        ledger.mapNotNull { (oldId, path) -> if (oldId in liveIds) null else live[path]?.takeIf { it != oldId }?.let { oldId to it } }

    /** Moves choices of returning videos to their new ids, records paths, and removes only what cannot come back. */
    suspend fun reconcile(context: Context, services: ServiceLocator) = withContext(Dispatchers.IO) {
        val storage = services.artworkContext
        CustomThumbs.preload(storage)
        services.artwork.ensureLoaded()
        val live = services.db.dao().allIdPaths()
        // An empty index (first run, or a database problem) is never a reason to delete anything.
        if (live.isEmpty()) return@withContext
        val liveIds = live.mapTo(HashSet()) { it.id }
        val idOfPath = live.associate { it.relativePath to it.id }
        val pathOfId = live.associate { it.id to it.relativePath }
        val store = prefs(context, services.libraryId)
        val ledger = decode(store.getString("paths", null))

        val moves = returning(ledger, liveIds, idOfPath)
        moves.forEach { (from, to) -> CustomThumbs.move(storage, from, to) }
        services.artwork.moveExact(moves.map { (from, to) -> ArtworkKeys.video(from) to ArtworkKeys.video(to) })

        val artworkIds = services.artwork.allKeys().mapNotNull { key -> key.takeIf { it.startsWith("video:") }?.removePrefix("video:")?.toLongOrNull() }
        val chosen = CustomThumbs.ids(storage) + artworkIds
        val next = LinkedHashMap<Long, String>()
        chosen.sorted().forEach { id -> (pathOfId[id] ?: ledger[id])?.let { next[id] = it } }
        val gone = next.keys.filter { it !in liveIds }
        gone.take((gone.size - MAX_KEPT).coerceAtLeast(0)).forEach { next.remove(it) }
        store.edit().putString("paths", encode(next)).apply()

        val keep = next.keys.filterTo(HashSet()) { it !in liveIds }
        CustomThumbs.prune(storage, liveIds, keep)
        services.artwork.pruneVideos(liveIds, keep)
    }

    /** Forgets every kept path (after the user resets all thumbnails and artwork). */
    fun forgetAll(context: Context, libraryId: String) {
        prefs(context, libraryId).edit().remove("paths").apply()
    }
}
