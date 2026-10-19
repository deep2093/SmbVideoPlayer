package com.deepak.videolibrary

import android.content.Context
import androidx.room.Room
import com.deepak.videolibrary.data.LibraryDatabase
import com.deepak.videolibrary.scan.ScanRepository
import com.deepak.videolibrary.settings.SettingsStore
import com.deepak.videolibrary.thumbs.CoverBuilder
import com.deepak.videolibrary.thumbs.ThumbnailGenerator

/** Process-wide singletons, shared by the UI and the background workers. */
class ServiceLocator private constructor(context: Context) {
    val db: LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
            .addMigrations(com.deepak.videolibrary.data.MIGRATION_1_2, com.deepak.videolibrary.data.MIGRATION_2_3).build()
    val settings = SettingsStore(context)
    val scanRepository = ScanRepository(db)
    val thumbnails = ThumbnailGenerator(context, db)
    val covers = CoverBuilder(context, db)

    companion object {
        @Volatile
        private var instance: ServiceLocator? = null

        fun get(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context.applicationContext).also { instance = it }
            }
    }
}
