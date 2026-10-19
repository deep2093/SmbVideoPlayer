package com.deepak.videolibrary.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryStateDao {
    @RawQuery(observedEntities = [VideoEntity::class, HiddenItem::class])
    abstract fun pages(query: SupportSQLiteQuery): PagingSource<Int, VideoEntity>
    @RawQuery abstract suspend fun videoList(query: SupportSQLiteQuery): List<VideoEntity>
    @RawQuery(observedEntities = [VideoEntity::class, HiddenItem::class, CustomFolderEntity::class])
    abstract fun folders(query: SupportSQLiteQuery): Flow<List<FolderSummary>>
    @RawQuery(observedEntities = [VideoEntity::class, HiddenItem::class])
    abstract fun directories(query: SupportSQLiteQuery): Flow<List<DirectorySummary>>
    @RawQuery(observedEntities = [VideoEntity::class, HiddenItem::class, PlaybackStateEntity::class])
    abstract fun continuing(query: SupportSQLiteQuery): Flow<List<VideoEntity>>
    @RawQuery(observedEntities = [VideoEntity::class, HiddenItem::class])
    abstract fun thumbnailFailures(query: SupportSQLiteQuery): Flow<List<VideoEntity>>
    @RawQuery(observedEntities = [VideoEntity::class, HiddenItem::class])
    abstract fun count(query: SupportSQLiteQuery): Flow<Int>
    @RawQuery abstract suspend fun ids(query: SupportSQLiteQuery): List<Long>

    @Query("SELECT * FROM hidden_items WHERE connectionKey=:key ORDER BY label COLLATE NOCASE")
    abstract fun hiddenItems(key: String): Flow<List<HiddenItem>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun hide(item: HiddenItem)
    @Delete abstract suspend fun unhide(item: HiddenItem)
    @Query("DELETE FROM hidden_items WHERE connectionKey=:key") abstract suspend fun unhideAll(key: String)
    @Query("SELECT * FROM playback_state WHERE connectionKey=:key AND path=:path")
    abstract fun observeProgress(key: String, path: String): Flow<PlaybackStateEntity?>
    @Query("SELECT * FROM playback_state WHERE connectionKey=:key AND path=:path")
    abstract suspend fun progress(key: String, path: String): PlaybackStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putProgress(item: PlaybackStateEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun importProgress(items: List<PlaybackStateEntity>)
    @Transaction open suspend fun saveProgress(key: String, path: String, position: Long, duration: Long) {
        val old = progress(key, path) ?: PlaybackStateEntity(key, path)
        putProgress(old.copy(positionMs = position.coerceAtLeast(0), durationMs = if(duration>0) duration else old.durationMs, updatedAt = System.currentTimeMillis()))
    }
    @Transaction open suspend fun hideContinue(key: String, path: String, hide: Boolean) {
        putProgress((progress(key, path) ?: PlaybackStateEntity(key, path)).copy(hidden = hide))
    }
    @Query("SELECT * FROM videos WHERE relativePath=:path OR substr(relativePath,1,length(:path)+1)=:path||'/'")
    abstract suspend fun under(path: String): List<VideoEntity>
    @Query("SELECT * FROM videos") abstract suspend fun legacyVideos(): List<VideoEntity>
    @Query("UPDATE videos SET relativePath=:path, parentDir=:parent, name=:name, groupKey=:groupName WHERE id=:id")
    abstract suspend fun moveVideo(id: Long, path: String, parent: String, name: String, groupName: String)
    @Query("UPDATE playback_state SET path=:destination||substr(path,length(:source)+1) WHERE connectionKey=:key AND (path=:source OR substr(path,1,length(:source)+1)=:source||'/')")
    abstract suspend fun moveProgress(key: String, source: String, destination: String)
    @Query("UPDATE hidden_items SET target=:destination||substr(target,length(:source)+1) WHERE connectionKey=:key AND kind!='GROUP' AND (target=:source OR substr(target,1,length(:source)+1)=:source||'/')")
    abstract suspend fun moveHidden(key: String, source: String, destination: String)
    @Query("DELETE FROM videos WHERE relativePath=:path OR substr(relativePath,1,length(:path)+1)=:path||'/'")
    abstract suspend fun deletePath(path: String)
    @Query("DELETE FROM playback_state WHERE connectionKey=:key AND (path=:path OR substr(path,1,length(:path)+1)=:path||'/')")
    abstract suspend fun deleteProgress(key: String, path: String)
    @Query("DELETE FROM hidden_items WHERE connectionKey=:key AND kind!='GROUP' AND (target=:path OR substr(target,1,length(:path)+1)=:path||'/')")
    abstract suspend fun deleteHidden(key: String, path: String)
}
