package com.deepak.videolibrary.data

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
