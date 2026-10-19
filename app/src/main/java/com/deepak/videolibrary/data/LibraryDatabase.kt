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
        HiddenItem::class,
        PlaybackStateEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
    abstract fun libraryState(): LibraryStateDao
}


val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE videos ADD COLUMN customGroup TEXT DEFAULT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS custom_folders (name TEXT NOT NULL PRIMARY KEY)")
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hidden_items (connectionKey TEXT NOT NULL, kind TEXT NOT NULL, target TEXT NOT NULL, label TEXT NOT NULL, PRIMARY KEY(connectionKey, kind, target))")
        db.execSQL("CREATE TABLE IF NOT EXISTS playback_state (connectionKey TEXT NOT NULL, path TEXT NOT NULL, positionMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, hidden INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(connectionKey, path))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_state_connectionKey_updatedAt ON playback_state(connectionKey, updatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_thumbStatus_id ON videos(thumbStatus, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_lastSeenSession ON videos(lastSeenSession)")
    }
}
