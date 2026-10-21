package com.deepak.videolibrary.data

import androidx.sqlite.db.SimpleSQLiteQuery

/** All values are bound parameters; only allowlisted sort expressions are interpolated. */
data class LibraryScope(
    val key: String,
    val query: String = "",
    val group: String? = null,
    val directory: String? = null,
    val subtree: Boolean = false,
    val groupMatches: Boolean = false,
    val pathMatches: Boolean = false,
    val sort: String = "NAME",
    val descending: Boolean = false,
    val match: String? = null,
    val sourcePath: String? = null,
    val includeSubfolders: Boolean = true,
    /** An own custom folder: only videos inside these drive folders (with sub-folders) or these exact files. */
    val members: FolderMembers? = null,
)

object LibraryQueries {
    private fun where(s: LibraryScope): Pair<String, MutableList<Any?>> {
        val args = mutableListOf<Any?>(s.key)
        val text =
            StringBuilder(
                " NOT EXISTS (SELECT 1 FROM hidden_items h WHERE h.connectionKey=? AND ((h.kind='FILE' AND h.target=v.relativePath) OR (h.kind='DIRECTORY' AND (h.target=v.relativePath OR substr(v.relativePath,1,length(h.target)+1)=h.target||'/')) OR (h.kind='GROUP' AND h.target=COALESCE(v.customGroup,v.groupKey))))",
            )
        if (s.group != null) {
            text.append(" AND COALESCE(v.customGroup,v.groupKey)=?")
            args.add(s.group)
        }
        s.sourcePath?.let { path ->
            text.append(if (s.includeSubfolders && path.isEmpty()) "" else if (s.includeSubfolders)
                " AND (v.parentDir=? OR substr(v.parentDir,1,length(?)+1)=?||'/')" else " AND v.parentDir=?")
            if (s.includeSubfolders && path.isNotEmpty()) args.addAll(listOf(path, path, path))
            else if (!s.includeSubfolders) args.add(path)
        }
        s.members?.let { m ->
            val parts = ArrayList<String>()
            m.folders.forEach { path ->
                if (path.isEmpty()) {
                    parts += "1"
                } else {
                    parts += "(v.parentDir=? OR substr(v.parentDir,1,length(?)+1)=?||'/')"
                    args.addAll(listOf(path, path, path))
                }
            }
            m.files.forEach { path ->
                parts += "v.relativePath=?"
                args.add(path)
            }
            text.append(if (parts.isEmpty()) " AND 0" else " AND (" + parts.joinToString(" OR ") + ")")
        }
        if (s.match != null) {
            text.append(" AND instr(lower(v.name),lower(?))>0")
            args.add(s.match)
        }
        s.directory?.let { dir ->
            if (!s.subtree) {
                text.append(" AND v.parentDir=?")
                args.add(dir)
            } else if (dir.isNotEmpty()) {
                text.append(" AND (v.parentDir=? OR substr(v.parentDir,1,length(?)+1)=?||'/')")
                args.addAll(listOf(dir, dir, dir))
            }
        }
        if (s.query.isNotBlank()) {
            text.append(" AND (instr(lower(v.name),lower(?))>0")
            args.add(s.query.trim())
            if (s.groupMatches) {
                text.append(" OR instr(lower(COALESCE(v.customGroup,v.groupKey)),lower(?))>0")
                args.add(s.query.trim())
            }
            if (s.pathMatches) {
                text.append(" OR instr(lower(v.parentDir),lower(?))>0")
                args.add(s.query.trim())
            }
            text.append(")")
        }
        return text.toString() to args
    }
    fun videos(s: LibraryScope): SimpleSQLiteQuery {
        val (w, args) = where(s)
        val order = when (s.sort) {
            "NEWEST" -> "v.addedAt"
            "MODIFIED" -> "v.modified"
            "SIZE" -> "v.size"
            else -> "v.name COLLATE NOCASE"
        }
        return SimpleSQLiteQuery(
            "SELECT v.* FROM videos v WHERE $w ORDER BY $order ${if (s.descending) "DESC" else "ASC"}, v.relativePath",
            args.toTypedArray(),
        )
    }
    /** Keyset pagination keeps playback memory bounded independently of library size. */
    fun batch(s: LibraryScope, after: VideoEntity? = null, includeStart: Boolean = false, limit: Int = 100): SimpleSQLiteQuery {
        val (w, args) = where(s)
        val expression = when (s.sort) { "NEWEST" -> "v.addedAt"; "MODIFIED" -> "v.modified"; "SIZE" -> "v.size"; else -> "v.name COLLATE NOCASE" }
        val value: Any? = after?.let { when (s.sort) { "NEWEST" -> it.addedAt; "MODIFIED" -> it.modified; "SIZE" -> it.size; else -> it.name } }
        val anchor = if (after == null) "" else {
            args.addAll(listOf(value, value, after.relativePath))
            " AND ($expression ${if (s.descending) "<" else ">"} ? OR ($expression = ? AND v.relativePath ${if (includeStart) ">=" else ">"} ?))"
        }
        args.add(limit.coerceIn(1, 200))
        return SimpleSQLiteQuery("SELECT v.* FROM videos v WHERE $w$anchor ORDER BY $expression ${if (s.descending) "DESC" else "ASC"}, v.relativePath LIMIT ?", args.toTypedArray())
    }

    fun count(
        s: LibraryScope,
    ): SimpleSQLiteQuery {
        val(w, a) = where(s)
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM videos v WHERE $w", a.toTypedArray())
    }
    fun groups(s: LibraryScope, sort: String, descending: Boolean): SimpleSQLiteQuery {
        // Virtual-folder search matches the FOLDER NAME only, never the names of the files inside it.
        val folderText = s.query.trim()
        val (baseWhere, baseArgs) = where(s.copy(query = "", groupMatches = false))
        val w = if (folderText.isEmpty()) baseWhere else "$baseWhere AND instr(lower(COALESCE(v.customGroup,v.groupKey)),lower(?))>0"
        val a: List<Any?> = if (folderText.isEmpty()) baseArgs else baseArgs + folderText
        val order = when (sort) {
            "COUNT" -> "count"
            "DATE" -> "lastModified"
            "SIZE" -> "totalSize"
            else -> "groupKey COLLATE NOCASE"
        }
        return SimpleSQLiteQuery(
            "SELECT COALESCE(v.customGroup,v.groupKey) groupKey, COUNT(*) count, SUM(CASE WHEN v.thumbStatus='DONE' THEN 1 ELSE 0 END) thumbsDone, SUM(v.size) totalSize, MAX(v.modified) lastModified FROM videos v WHERE $w GROUP BY COALESCE(v.customGroup,v.groupKey) UNION ALL SELECT f.name,0,0,0,0 FROM custom_folders f WHERE NOT EXISTS(SELECT 1 FROM videos WHERE COALESCE(customGroup,groupKey)=f.name) AND instr(lower(f.name),lower(?))>0 AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.connectionKey=? AND h.kind='GROUP' AND h.target=f.name) ORDER BY $order ${if (descending) "DESC" else "ASC"}",
            (
                a +
                    listOf(s.query.trim(), s.key)
            ).toTypedArray(),
        )
    }
    fun continuing(key: String): SimpleSQLiteQuery {
        val(w, a) = where(LibraryScope(key))
        return SimpleSQLiteQuery(
            "SELECT v.* FROM videos v JOIN playback_state p ON p.path=v.relativePath AND p.connectionKey=? WHERE $w AND p.hidden=0 AND p.positionMs>15000 AND (p.durationMs<=0 OR p.positionMs<p.durationMs-10000) ORDER BY p.updatedAt DESC LIMIT 18",
            (
                listOf(key) +
                    a
            ).toTypedArray(),
        )
    }
    fun coverIds(
        s: LibraryScope,
    ): SimpleSQLiteQuery {
        val(w, a) = where(s)
        return SimpleSQLiteQuery(
            "SELECT v.id FROM videos v WHERE $w AND v.thumbStatus='DONE' ORDER BY v.name COLLATE NOCASE",
            a.toTypedArray(),
        )
    }
    fun thumbnailFailures(
        key: String,
    ): SimpleSQLiteQuery {
        val(w, a) = where(LibraryScope(key))
        return SimpleSQLiteQuery(
            "SELECT v.* FROM videos v WHERE $w AND v.thumbStatus='FAILED' ORDER BY v.name COLLATE NOCASE",
            a.toTypedArray(),
        )
    }
    fun directories(s: LibraryScope): SimpleSQLiteQuery {
        val dir = s.directory ?: ""
        val(w, a) = where(s.copy(subtree = true, pathMatches = true))
        val start = dir.length + if (dir.isEmpty()) 1 else 2
        val child = "substr(v.parentDir,$start,instr(substr(v.parentDir,$start)||'/','/')-1)"
        val path = if (dir.isEmpty()) child else "?||'/'||$child"
        return SimpleSQLiteQuery(
            "SELECT $path path, COUNT(*) count, SUM(CASE WHEN v.thumbStatus='DONE' THEN 1 ELSE 0 END) thumbsDone FROM videos v WHERE $w AND v.parentDir!=? GROUP BY path ORDER BY path COLLATE NOCASE",
            (
                (if (dir.isEmpty()) emptyList() else listOf(dir)) +
                    a +
                    dir
            ).toTypedArray(),
        )
    }
    fun allDirectories(s: LibraryScope): SimpleSQLiteQuery {
        val(w, a) = where(s.copy(subtree = true, pathMatches = true))
        return SimpleSQLiteQuery(
            "SELECT v.parentDir path,COUNT(*) count,SUM(CASE WHEN v.thumbStatus='DONE' THEN 1 ELSE 0 END) thumbsDone FROM videos v WHERE $w AND v.parentDir!=? GROUP BY v.parentDir ORDER BY v.parentDir COLLATE NOCASE",
            (
                a +
                    (s.directory ?: "")
            ).toTypedArray(),
        )
    }
    fun matchingFolders(key: String, query: String): SimpleSQLiteQuery {
        val(w, a) = where(LibraryScope(key))
        return SimpleSQLiteQuery(
            "SELECT f.*,COUNT(v.id) count,SUM(CASE WHEN v.thumbStatus='DONE' THEN 1 ELSE 0 END) thumbsDone,COALESCE(MAX(v.modified),0) lastModified FROM match_folders f LEFT JOIN videos v ON ((f.sourcePath IS NULL AND instr(lower(v.name),lower(f.term))>0) OR (f.sourcePath IS NOT NULL AND (v.parentDir=f.sourcePath OR (f.includeSubfolders=1 AND (f.sourcePath='' OR substr(v.parentDir,1,length(f.sourcePath)+1)=f.sourcePath||'/'))))) AND $w WHERE f.connectionKey=? AND instr(lower(f.name),lower(?))>0 AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.connectionKey=? AND h.kind='MATCH' AND h.target=CAST(f.id AS TEXT)) GROUP BY f.id ORDER BY f.name COLLATE NOCASE",
            (
                a +
                    listOf(key, query, key)
            ).toTypedArray(),
        )
    }
}
