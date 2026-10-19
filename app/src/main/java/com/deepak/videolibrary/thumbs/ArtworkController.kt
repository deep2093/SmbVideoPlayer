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
    fun key(name: String,count: Int,done: Int,modified: Long) = "${services.libraryId}:$name:$count:$done:$modified:$revision"
    fun cached(key: String): File? = files.get(key)?.takeIf { it.isFile }
    fun invalidate() { files.evictAll(); revision++ }
    suspend fun cover(identity: String, key: String, scope: LibraryScope): File? {
        cached(key)?.let { return it }
        return try {
            val ids = withContext(Dispatchers.IO) { services.db.libraryState().ids(LibraryQueries.coverIds(scope)) }
            services.covers.buildOriginal(identity,ids)?.also { files.put(key,it) }
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
