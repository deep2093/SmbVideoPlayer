package com.deepak.videolibrary.metadata

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.deepak.videolibrary.data.VideoEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent, per-library preferences. Scene metadata never changes the artwork or virtual-folder tables. */
data class SceneMetadataSettings(
    val enabled: Boolean = true,
    val primary: String = STASHDB,
    val automatic: Boolean = true,
    val phash: Boolean = true,
    val reviewUncertain: Boolean = true,
    val allowedOnlineLookup: Boolean = false,
) {
    companion object {
        const val STASHDB = "stashdb"
        // Separate from the removed artwork provider's legacy key, which is intentionally cleared.
        const val TPDB = "stashbox_tpdb"
    }

    fun providers(): List<String> = if (primary == TPDB) listOf(TPDB, STASHDB) else listOf(STASHDB, TPDB)
}

class SceneMetadataPreferences(context: Context, libraryId: String) {
    private val prefs = context.applicationContext.getSharedPreferences("scene-metadata-$libraryId", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings = state.asStateFlow()

    private fun read() = SceneMetadataSettings(
        enabled = prefs.getBoolean("enabled", true),
        primary = prefs.getString("primary", SceneMetadataSettings.STASHDB)
            ?.takeIf { it == SceneMetadataSettings.TPDB } ?: SceneMetadataSettings.STASHDB,
        automatic = prefs.getBoolean("automatic", true),
        phash = prefs.getBoolean("phash", true),
        reviewUncertain = prefs.getBoolean("review_uncertain", true),
        allowedOnlineLookup = prefs.getBoolean("online_consent", false),
    )

    @Synchronized
    fun update(change: (SceneMetadataSettings) -> SceneMetadataSettings) {
        val s = change(state.value)
        val next = s.copy(primary = if (s.primary == SceneMetadataSettings.TPDB) s.primary else SceneMetadataSettings.STASHDB)
        prefs.edit()
            .putBoolean("enabled", next.enabled)
            .putString("primary", next.primary)
            .putBoolean("automatic", next.automatic)
            .putBoolean("phash", next.phash)
            .putBoolean("review_uncertain", next.reviewUncertain)
            .putBoolean("online_consent", next.allowedOnlineLookup)
            .apply()
        state.value = next
    }
}

data class SceneMetadataRecord(
    val path: String,
    val size: Long,
    val modified: Long,
    val status: String,
    val result: String?,
    val suggestions: String?,
    val checked: Long,
)

/** A new SQLite database, so no migration or modifications to existing Room library/artwork schemas. */
class SceneMetadataStore(context: Context, libraryId: String) :
    SQLiteOpenHelper(context.applicationContext, "scene-metadata-$libraryId.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE scene_metadata(path TEXT PRIMARY KEY NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, status TEXT NOT NULL, result TEXT, suggestions TEXT, checked INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_scene_metadata_status ON scene_metadata(status, checked)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun get(path: String): SceneMetadataRecord? {
        readableDatabase.query("scene_metadata", null, "path=?", arrayOf(path), null, null, null).use { c ->
            return if (c.moveToFirst()) SceneMetadataRecord(
                c.getString(c.getColumnIndexOrThrow("path")),
                c.getLong(c.getColumnIndexOrThrow("size")),
                c.getLong(c.getColumnIndexOrThrow("modified")),
                c.getString(c.getColumnIndexOrThrow("status")),
                c.getString(c.getColumnIndexOrThrow("result")),
                c.getString(c.getColumnIndexOrThrow("suggestions")),
                c.getLong(c.getColumnIndexOrThrow("checked")),
            ) else null
        }
    }

    fun needsLookup(video: VideoEntity): Boolean {
        val row = get(video.relativePath) ?: return true
        if (row.size != video.size || row.modified != video.modified) return true
        return row.status == "RETRY" && System.currentTimeMillis() - row.checked > 60 * 60 * 1000L
    }

    @Synchronized
    fun save(video: VideoEntity, status: String, result: String? = null, suggestions: String? = null) {
        val values = ContentValues().apply {
            put("path", video.relativePath)
            put("size", video.size)
            put("modified", video.modified)
            put("status", status)
            put("result", result)
            put("suggestions", suggestions)
            put("checked", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("scene_metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun review(limit: Int = 100): List<SceneMetadataRecord> {
        val result = ArrayList<SceneMetadataRecord>()
        readableDatabase.query("scene_metadata", arrayOf("path"), "status=?", arrayOf("REVIEW"), null, null, "checked DESC", limit.coerceIn(1, 200).toString()).use { c ->
            while (c.moveToNext()) get(c.getString(0))?.let(result::add)
        }
        return result
    }

    /** The user confirms a proposed provider scene. No artwork or file-path changes are made. */
    @Synchronized
    fun approve(path: String, selectedSceneJson: String) {
        val old = get(path) ?: return
        writableDatabase.execSQL(
            "UPDATE scene_metadata SET status='CONFIRMED', result=?, suggestions=NULL, checked=? WHERE path=? AND size=? AND modified=?",
            arrayOf(selectedSceneJson, System.currentTimeMillis(), old.path, old.size, old.modified),
        )
    }

    @Synchronized
    fun dismiss(path: String) {
        writableDatabase.execSQL("UPDATE scene_metadata SET status='DISMISSED', suggestions=NULL WHERE path=? AND status='REVIEW'", arrayOf(path))
    }

    @Synchronized
    fun retry(path: String) {
        writableDatabase.delete("scene_metadata", "path=?", arrayOf(path))
    }
}
