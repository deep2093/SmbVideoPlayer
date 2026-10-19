package com.deepak.videolibrary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    // ---- videos ----

    @Query("SELECT * FROM videos WHERE relativePath = :path LIMIT 1")
    suspend fun findVideo(path: String): VideoEntity?

    @Insert
    suspend fun insertVideo(video: VideoEntity): Long

    @Update
    suspend fun updateVideo(video: VideoEntity)
    @Query("UPDATE videos SET size=:size, modified=:modified, groupKey=:groupName, thumbStatus='PENDING', lastSeenSession=:session WHERE id=:id")
    suspend fun updateMetadata(id:Long,size:Long,modified:Long,groupName:String,session:Long)
    @Query("UPDATE videos SET groupKey=:groupName,lastSeenSession=:session WHERE id=:id")
    suspend fun updateGroupKey(id:Long,groupName:String,session:Long)

    @Query("UPDATE videos SET lastSeenSession = :sessionId WHERE id = :id")
    suspend fun touchVideo(id: Long, sessionId: Long)

    @Query("SELECT COUNT(*) FROM videos WHERE lastSeenSession != :sessionId")
    suspend fun countNotSeen(sessionId: Long): Int

    @Query("DELETE FROM videos WHERE lastSeenSession != :sessionId")
    suspend fun deleteNotSeen(sessionId: Long): Int

    @Query(
        "SELECT * FROM videos " +
            "WHERE (:group IS NULL OR COALESCE(customGroup, groupKey) = :group) AND instr(lower(name), lower(:query)) > 0 " +
            "ORDER BY name COLLATE NOCASE"
    )
    fun observeVideos(group: String?, query: String): Flow<List<VideoEntity>>

    @Query("SELECT COUNT(*) FROM videos")
    fun observeVideoCount(): Flow<Int>

    @Query(
        "SELECT groupKey, SUM(count) AS count, SUM(thumbsDone) AS thumbsDone FROM (" +
            "SELECT COALESCE(customGroup, groupKey) AS groupKey, 1 AS count, " +
            "CASE WHEN thumbStatus = 'DONE' THEN 1 ELSE 0 END AS thumbsDone FROM videos " +
            "UNION ALL SELECT name AS groupKey, 0 AS count, 0 AS thumbsDone FROM custom_folders" +
            ") GROUP BY groupKey ORDER BY groupKey COLLATE NOCASE"
    )
    fun observeGroups(): Flow<List<GroupRow>>

    // ---- persistent virtual folder organization ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun createFolder(folder: CustomFolderEntity)

    @Query("UPDATE videos SET customGroup = :destination WHERE customGroup IS NULL AND groupKey = :from")
    suspend fun assignAutoGroup(from: String, destination: String)

    @Query("UPDATE videos SET customGroup = :destination WHERE id = :videoId")
    suspend fun assignVideo(videoId: Long, destination: String)

    @Query("UPDATE videos SET customGroup = :newName WHERE COALESCE(customGroup, groupKey) = :oldName")
    suspend fun renameAssignments(oldName: String, newName: String)

    @Query("UPDATE custom_folders SET name = :newName WHERE name = :oldName")
    suspend fun renameFolderRow(oldName: String, newName: String)

    @Query("DELETE FROM custom_folders WHERE name = :name")
    suspend fun deleteFolderRow(name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM custom_folders WHERE name = :name)")
    suspend fun isCustomFolder(name: String): Boolean

    @Query("SELECT * FROM videos WHERE customGroup IS NOT NULL ORDER BY name COLLATE NOCASE")
    suspend fun manuallyGroupedVideos(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE (customGroup IS NULL OR customGroup != :name) AND instr(lower(name), lower(:query)) > 0 ORDER BY name COLLATE NOCASE LIMIT 100")
    suspend fun findAssignableVideos(name: String, query: String): List<VideoEntity>

    // ---- thumbnails ----

    @Query("SELECT * FROM videos WHERE thumbStatus = 'PENDING' ORDER BY id LIMIT :limit")
    suspend fun nextPendingThumbs(limit: Int): List<VideoEntity>

    @Query("UPDATE videos SET thumbStatus = :status, thumbError=:reason WHERE id = :id")
    suspend fun setThumbStatus(id: Long, status: String, reason: String? = null)

    @Query("SELECT * FROM videos WHERE thumbStatus='FAILED' ORDER BY name COLLATE NOCASE")
    fun observeFailedThumbs(): Flow<List<VideoEntity>>

    @Query("UPDATE videos SET customGroup=:folder WHERE relativePath IN (:paths)")
    suspend fun assignPaths(paths:List<String>,folder:String)

    @Query("UPDATE videos SET thumbStatus = 'PENDING' WHERE thumbStatus = 'FAILED'")
    suspend fun resetFailedThumbs(): Int

    @Query("UPDATE videos SET thumbStatus = 'PENDING'")
    suspend fun resetAllThumbs(): Int

    @Query("SELECT COUNT(*) FROM videos WHERE thumbStatus = :status")
    suspend fun countThumbs(status: String): Int

    @Query("SELECT COUNT(*) FROM videos WHERE thumbStatus = :status")
    fun observeThumbCount(status: String): Flow<Int>

    @Query("SELECT id FROM videos WHERE COALESCE(customGroup, groupKey) = :group AND thumbStatus = 'DONE' ORDER BY name COLLATE NOCASE")
    suspend fun doneIdsForGroup(group: String): List<Long>

    // ---- scan sessions ----

    @Insert
    suspend fun insertSession(session: ScanSessionEntity): Long

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun getSession(id: Long): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions ORDER BY id DESC LIMIT 1")
    suspend fun latestSession(): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions ORDER BY id DESC LIMIT 1")
    fun observeLatestSession(): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions WHERE status IN ('INTERRUPTED', 'FAILED') ORDER BY id DESC LIMIT 1")
    suspend fun latestResumableSession(): ScanSessionEntity?

    @Query("UPDATE scan_sessions SET status = 'INTERRUPTED' WHERE status = 'RUNNING'")
    suspend fun markRunningInterrupted()

    @Query("UPDATE scan_sessions SET status = :status, finishedAt = :finishedAt WHERE id = :id")
    suspend fun setSessionStatus(id: Long, status: String, finishedAt: Long?)

    /** Refreshes the progress counters of a session from the data actually stored. */
    @Query(
        "UPDATE scan_sessions SET " +
            "dirsScanned = dirsScanned + 1, " +
            "currentDir = :dir, " +
            "videosFound = (SELECT COUNT(*) FROM videos WHERE lastSeenSession = :sessionId), " +
            "errorCount = (SELECT COUNT(*) FROM scan_errors WHERE sessionId = :sessionId) " +
            "WHERE id = :sessionId"
    )
    suspend fun bumpProgress(sessionId: Long, dir: String)

    @Query(
        "UPDATE scan_sessions SET " +
            "videosFound = (SELECT COUNT(*) FROM videos WHERE lastSeenSession = :sessionId), " +
            "errorCount = (SELECT COUNT(*) FROM scan_errors WHERE sessionId = :sessionId) " +
            "WHERE id = :sessionId"
    )
    suspend fun refreshCounters(sessionId: Long)

    // ---- pending directories ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPending(dirs: List<PendingDirEntity>)

    @Query("SELECT path FROM pending_dirs LIMIT 1")
    suspend fun nextPending(): String?

    @Query("DELETE FROM pending_dirs WHERE path = :path")
    suspend fun deletePending(path: String)

    @Query("DELETE FROM pending_dirs")
    suspend fun clearPending()

    @Query("SELECT COUNT(*) FROM pending_dirs")
    suspend fun pendingCountNow(): Int

    @Query("SELECT COUNT(*) FROM pending_dirs")
    fun observePendingCount(): Flow<Int>

    // ---- errors ----

    @Insert
    suspend fun insertError(error: ScanErrorEntity)

    @Query("SELECT * FROM scan_errors WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 500")
    fun observeErrors(sessionId: Long): Flow<List<ScanErrorEntity>>

    @Query("SELECT DISTINCT path FROM scan_errors WHERE sessionId = :sessionId AND isDirectory = 1")
    suspend fun errorDirPaths(sessionId: Long): List<String>

    @Query("DELETE FROM scan_errors WHERE sessionId = :sessionId AND isDirectory = 1")
    suspend fun clearDirErrors(sessionId: Long)
}
