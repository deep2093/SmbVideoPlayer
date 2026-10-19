package com.deepak.videolibrary

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.deepak.videolibrary.data.*
import com.deepak.videolibrary.scan.ScanRepository
import com.deepak.videolibrary.settings.*
import com.deepak.videolibrary.thumbs.*
import java.io.File

/** Each saved server/account/root has its own persistent index and artwork. */
class ServiceLocator internal constructor(context: Context, val libraryId: String, val settings: SettingsStore) {
    private val registry = context.getSharedPreferences("library_registry", Context.MODE_PRIVATE)
    private val legacy = registry.getString("legacy_library", null) == libraryId
    val artworkContext: Context = object : ContextWrapper(context) {
        override fun getFilesDir(): File = if (legacy) context.filesDir else File(context.filesDir, "libraries/$libraryId").apply { mkdirs() }
    }
    val db: LibraryDatabase = Room.databaseBuilder(context, LibraryDatabase::class.java,
        if (legacy) "library.db" else "library-$libraryId.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    val scanRepository = ScanRepository(db, context.getSharedPreferences("scan-owners-$libraryId", Context.MODE_PRIVATE))
    val thumbnails = ThumbnailGenerator(artworkContext, db)
    val covers = CoverBuilder(artworkContext, db)

    companion object {
        private val instances = HashMap<String, ServiceLocator>()
        private var store: SettingsStore? = null
        @Synchronized fun get(context: Context): ServiceLocator {
            val app = context.applicationContext
            val settings = store ?: SettingsStore(app).also { store = it }
            val id = LibraryIdentity.of(settings.load())
            val registry = app.getSharedPreferences("library_registry", Context.MODE_PRIVATE)
            // Adopt the existing database exactly once, before the first connection can be changed.
            if (!registry.contains("legacy_library")) {
                check(registry.edit().putString("legacy_library", id).commit()) { "Could not register existing library" }
            }
            return instances.getOrPut(id) { ServiceLocator(app, id, settings) }
        }
    }
}
