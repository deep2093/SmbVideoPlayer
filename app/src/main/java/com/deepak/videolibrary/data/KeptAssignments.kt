package com.deepak.videolibrary.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A video's path, size and date (for matching saved posters to videos). */
data class VideoStamp(val relativePath: String, val size: Long, val modified: Long)

/** A video's custom folder, read just before a scan drops the video because it was not seen. */
data class AssignmentRow(val relativePath: String, val name: String, val size: Long, val customGroup: String)

/**
 * Keeps the custom folder of videos a scan removed, and gives it back when the same file is found again.
 *
 * A custom folder is stored on the video's row, and a clean scan deletes rows it did not see. So a file that was
 * renamed on the PC, renamed only in upper/lower case, or in a folder that was moved away and back used to come back
 * without its folder. Now the folder is remembered here and handed back to the file that returns: the same path, the
 * same path in other letter case, or (for a rename) the only remembered file with the same name and size.
 *
 * Nothing here touches the drive. Entries older than a year, or beyond [MAX_ENTRIES], are forgotten.
 */
class KeptAssignments(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Entry(val path: String, val name: String, val size: Long, val group: String, val keptAt: Long)

    private val entries = LinkedHashMap<String, Entry>()
    // Lookups for a scan of tens of thousands of new files: lower-case path, and lower-case name + size.
    private val byLowerPath = HashMap<String, String>()
    private val byNameSize = HashMap<String, MutableList<String>>()
    private var loaded = false
    private var dirty = false

    private fun nameSizeKey(name: String, size: Long) = name.lowercase() + "\u0000" + size

    private fun reindex() {
        byLowerPath.clear()
        byNameSize.clear()
        for (e in entries.values) {
            byLowerPath[e.path.lowercase()] = e.path
            byNameSize.getOrPut(nameSizeKey(e.name, e.size)) { ArrayList(1) }.add(e.path)
        }
    }

    @Synchronized
    fun remember(rows: List<AssignmentRow>) {
        if (rows.isEmpty()) return
        load()
        val now = clock()
        for (row in rows) {
            if (row.customGroup.isBlank()) continue
            entries.remove(row.relativePath)
            entries[row.relativePath] = Entry(row.relativePath, row.name, row.size, row.customGroup, now)
        }
        prune(now)
        reindex()
        dirty = true
        flush()
    }

    /** The custom folder for a newly found video, or null. A match is used once and then forgotten. */
    @Synchronized
    fun claim(relativePath: String, name: String, size: Long): String? {
        load()
        if (entries.isEmpty()) return null
        val path = when {
            entries.containsKey(relativePath) -> relativePath
            else -> byLowerPath[relativePath.lowercase()] ?: byNameSize[nameSizeKey(name, size)]?.singleOrNull()
        } ?: return null
        val match = entries.remove(path) ?: return null
        byLowerPath.remove(match.path.lowercase())
        byNameSize[nameSizeKey(match.name, match.size)]?.let { list ->
            list.remove(match.path)
            if (list.isEmpty()) byNameSize.remove(nameSizeKey(match.name, match.size))
        }
        dirty = true
        return match.group
    }

    /** Writes pending changes. Cheap when nothing changed. */
    @Synchronized
    fun flush() {
        if (!dirty) return
        val array = JSONArray()
        entries.values.forEach { e ->
            array.put(JSONObject().put("path", e.path).put("name", e.name).put("size", e.size).put("group", e.group).put("kept", e.keptAt))
        }
        val text = JSONObject().put("format", 1).put("entries", array).toString()
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(temporary).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                temporary.delete()
                error("rename failed")
            }
            dirty = false
        }
    }

    @Synchronized
    internal fun size(): Int {
        load()
        return entries.size
    }

    private fun prune(now: Long) {
        val cutoff = now - MAX_AGE_MS
        entries.values.removeAll { it.keptAt < cutoff }
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val array = root.optJSONArray("entries") ?: return@runCatching
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val path = o.optString("path").takeIf { it.isNotBlank() } ?: continue
                val group = o.optString("group").takeIf { it.isNotBlank() } ?: continue
                entries[path] = Entry(path, o.optString("name"), o.optLong("size", -1L), group, o.optLong("kept", 0L))
            }
        }
        // A damaged file only loses the remembered folders; the library itself is untouched.
        prune(clock())
        reindex()
    }

    companion object {
        const val MAX_ENTRIES = 20_000
        val MAX_AGE_MS: Long = 365L * 24 * 60 * 60 * 1000
    }
}
