package com.deepak.videolibrary.history.v2

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        VideoEntity::class,
        ScanSessionEntity::class,
        ScanErrorEntity::class,
        PendingDirEntity::class,
        CustomFolderEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
}

