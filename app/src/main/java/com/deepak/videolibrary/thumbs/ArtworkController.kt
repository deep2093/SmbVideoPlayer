package com.deepak.videolibrary.thumbs

import androidx.compose.runtime.*
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Coordinates all artwork caches and serializes destructive maintenance with generators. */
internal class ArtworkController(private val services: ServiceLocator) {
    private val files = android.util.LruCache<String,File>(100)
    var revision by mutableIntStateOf(0)
    fun key(name: String,count: Int,done: Int,modified: Long) = "${services.libraryId}:$name:$count:$done:$modified:$revision:${services.artwork.revision.value}:${services.scenePosters.revision.value}"
    fun cached(key: String): File? = files.get(key)?.takeIf { it.isFile }
    /**
     * The last cover built for each folder and what it was built from. When artwork changes somewhere else (a
     * portrait saved for another folder), this folder's cover is reused instead of being rebuilt, so the screen does
     * not flash while a lookup runs.
     */
    private val built = java.util.concurrent.ConcurrentHashMap<String, Pair<String, File>>()

    /** Drops everything: thumbnails or settings changed for all folders. */
    fun invalidate() { files.evictAll(); built.clear(); revision++ }

    /** Stored artwork changed for some folders: screens look again, but unchanged folders keep their covers. */
    fun refresh() { revision++ }
    suspend fun cover(identity: String, key: String, scope: LibraryScope): File? {
        cached(key)?.let { return it }
        val art = services.artwork
        val decision = art.decideFolder(identity)
        when (decision.source) {
            com.deepak.videolibrary.artwork.ArtworkSource.NONE -> return null
            com.deepak.videolibrary.artwork.ArtworkSource.IMAGE ->
                art.imageFile(decision.imageName)?.let { files.put(key, it); return it }
            else -> Unit
        }
        val style = when (decision.source) {
            com.deepak.videolibrary.artwork.ArtworkSource.FIRST_FRAME -> com.deepak.videolibrary.artwork.ArtworkMode.FIRST_FRAME
            com.deepak.videolibrary.artwork.ArtworkSource.COLLAGE -> com.deepak.videolibrary.artwork.ArtworkMode.COLLAGE
            else -> null
        }
        return try {
            val ids = withContext(Dispatchers.IO) { services.db.libraryState().ids(LibraryQueries.coverIds(scope)) }
            val metadataOptions = services.sceneMetadataPreferences.settings.value
            if (ids.size == 1 && metadataOptions.enabled && metadataOptions.folderPosters &&
                metadataOptions.posterMode != com.deepak.videolibrary.metadata.PosterMode.DISABLED &&
                decision.source == com.deepak.videolibrary.artwork.ArtworkSource.LEGACY &&
                art.effectiveRow(identity)?.manual != true) {
                val video = withContext(Dispatchers.IO) { services.db.dao().findVideoById(ids.first()) }
                if (video != null && !CustomThumbs.has(services.artworkContext, video.id)) {
                    services.scenePosters.fileFor(video)?.let { files.put(key, it); return it }
                }
            }
            val tiles = art.settings.value.collageTiles
            val signature = "$decision|$style|$tiles|${ids.size}|${ids.hashCode()}"
            built[identity]?.takeIf { it.first == signature && it.second.isFile }?.let { (_, file) ->
                files.put(key, file)
                return file
            }
            val made = if (style != null) {
                services.covers.buildStyled(identity, ids, style, tiles)
            } else {
                services.covers.buildOriginal(identity, ids)
            }
            made?.also {
                files.put(key, it)
                built[identity] = signature to it
            }
        } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
    }
    suspend fun clear() = services.thumbnails.exclusive {
        services.covers.exclusive {
            withContext(Dispatchers.IO) { ThumbStore.clearAll(services.artworkContext) }
            services.db.dao().resetAllThumbs()
            invalidate()
        }
    }
}
