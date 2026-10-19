package com.deepak.videolibrary.history.v4

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
        MatchFolderEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
}
