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
    fun key(name: String,count: Int,done: Int,modified: Long) = "${services.libraryId}:$name:$count:$done:$modified:$revision:${services.artwork.revision.value}:${services.scenePosters.revision.value}:${services.moviePosters.revision.value}"
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
        val options = services.sceneMetadataPreferences.settings.value
        // A movie folder without a poster yet is an ordinary folder until one is found or chosen.
        var movieFellBack = false
        val placement = options.folderChoice(identity).let { choice ->
            if (choice == com.deepak.videolibrary.metadata.FolderCoverChoice.MOVIE && services.moviePosters.fileFor(identity) == null) {
                movieFellBack = true
                com.deepak.videolibrary.metadata.FolderCoverChoice.ORIGINAL
            } else choice
        }
        // Only the default cover can change. A hand-picked image or other manual
        // folder override has absolute priority in every section.
        val art = services.artwork
        val manual = art.effectiveRow(identity)?.manual == true
        if (manual || (placement == com.deepak.videolibrary.metadata.FolderCoverChoice.ORIGINAL && !movieFellBack)) {
            cached(key)?.let { return it }
        }
        if (!manual && placement == com.deepak.videolibrary.metadata.FolderCoverChoice.PERFORMER) {
            val portrait = if (art.settings.value.enabled && art.settings.value.adultConsent &&
                art.settings.value.portraitsOnFolders)
                art.effectiveRow(identity)?.takeIf { !it.manual }?.imageName?.let(art::imageFile) else null
            if (portrait != null) { files.put(key, portrait); return portrait }
        }
        // A full movie poster found for this folder (a cover you chose yourself still wins).
        if (!manual && placement == com.deepak.videolibrary.metadata.FolderCoverChoice.MOVIE) {
            services.moviePosters.fileFor(identity)?.let { files.put(key, it); return it }
        }
        // Don't let an old automatic portrait override a selected scene.
        // If no scene image is cached, honour the user's configured FIRST_FRAME
        // or COLLAGE fallback before returning to Skywing's original thumbnail.
        // Manual artwork remains the highest priority.
        val decision = if (!manual && (placement == com.deepak.videolibrary.metadata.FolderCoverChoice.SCENE ||
                placement == com.deepak.videolibrary.metadata.FolderCoverChoice.MOVIE)) {
            val fallback = if (art.settings.value.enabled)
                art.settings.value.modeFor(com.deepak.videolibrary.artwork.ArtworkKeys.scopeOf(identity))
            else com.deepak.videolibrary.artwork.ArtworkMode.EXISTING
            val source = when (fallback) {
                com.deepak.videolibrary.artwork.ArtworkMode.FIRST_FRAME ->
                    com.deepak.videolibrary.artwork.ArtworkSource.FIRST_FRAME
                com.deepak.videolibrary.artwork.ArtworkMode.COLLAGE ->
                    com.deepak.videolibrary.artwork.ArtworkSource.COLLAGE
                else -> com.deepak.videolibrary.artwork.ArtworkSource.LEGACY
            }
            com.deepak.videolibrary.artwork.ArtworkDecision(source)
        } else art.decideFolder(identity)
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
            // Find one confirmed scene poster among this folder's indexed videos.
            // This also supports multi-video folders; if none is available keep the normal cover.
            // A folder with no movie poster uses a confirmed scene picture of its own videos before the plain cover,
            // so confirmed matches show without pressing Fetch.
            if (!manual && (placement == com.deepak.videolibrary.metadata.FolderCoverChoice.SCENE || movieFellBack) &&
                options.enabled && options.posterMode != com.deepak.videolibrary.metadata.PosterMode.DISABLED) {
                // Part 1 of a split scene first; if no part 1 has a picture, any file of the folder will do.
                val candidates = withContext(Dispatchers.IO) { ids.take(160).mapNotNull { services.db.dao().findVideoById(it) } }
                for (video in com.deepak.videolibrary.metadata.PartOne.firstPartsFirst(candidates) { it.name }) {
                    val poster = services.scenePosters.fileFor(video) ?: continue
                    files.put(key, poster)
                    return poster
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
            ThumbnailRetries(services.artworkContext).clearAll()
            VideoDurations.forgetTried(services.artworkContext)
            services.db.dao().resetAllThumbs()
            invalidate()
        }
    }
}
