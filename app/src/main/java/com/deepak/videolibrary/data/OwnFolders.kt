package com.deepak.videolibrary.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A custom folder the user named and filled by hand: chosen drive folders (each with everything inside it) and
 * single files. Nothing on the drive changes.
 */
data class OwnFolder(
    val id: String,
    val name: String,
    val folders: List<String> = emptyList(),
    val files: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()

    /** The library rule for everything shown in this folder. */
    fun members(): FolderMembers = FolderMembers(folders, files)
}

/** Drive folders (with their sub-folders) and single files, as share-relative paths. */
data class FolderMembers(val folders: List<String>, val files: List<String>)

/** Own folders of each server, kept in a small preferences file as JSON. */
class OwnFolderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("own_folders", Context.MODE_PRIVATE)

    @Synchronized
    fun load(key: String): List<OwnFolder> = decode(prefs.getString(key, null))

    @Synchronized
    fun save(key: String, folders: List<OwnFolder>) {
        prefs.edit().putString(key, encode(folders)).apply()
    }

    companion object {
        const val MAX_MEMBERS = 500

        internal fun encode(folders: List<OwnFolder>): String = JSONArray().apply {
            folders.forEach { f ->
                put(
                    JSONObject()
                        .put("id", f.id)
                        .put("name", f.name)
                        .put("folders", JSONArray(f.folders))
                        .put("files", JSONArray(f.files)),
                )
            }
        }.toString()

        internal fun decode(text: String?): List<OwnFolder> {
            if (text.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    // A folder may be "" (the whole share); a file never is.
                    OwnFolder(id, name, strings(o.optJSONArray("folders")), strings(o.optJSONArray("files")).filter { it.isNotEmpty() })
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        private fun strings(array: JSONArray?): List<String> =
            if (array == null) emptyList() else (0 until array.length()).map { array.optString(it).trim('/') }.distinct()
    }
}
