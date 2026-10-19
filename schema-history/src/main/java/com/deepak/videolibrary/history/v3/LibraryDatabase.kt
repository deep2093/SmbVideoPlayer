package com.deepak.videolibrary.history.v3

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        VideoEntity::class,
        ScanSessionEntity::class,
        ScanErrorEntity::class,
        PendingDirEntity::class,
        CustomFolderEntity::class,
        HiddenItem::class,
        PlaybackStateEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
}

