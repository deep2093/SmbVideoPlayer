package com.deepak.videolibrary.data

import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryStateDao {
    @Query("SELECT * FROM transfer_journal WHERE operationId=:id")
    abstract suspend fun journal(id: String): TransferJournal?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putJournal(entry: TransferJournal)
    @Query("UPDATE transfer_journal SET phase=:phase WHERE operationId=:id")
    abstract suspend fun journalPhase(id: String, phase: String)

    @Query("SELECT * FROM match_folders WHERE connectionKey=:key AND sourcePath=:path AND includeSubfolders=:recursive LIMIT 1")
    abstract suspend fun sourceFolder(key: String, path: String, recursive: Boolean): MatchFolderEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM match_folders WHERE connectionKey=:key AND name=:name)")
    abstract suspend fun matchNameExists(key: String, name: String): Boolean

    @Insert abstract suspend fun createMatch(folder: MatchFolderEntity): Long

    @Query("UPDATE match_folders SET name=:name,term=CASE WHEN sourcePath IS NULL THEN :name ELSE term END WHERE id=:id")
    abstract suspend fun renameMatch(id: Long, name: String)

    @Query("DELETE FROM match_folders WHERE id=:id")
    abstract suspend fun deleteMatch(id: Long)

    @RawQuery(observedEntities = [MatchFolderEntity::class, VideoEntity::class, HiddenItem::class])
    abstract fun matchingFolders(query: SupportSQLiteQuery): Flow<List<MatchFolderRow>>

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

    @Query("SELECT * FROM hidden_items WHERE connectionKey=:key ORDER BY label COLLATE NOCASE")
    abstract suspend fun savedHidden(key: String): List<HiddenItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun hide(item: HiddenItem)

    @Delete abstract suspend fun unhide(item: HiddenItem)

    @Query("DELETE FROM hidden_items WHERE connectionKey=:key")
    abstract suspend fun unhideAll(key: String)

    @Query("SELECT * FROM playback_state WHERE connectionKey=:key AND path=:path")
    abstract fun observeProgress(key: String, path: String): Flow<PlaybackStateEntity?>

    @Query("SELECT * FROM playback_state WHERE connectionKey=:key AND path=:path")
    abstract suspend fun progress(key: String, path: String): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putProgress(item: PlaybackStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun importProgress(items: List<PlaybackStateEntity>)

    @Transaction open suspend fun saveProgress(key: String, path: String, position: Long, duration: Long) {
        val old = progress(key, path) ?: PlaybackStateEntity(key, path)
        putProgress(
            old.copy(
                positionMs = position.coerceAtLeast(0),
                durationMs = if (duration >
                    0
                ) {
                    duration
                } else {
                    old.durationMs
                },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    @Transaction open suspend fun hideContinue(key: String, path: String, hide: Boolean) {
        putProgress((progress(key, path) ?: PlaybackStateEntity(key, path)).copy(hidden = hide))
    }

    @Query("SELECT * FROM videos WHERE relativePath=:path OR substr(relativePath,1,length(:path)+1)=:path||'/'")
    abstract suspend fun under(path: String): List<VideoEntity>

    @Query("SELECT * FROM custom_folders")
    abstract suspend fun manualFolders(): List<CustomFolderEntity>
    @Query("SELECT * FROM match_folders WHERE connectionKey=:key")
    abstract suspend fun savedCollections(key: String): List<MatchFolderEntity>
    @Query("SELECT * FROM playback_state WHERE connectionKey=:key")
    abstract suspend fun savedProgress(key: String): List<PlaybackStateEntity>
    @Query("UPDATE videos SET customGroup=:groupName WHERE relativePath=:path")
    abstract suspend fun restoreAssignment(path: String, groupName: String): Int

    @Query("SELECT * FROM videos")
    abstract suspend fun legacyVideos(): List<VideoEntity>

    @Query("UPDATE videos SET relativePath=:path, parentDir=:parent, name=:name, groupKey=:groupName WHERE id=:id")
    abstract suspend fun moveVideo(id: Long, path: String, parent: String, name: String, groupName: String)

    @Query(
        "UPDATE playback_state SET path=:destination||substr(path,length(:source)+1) WHERE connectionKey=:key AND (path=:source OR substr(path,1,length(:source)+1)=:source||'/')",
    )
    abstract suspend fun moveProgress(key: String, source: String, destination: String)

    @Query(
        "UPDATE hidden_items SET target=:destination||substr(target,length(:source)+1) WHERE connectionKey=:key AND kind IN ('FILE','DIRECTORY') AND (target=:source OR substr(target,1,length(:source)+1)=:source||'/')",
    )
    abstract suspend fun moveHidden(key: String, source: String, destination: String)

    @Query("UPDATE match_folders SET sourcePath=:destination||substr(sourcePath,length(:source)+1) WHERE connectionKey=:key AND sourcePath IS NOT NULL AND (sourcePath=:source OR substr(sourcePath,1,length(:source)+1)=:source||'/')")
    abstract suspend fun moveSourceFolders(key: String, source: String, destination: String)

    @Query("DELETE FROM videos WHERE relativePath=:path OR substr(relativePath,1,length(:path)+1)=:path||'/'")
    abstract suspend fun deletePath(path: String)

    @Query("DELETE FROM playback_state WHERE connectionKey=:key AND (path=:path OR substr(path,1,length(:path)+1)=:path||'/')")
    abstract suspend fun deleteProgress(key: String, path: String)

    @Query(
        "DELETE FROM hidden_items WHERE connectionKey=:key AND kind IN ('FILE','DIRECTORY') AND (target=:path OR substr(target,1,length(:path)+1)=:path||'/')",
    )
    abstract suspend fun deleteHidden(key: String, path: String)
}
