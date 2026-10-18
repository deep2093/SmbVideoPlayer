package com.deepak.videolibrary.data

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
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
}


val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE videos ADD COLUMN customGroup TEXT DEFAULT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS custom_folders (name TEXT NOT NULL PRIMARY KEY)")
    }
}
