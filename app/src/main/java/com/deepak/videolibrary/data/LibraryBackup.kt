package com.deepak.videolibrary.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

/** Portable organization/history, deliberately excluding credentials and remote file contents. */
class LibraryBackup(private val db: LibraryDatabase, private val libraryId: String, private val key: String) {
    suspend fun export(): String = db.withTransaction {
        val dao = db.libraryState()
        JSONObject().put("format",1).put("libraryId",libraryId).apply {
            put("folders",JSONArray().apply { dao.manualFolders().forEach { put(it.name) } })
            put("assignments",JSONArray().apply { dao.legacyVideos().filter { it.customGroup != null }.forEach {
                put(JSONObject().put("path",it.relativePath).put("group",it.customGroup))
            } })
            put("collections",JSONArray().apply { dao.savedCollections(key).forEach {
                put(JSONObject().put("id",it.id).put("name",it.name).put("term",it.term).put("sourcePath",it.sourcePath ?: JSONObject.NULL).put("recursive",it.includeSubfolders))
            } })
            put("hidden",JSONArray().apply { dao.savedHidden(key).forEach { put(JSONObject().put("kind",it.kind).put("target",it.target).put("label",it.label)) } })
            put("progress",JSONArray().apply { dao.savedProgress(key).forEach {
                put(JSONObject().put("path",it.path).put("position",it.positionMs).put("duration",it.durationMs).put("hidden",it.hidden).put("updated",it.updatedAt))
            } })
        }.toString(2)
    }

    suspend fun restore(text: String): Int {
        require(text.length <= 10 * 1024 * 1024) { "Backup too large" }
        val root = JSONObject(text)
        require(root.getInt("format") == 1 && root.getString("libraryId") == libraryId) { "Choose a backup for this server, account and starting folder" }
        fun path(value: String): String {
            require(value.length <= 4096 && (value.isEmpty() || value.split('/').none { it == "." || it == ".." || it.isEmpty() }) && '\\' !in value)
            return value
        }
        fun name(value: String): String { require(value.isNotBlank() && value.length <= 512); return value }
        fun rows(field: String, action: (JSONObject) -> Unit) { val a = root.getJSONArray(field); require(a.length() <= 100000); for(i in 0 until a.length()) action(a.getJSONObject(i)) }
        // Validate before entering the transaction; failed input never partially restores data.
        rows("assignments") { path(it.getString("path")); name(it.getString("group")) }
        rows("collections") { name(it.getString("name")); if (!it.isNull("sourcePath")) path(it.getString("sourcePath")) }
        rows("progress") { path(it.getString("path")) }
        rows("hidden") { require(it.getString("kind") in setOf("FILE","DIRECTORY","GROUP","MATCH")) }
        return db.withTransaction {
            val dao = db.libraryState()
            val folders = root.getJSONArray("folders"); require(folders.length() <= 100000)
            for(i in 0 until folders.length()) db.dao().createFolder(CustomFolderEntity(name(folders.getString(i))))
            val mappings = HashMap<String,String>()
            val collections = root.getJSONArray("collections")
            for(i in 0 until collections.length()) {
                val row = collections.getJSONObject(i)
                val existing = dao.savedCollections(key).firstOrNull { it.name == row.getString("name") }
                val id = existing?.id ?: dao.createMatch(MatchFolderEntity(connectionKey=key,name=row.getString("name"),term=row.getString("term"),sourcePath=if(row.isNull("sourcePath"))null else row.getString("sourcePath"),includeSubfolders=row.getBoolean("recursive")))
                mappings[row.getLong("id").toString()] = id.toString()
            }
            var skipped = 0
            val assignments = root.getJSONArray("assignments")
            for(i in 0 until assignments.length()) { val row=assignments.getJSONObject(i); if(dao.restoreAssignment(row.getString("path"),row.getString("group")) == 0) skipped++ }
            val hidden = root.getJSONArray("hidden")
            for(i in 0 until hidden.length()) {
                val row=hidden.getJSONObject(i); val kind=row.getString("kind")
                val target = if(kind=="MATCH") mappings[row.getString("target")] else row.getString("target")
                if(target != null) dao.hide(HiddenItem(key,kind,target,row.getString("label")))
            }
            val progress = root.getJSONArray("progress")
            for(i in 0 until progress.length()) { val row=progress.getJSONObject(i); dao.putProgress(PlaybackStateEntity(key,row.getString("path"),row.getLong("position").coerceAtLeast(0),row.getLong("duration").coerceAtLeast(0),row.getBoolean("hidden"),row.getLong("updated"))) }
            skipped
        }
    }
}
