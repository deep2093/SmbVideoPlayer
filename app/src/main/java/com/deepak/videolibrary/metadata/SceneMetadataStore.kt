package com.deepak.videolibrary.metadata

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.deepak.videolibrary.data.VideoEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent, per-library preferences. Scene metadata never changes the artwork or virtual-folder tables. */
enum class PosterMode(val label: String) {
    AUTOMATIC("Automatic: verified matches"),
    REVIEW_FIRST("Review first"),
    DISABLED("Disabled"),
}

/**
 * One independently saved choice for each folder section.
 * ORIGINAL leaves the existing cover system alone; it does not clear hand-picked covers.
 */
enum class FolderCoverChoice(val label: String) {
    ORIGINAL("Original / existing cover"),
    PERFORMER("Performer portrait"),
    SCENE("Scene poster"),
    MOVIE("Full movie poster"),
}

data class SceneMetadataSettings(
    val enabled: Boolean = true,
    val primary: String = STASHDB,
    val automatic: Boolean = true,
    val phash: Boolean = false,
    val reviewUncertain: Boolean = true,
    val allowedOnlineLookup: Boolean = false,
    val posterMode: PosterMode = PosterMode.AUTOMATIC,
    val videoPosters: Boolean = true,
    val folderPosters: Boolean = false,
    val originalFolderCover: FolderCoverChoice = FolderCoverChoice.ORIGINAL,
    val customFolderCover: FolderCoverChoice = FolderCoverChoice.ORIGINAL,
    val virtualFolderCover: FolderCoverChoice = FolderCoverChoice.ORIGINAL,
    val generatedFolderCover: FolderCoverChoice = FolderCoverChoice.ORIGINAL,
    /** Virtual Set name-generated folder portraits are separate from other generated folders. */
    val virtualGeneratedFolderCover: FolderCoverChoice = FolderCoverChoice.PERFORMER,
    /** The videos inside only Virtual Set generated folders may show scene art independently. */
    val virtualGeneratedVideoScenes: Boolean = true,
) {
    companion object {
        const val STASHDB = "stashdb"
        // Separate from the removed artwork provider's legacy key, which is intentionally cleared.
        const val TPDB = "stashbox_tpdb"
    }

    fun providers(): List<String> = if (primary == TPDB) listOf(TPDB, STASHDB) else listOf(STASHDB, TPDB)

    /**
     * What a folder's automatic cover is. Movie folders inside Original folders get a full movie poster (always on:
     * certain matches are applied, unclear ones wait in review). In Virtual folders, the name folders made from file
     * names (before a comma or hyphen, or the first two words) get a performer portrait; the Virtual folder you
     * named yourself does not. Custom folders get nothing automatic: poster or performer is chosen by hand in Change
     * cover. Scene posters belong to each video.
     */
    fun folderChoice(identity: String): FolderCoverChoice = when {
        identity.startsWith("original:") -> FolderCoverChoice.MOVIE
        identity.startsWith("vset:") && identity.removePrefix("vset:").contains(':') -> FolderCoverChoice.PERFORMER
        else -> FolderCoverChoice.ORIGINAL
    }

    fun needsFolderScenePosters() = false

    fun needsMoviePosters() = true
}

class SceneMetadataPreferences(context: Context, libraryId: String) {
    private val prefs = context.applicationContext.getSharedPreferences("scene-metadata-$libraryId", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings = state.asStateFlow()

    private fun read() = SceneMetadataSettings(
        enabled = prefs.getBoolean("enabled", true),
        primary = prefs.getString("primary", SceneMetadataSettings.STASHDB)
            ?.takeIf { it == SceneMetadataSettings.TPDB } ?: SceneMetadataSettings.STASHDB,
        automatic = true,
        phash = prefs.getBoolean("phash", false),
        reviewUncertain = prefs.getBoolean("review_uncertain", true),
        allowedOnlineLookup = prefs.getBoolean("online_consent", false),
        // Confirmed scene posters are always applied automatically; there is no approval step or off switch.
        posterMode = PosterMode.AUTOMATIC,
        videoPosters = prefs.getBoolean("video_posters", true),
        folderPosters = prefs.getBoolean("folder_posters", false),
        originalFolderCover = savedChoice("original_folder_cover"),
        customFolderCover = savedChoice("custom_folder_cover"),
        virtualFolderCover = savedChoice("virtual_folder_cover"),
        generatedFolderCover = savedChoice("generated_folder_cover"),
        virtualGeneratedFolderCover = savedChoice("virtual_generated_cover", FolderCoverChoice.PERFORMER),
        virtualGeneratedVideoScenes = prefs.getBoolean("virtual_generated_video_scenes", true),
    )

    private fun savedChoice(key: String, default: FolderCoverChoice = FolderCoverChoice.ORIGINAL): FolderCoverChoice =
        runCatching { FolderCoverChoice.valueOf(prefs.getString(key, default.name)!!) }
            .getOrDefault(default)

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
            .putString("poster_mode", next.posterMode.name)
            .putBoolean("video_posters", next.videoPosters)
            .putBoolean("folder_posters", next.folderPosters)
            .putString("original_folder_cover", next.originalFolderCover.name)
            .putString("custom_folder_cover", next.customFolderCover.name)
            .putString("virtual_folder_cover", next.virtualFolderCover.name)
            .putString("generated_folder_cover", next.generatedFolderCover.name)
            .putString("virtual_generated_cover", next.virtualGeneratedFolderCover.name)
            .putBoolean("virtual_generated_video_scenes", next.virtualGeneratedVideoScenes)
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

    private val changes = MutableStateFlow(0)

    /** Goes up each time a result is saved or changed, so the app can notice when matches are waiting for review. */
    val revision = changes.asStateFlow()

    /** How many videos have matches waiting for your decision. */
    @Synchronized
    fun reviewCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM scene_metadata WHERE status='REVIEW'", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

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
        changes.value += 1
    }

    @Synchronized
    fun review(limit: Int = 100): List<SceneMetadataRecord> {
        val result = ArrayList<SceneMetadataRecord>()
        readableDatabase.query("scene_metadata", arrayOf("path"), "status=?", arrayOf("REVIEW"), null, null, "checked DESC", limit.coerceIn(1, 200).toString()).use { c ->
            while (c.moveToNext()) get(c.getString(0))?.let(result::add)
        }
        return result
    }

    /** Already approved matches can gain a poster when artwork is enabled later. */
    @Synchronized
    fun confirmed(limit: Int = 4000): List<SceneMetadataRecord> {
        val records = ArrayList<SceneMetadataRecord>()
        readableDatabase.query(
            "scene_metadata", arrayOf("path"), "status=?", arrayOf("CONFIRMED"),
            null, null, "checked DESC", limit.coerceIn(1, 5000).toString(),
        ).use { rows -> while (rows.moveToNext()) get(rows.getString(0))?.let(records::add) }
        return records
    }

    /** Reject just one inaccurate candidate, keeping other provider suggestions for the same file. */
    @Synchronized
    fun reject(path: String, providerId: String, sceneId: String) {
        val old = get(path) ?: return
        if (old.status != "REVIEW") return
        val current = runCatching { org.json.JSONArray(old.suggestions ?: "[]") }.getOrDefault(org.json.JSONArray())
        val remaining = org.json.JSONArray()
        for (i in 0 until current.length()) {
            val item = current.optJSONObject(i) ?: continue
            if (item.optString("provider") != providerId || item.optJSONObject("scene")?.optString("id") != sceneId) remaining.put(item)
        }
        writableDatabase.execSQL(
            "UPDATE scene_metadata SET status=?, suggestions=?, checked=? WHERE path=? AND status='REVIEW'",
            arrayOf(if (remaining.length() == 0) "DISMISSED" else "REVIEW", remaining.toString(), System.currentTimeMillis(), path),
        )
    }

    /** Apply user-requested search results. Previously confirmed matches are not overwritten by background jobs. */
    @Synchronized
    fun offerAlternatives(path: String, suggestions: String) {
        val row = get(path) ?: return
        if (row.status == "CONFIRMED") return
        writableDatabase.execSQL(
            "UPDATE scene_metadata SET status='REVIEW', suggestions=?, checked=? WHERE path=? AND status!='CONFIRMED'",
            arrayOf(suggestions, System.currentTimeMillis(), path),
        )
    }

    /** Forget "no match" and failed lookups so the next lookup tries them again. Confirmed and pending-review results stay. */
    @Synchronized
    fun forgetMisses(): Int = writableDatabase.delete("scene_metadata", "status IN ('NO_MATCH','RETRY')", null)

    /** Change of primary provider: retry only unresolved results, preserving confirmed and dismissed decisions. */
    @Synchronized
    fun retryUnresolved(): Int {
        return writableDatabase.delete(
            "scene_metadata", "status IN ('REVIEW','NO_MATCH','RETRY')", null,
        )
    }

    /** The user confirms a proposed provider scene. No artwork or file-path changes are made. */
    @Synchronized
    fun approve(path: String, selectedSceneJson: String) {
        val old = get(path) ?: return
        writableDatabase.execSQL(
            "UPDATE scene_metadata SET status='CONFIRMED', result=?, suggestions=NULL, checked=? WHERE path=? AND size=? AND modified=?",
            arrayOf(selectedSceneJson, System.currentTimeMillis(), old.path, old.size, old.modified),
        )
        changes.value += 1
    }

    @Synchronized
    fun dismiss(path: String) {
        writableDatabase.execSQL("UPDATE scene_metadata SET status='DISMISSED', suggestions=NULL WHERE path=? AND status='REVIEW'", arrayOf(path))
        changes.value += 1
    }

    @Synchronized
    fun retry(path: String) {
        writableDatabase.delete("scene_metadata", "path=?", arrayOf(path))
        changes.value += 1
    }

    /** Clear local scene results only. Does not touch SMB files or manual covers. */
    fun clearMatches(): Int = writableDatabase.delete("scene_metadata", null, null).also { changes.value += 1 }
}
