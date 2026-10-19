package com.deepak.videolibrary.history.v4

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One indexed video. relativePath is the path inside the share, with forward slashes. */
@Entity(
    tableName = "videos",
    indices = [
        Index(value = ["relativePath"], unique = true),
        Index(value = ["groupKey"]),
        Index(value = ["parentDir"]),
        Index(value = ["thumbStatus", "id"]),
        Index(value = ["lastSeenSession"]),
    ],
)
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val relativePath: String,
    val name: String,
    val parentDir: String,
    val size: Long,
    val modified: Long,
    val groupKey: String,
    val customGroup: String? = null,
    val thumbStatus: String = "PENDING",
    val thumbError: String? = null,
    val lastSeenSession: Long,
    val addedAt: Long,
)

@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val dirsScanned: Int = 0,
    val videosFound: Int = 0,
    val errorCount: Int = 0,
    val currentDir: String = "",
)

@Entity(tableName = "scan_errors", indices = [Index(value = ["sessionId"])])
data class ScanErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val path: String,
    val message: String,
    val isDirectory: Boolean,
    val time: Long,
)

/** Directories still waiting to be listed. This table is what makes a scan resumable. */
@Entity(tableName = "pending_dirs")
data class PendingDirEntity(
    @PrimaryKey val path: String,
)

data class GroupRow(
    val groupKey: String,
    val customGroup: String? = null,
    val count: Int,
    val thumbsDone: Int,
)

/** Explicit virtual folders exist even when temporarily empty. */
@Entity(tableName = "custom_folders")
data class CustomFolderEntity(@PrimaryKey val name: String)

@Entity(tableName = "hidden_items", primaryKeys = ["connectionKey", "kind", "target"])
data class HiddenItem(val connectionKey: String, val kind: String, val target: String, val label: String)

@Entity(tableName = "playback_state", primaryKeys = ["connectionKey", "path"], indices = [Index(value = ["connectionKey", "updatedAt"])])
data class PlaybackStateEntity(val connectionKey: String, val path: String,
    val positionMs: Long = 0, val durationMs: Long = 0, val hidden: Boolean = false, val updatedAt: Long = 0)

data class FolderSummary(val groupKey: String, val count: Int, val thumbsDone: Int, val totalSize: Long, val lastModified: Long)
data class DirectorySummary(val path: String, val count: Int, val thumbsDone: Int)

/** An overlapping, automatic collection; membership never rewrites video assignments. */
@Entity(tableName="match_folders",indices=[Index(value=["connectionKey","name"],unique=true)])
data class MatchFolderEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,
    val connectionKey:String,val name:String,val term:String)
data class MatchFolderRow(@androidx.room.Embedded val folder:MatchFolderEntity,
    val count:Int,val thumbsDone:Int,val lastModified:Long)

