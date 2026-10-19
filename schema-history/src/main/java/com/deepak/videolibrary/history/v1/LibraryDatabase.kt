package com.deepak.videolibrary.history.v1

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        VideoEntity::class,
        ScanSessionEntity::class,
        ScanErrorEntity::class,
        PendingDirEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
}

