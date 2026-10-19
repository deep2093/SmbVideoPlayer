package com.deepak.videolibrary

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper = MigrationTestHelper(instrumentation, LibraryDatabase::class.java)
    @get:Rule val earlyHelper = MigrationTestHelper(instrumentation, "com.deepak.videolibrary.history.v3early.LibraryDatabase", FrameworkSQLiteOpenHelperFactory())
    private val migrations = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

    @Test fun everySupportedVersionPreservesLibrary() {
        for (version in 1..4) {
            val name = "upgrade-$version-5"
            helper.createDatabase(name, version).use { old ->
                old.execSQL("INSERT INTO videos(id,relativePath,name,parentDir,size,modified,groupKey,lastSeenSession,addedAt,thumbStatus) VALUES (42,'Movies/A.mp4','A.mp4','Movies',100,1,'A',1,1,'DONE')")
                if (version >= 2) {
                    old.execSQL("INSERT INTO custom_folders(name) VALUES ('Favourites')")
                    old.execSQL("UPDATE videos SET customGroup='Favourites' WHERE id=42")
                }
                if (version >= 3) {
                    old.execSQL("INSERT INTO playback_state VALUES ('nas/share','Movies/A.mp4',30000,120000,0,1)")
                    old.execSQL("INSERT INTO hidden_items VALUES ('nas/share','FILE','Movies/Hidden.mp4','Hidden')")
                }
                if (version == 4) old.execSQL("INSERT INTO match_folders(connectionKey,name,term) VALUES ('nas/share','Actor','Actor')")
            }
            helper.runMigrationsAndValidate(name, 5, true, *migrations).use { migrated ->
                migrated.query("SELECT id,thumbStatus,customGroup FROM videos").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(42L,c.getLong(0)); assertEquals("DONE",c.getString(1))
                    if (version >= 2) assertEquals("Favourites", c.getString(2))
                }
                if (version >= 3) migrated.query("SELECT positionMs FROM playback_state").use { c -> assertTrue(c.moveToFirst()); assertEquals(30000L,c.getLong(0)) }
                if (version == 4) migrated.query("SELECT term,sourcePath,includeSubfolders FROM match_folders").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals("Actor",c.getString(0)); assertTrue(c.isNull(1)); assertEquals(1,c.getInt(2))
                }
            }
            val production = Room.databaseBuilder(instrumentation.targetContext, LibraryDatabase::class.java, name).addMigrations(*migrations).build()
            try { assertEquals(5,production.openHelper.writableDatabase.version) } finally { production.close() }
        }
    }

    @Test fun earlyVersion3WithoutThumbnailErrorAlsoUpgrades() {
        val name = "early-v3-upgrade"
        earlyHelper.createDatabase(name, 3).use { old ->
            old.execSQL("INSERT INTO videos(id,relativePath,name,parentDir,size,modified,groupKey,lastSeenSession,addedAt,thumbStatus) VALUES (42,'A.mp4','A.mp4','',1,1,'A',1,1,'DONE')")
        }
        helper.runMigrationsAndValidate(name,5,true,*migrations).use { db ->
            db.query("SELECT id,thumbError FROM videos").use { c -> assertTrue(c.moveToFirst()); assertEquals(42L,c.getLong(0)); assertTrue(c.isNull(1)) }
        }
    }

    @Test fun freshProductionDatabaseOpens() {
        val name = "fresh-v5"
        instrumentation.targetContext.deleteDatabase(name)
        val production = Room.databaseBuilder(instrumentation.targetContext,LibraryDatabase::class.java,name).build()
        try { assertEquals(5,production.openHelper.writableDatabase.version) } finally { production.close() }
    }
}
