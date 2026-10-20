package com.deepak.videolibrary.player

import com.deepak.videolibrary.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

/** The player receives one small batch; reaching its end advances to the next batch. */
class LibraryPlaybackQueue(private val dao: LibraryStateDao) {
    private var scope: LibraryScope? = null
    private var last: VideoEntity? = null
    var hasMore: Boolean = false
        private set
    suspend fun begin(selectedScope: LibraryScope, start: VideoEntity?): List<VideoEntity> {
        reset()
        scope = selectedScope
        return load(start, start != null)
    }
    suspend fun next(): List<VideoEntity> = if (hasMore) load(last, false) else emptyList()
    fun reset() { scope=null; last=null; hasMore=false }
    private suspend fun load(after: VideoEntity?, includeStart: Boolean): List<VideoEntity> {
        val selected=requireNotNull(scope)
        val rows = withContext(Dispatchers.IO) { dao.videoList(LibraryQueries.batch(selected, after, includeStart, 101)) }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        hasMore = rows.size > 100
        return rows.take(100).also { last = it.lastOrNull() }
    }
}
