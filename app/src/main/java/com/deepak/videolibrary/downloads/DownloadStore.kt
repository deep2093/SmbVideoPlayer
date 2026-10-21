package com.deepak.videolibrary.downloads

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One file to copy from the server to this device. */
@Entity(tableName = "downloads", indices = [Index(value = ["status", "id"])])
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Which saved server/library the file belongs to. */
    val libraryId: String,
    /** Path inside the share (forward slashes). */
    val sourcePath: String,
    val name: String,
    /** Sub-folder under Download/Veyra on the device ("" for the top level). */
    val targetFolder: String,
    /** Size in bytes; 0 until known. */
    val size: Long = 0,
    val bytesDone: Long = 0,
    val status: String = QUEUED,
    val error: String? = null,
    /** The device file being written (content:// address), once created. */
    val uri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val fraction: Float get() = if (size > 0) (bytesDone.toDouble() / size).toFloat().coerceIn(0f, 1f) else 0f
    val active: Boolean get() = status == QUEUED || status == RUNNING
    val finished: Boolean get() = status == DONE || status == FAILED || status == CANCELLED

    companion object {
        const val QUEUED = "QUEUED"
        const val RUNNING = "RUNNING"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY id DESC LIMIT 500")
    fun observeRecent(): Flow<List<DownloadItem>>

    @Insert
    suspend fun insertAll(items: List<DownloadItem>): List<Long>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadItem?

    @Query("SELECT status FROM downloads WHERE id = :id")
    suspend fun status(id: Long): String?

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' AND libraryId = :libraryId ORDER BY id LIMIT 1")
    suspend fun nextQueued(libraryId: String): DownloadItem?

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'QUEUED' AND libraryId = :libraryId")
    suspend fun queuedCount(libraryId: String): Int

    @Query("UPDATE downloads SET status = 'QUEUED' WHERE status = 'RUNNING' AND libraryId = :libraryId")
    suspend fun requeueInterrupted(libraryId: String)

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?)

    /** Only moves to RUNNING if nobody cancelled it in the meantime. */
    @Query("UPDATE downloads SET status = 'RUNNING', error = NULL WHERE id = :id AND status = 'QUEUED'")
    suspend fun markRunning(id: Long): Int

    @Query("UPDATE downloads SET size = :size, uri = :uri WHERE id = :id")
    suspend fun setTarget(id: Long, size: Long, uri: String?)

    @Query("UPDATE downloads SET bytesDone = :bytes WHERE id = :id")
    suspend fun setProgress(id: Long, bytes: Long)

    @Query("UPDATE downloads SET status = 'CANCELLED' WHERE id IN (:ids) AND status IN ('QUEUED', 'RUNNING')")
    suspend fun cancel(ids: List<Long>)

    @Query("UPDATE downloads SET status = 'CANCELLED' WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun cancelAll()

    @Query("UPDATE downloads SET status = 'QUEUED', error = NULL, bytesDone = 0, uri = NULL WHERE id = :id AND status IN ('FAILED', 'CANCELLED')")
    suspend fun retry(id: Long): Int

    @Query("DELETE FROM downloads WHERE id = :id AND status IN ('DONE', 'FAILED', 'CANCELLED')")
    suspend fun remove(id: Long)

    /** Removes completed downloads from the list (the files stay on the device). Failed and cancelled ones stay. */
    @Query("DELETE FROM downloads WHERE status = 'DONE'")
    suspend fun clearCompleted()
}

@Database(entities = [DownloadItem::class], version = 1, exportSchema = false)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun dao(): DownloadDao
}

/** The download queue is shared by all saved servers, so it lives in one small database of its own. */
object DownloadStore {
    @Volatile private var instance: DownloadDatabase? = null

    fun dao(context: Context): DownloadDao {
        instance?.let { return it.dao() }
        synchronized(this) {
            val db = instance ?: Room.databaseBuilder(context.applicationContext, DownloadDatabase::class.java, "downloads.db").build().also { instance = it }
            return db.dao()
        }
    }
}
