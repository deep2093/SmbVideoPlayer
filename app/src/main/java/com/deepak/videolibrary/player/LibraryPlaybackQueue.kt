package com.deepak.videolibrary.player

import com.deepak.videolibrary.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The player receives one small batch; reaching its end advances to the next batch. */
class LibraryPlaybackQueue(private val dao: LibraryStateDao) {
    private var scope: LibraryScope? = null
    private var last: VideoEntity? = null
    var hasMore: Boolean = false
        private set
    suspend fun begin(selectedScope: LibraryScope, start: VideoEntity?): List<VideoEntity> {
        scope = selectedScope
        return load(start, start != null)
    }
    suspend fun next(): List<VideoEntity> = if (hasMore) load(last, false) else emptyList()
    private suspend fun load(after: VideoEntity?, includeStart: Boolean): List<VideoEntity> = withContext(Dispatchers.IO) {
        val rows = dao.videoList(LibraryQueries.batch(requireNotNull(scope), after, includeStart, 101))
        hasMore = rows.size > 100
        rows.take(100).also { last = it.lastOrNull() }
    }
}
