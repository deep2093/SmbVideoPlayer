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
        MatchFolderEntity::class,
        TransferJournal::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
    abstract fun libraryState(): LibraryStateDao
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val hasThumbError = db.query("PRAGMA table_info(videos)").use { cursor ->
            val column = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(column) == "thumbError") found = true
            found
        }
        if (!hasThumbError) db.execSQL("ALTER TABLE videos ADD COLUMN thumbError TEXT DEFAULT NULL")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS match_folders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, connectionKey TEXT NOT NULL, name TEXT NOT NULL, term TEXT NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_match_folders_connectionKey_name ON match_folders(connectionKey,name)")
    }
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE videos ADD COLUMN customGroup TEXT DEFAULT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS custom_folders (name TEXT NOT NULL PRIMARY KEY)")
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE videos ADD COLUMN thumbError TEXT DEFAULT NULL")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS hidden_items (connectionKey TEXT NOT NULL, kind TEXT NOT NULL, target TEXT NOT NULL, label TEXT NOT NULL, PRIMARY KEY(connectionKey, kind, target))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS playback_state (connectionKey TEXT NOT NULL, path TEXT NOT NULL, positionMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, hidden INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(connectionKey, path))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_state_connectionKey_updatedAt ON playback_state(connectionKey, updatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_thumbStatus_id ON videos(thumbStatus, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_lastSeenSession ON videos(lastSeenSession)")
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE match_folders ADD COLUMN sourcePath TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE match_folders ADD COLUMN includeSubfolders INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE TABLE IF NOT EXISTS transfer_journal (operationId TEXT NOT NULL PRIMARY KEY, operation TEXT NOT NULL, source TEXT NOT NULL, destination TEXT NOT NULL, directory INTEGER NOT NULL, phase TEXT NOT NULL, remoteIdentity INTEGER NOT NULL)")
    }
}
