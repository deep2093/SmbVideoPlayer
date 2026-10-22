package com.deepak.videolibrary.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Virtual folder you made and named yourself (for example "Hollywood Movies"). It holds the drive folders you
 * chose; the subfolders inside it (one per name found in the file names) are generated from those folders.
 *
 * [renames] and [hidden] are your own changes to generated subfolders, keyed by the folder's identity
 * ([com.deepak.videolibrary.scan.VirtualNames.identity]). They survive every rescan. Nothing on the drive changes.
 */
data class VirtualSet(
    val id: String,
    val name: String,
    val folders: List<String> = emptyList(),
    val renames: Map<String, String> = emptyMap(),
    val hidden: Set<String> = emptySet(),
    /** Single videos chosen on their own; they are filed into the name subfolders like everything else. */
    val files: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()

    /** Everything the set reads, for the library queries. */
    fun members(): FolderMembers = FolderMembers(folders, files)
}

/** The Virtual folders of each server, kept as JSON in a small preferences file. */
class VirtualSetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("virtual_sets", Context.MODE_PRIVATE)

    @Synchronized
    fun load(key: String): List<VirtualSet> = decode(prefs.getString(key, null))

    @Synchronized
    fun save(key: String, sets: List<VirtualSet>) {
        prefs.edit().putString(key, encode(sets)).apply()
    }

    companion object {
        const val MAX_FOLDERS = 100
        const val MAX_FILES = 500

        internal fun encode(sets: List<VirtualSet>): String = JSONArray().apply {
            sets.forEach { s ->
                put(
                    JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put("folders", JSONArray(s.folders))
                        .put("renames", JSONObject().apply { s.renames.forEach { (k, v) -> put(k, v) } })
                        .put("hidden", JSONArray(s.hidden.toList()))
                        .put("files", JSONArray(s.files)),
                )
            }
        }.toString()

        internal fun decode(text: String?): List<VirtualSet> {
            if (text.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val renames = LinkedHashMap<String, String>()
                    o.optJSONObject("renames")?.let { r -> r.keys().forEach { k -> r.optString(k).takeIf { it.isNotBlank() }?.let { renames[k] = it } } }
                    VirtualSet(
                        id, name, strings(o.optJSONArray("folders")), renames, strings(o.optJSONArray("hidden")).toSet(),
                        strings(o.optJSONArray("files")).filter { it.isNotEmpty() },
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        // A folder may be "" (the whole share).
        private fun strings(array: JSONArray?): List<String> =
            if (array == null) emptyList() else (0 until array.length()).map { array.optString(it).trim('/') }.distinct()
    }
}

/** One generated subfolder of a [VirtualSet]: every video whose file name has this name before the first hyphen. */
data class VirtualGroup(val identity: String, val title: String, val videos: List<VideoEntity>) {
    val totalSize: Long get() = videos.sumOf { it.size }
    val lastModified: Long get() = videos.maxOfOrNull { it.modified } ?: 0L
}

object VirtualGroups {
    /**
     * Files [videos] (everything indexed inside the set's drive folders) under their names. A video filed by hand
     * (customGroup) stays only in the folder it was given. The set's renames and hidden folders are applied, so a
     * rescan never undoes them; renaming a folder to the name of another merges them.
     */
    fun build(
        set: VirtualSet,
        videos: List<VideoEntity>,
        rules: com.deepak.videolibrary.scan.GroupingSwitches = com.deepak.videolibrary.scan.GroupingSwitches(),
    ): List<VirtualGroup> {
        val titles = LinkedHashMap<String, String>()
        val members = LinkedHashMap<String, LinkedHashMap<String, VideoEntity>>()
        for (video in videos) {
            val names = video.customGroup?.takeIf { it.isNotBlank() }?.let { listOf(com.deepak.videolibrary.scan.VirtualNames.display(it)) }
                ?: com.deepak.videolibrary.scan.VirtualNames.of(video.name, rules)
            for (name in names) {
                val generated = com.deepak.videolibrary.scan.VirtualNames.identity(name)
                if (generated in set.hidden) continue
                val title = set.renames[generated] ?: name
                val identity = com.deepak.videolibrary.scan.VirtualNames.identity(title)
                if (identity in set.hidden) continue
                titles.putIfAbsent(identity, title)
                members.getOrPut(identity) { LinkedHashMap() }[video.relativePath] = video
            }
        }
        return members.map { (identity, byPath) ->
            VirtualGroup(identity, titles.getValue(identity), byPath.values.sortedBy { it.name.lowercase(java.util.Locale.ROOT) })
        }
    }

    /** [sort] is one of NAME, COUNT, DATE, SIZE (the same choices as the other folder pages). */
    fun sorted(groups: List<VirtualGroup>, sort: String, descending: Boolean): List<VirtualGroup> {
        val base = compareBy<VirtualGroup> { it.title.lowercase(java.util.Locale.ROOT) }
        val order: Comparator<VirtualGroup> = when (sort) {
            "COUNT" -> compareBy<VirtualGroup> { it.videos.size }.then(base)
            "DATE" -> compareBy<VirtualGroup> { it.lastModified }.then(base)
            "SIZE" -> compareBy<VirtualGroup> { it.totalSize }.then(base)
            else -> base
        }
        return groups.sortedWith(if (descending) order.reversed() else order)
    }
}

/** What a Virtual folder page shows: its videos and the subfolders built from them. [loaded] is false until the first result. */
data class VirtualContent(val videos: List<VideoEntity>, val groups: List<VirtualGroup>, val loaded: Boolean)

/**
 * Single videos of a Custom folder grouped by name with that place's switches: folder identity -> its videos.
 * A video filed by hand stays in its own folder. Sorted by name.
 */
fun groupByNames(videos: List<VideoEntity>, rules: com.deepak.videolibrary.scan.GroupingSwitches): java.util.SortedMap<String, List<VideoEntity>> {
    val names = com.deepak.videolibrary.scan.VirtualNames
    val out = java.util.TreeMap<String, LinkedHashMap<String, VideoEntity>>()
    for (video in videos) {
        val found = video.customGroup?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: names.of(video.name, rules)
        found.forEach { name -> out.getOrPut(names.identity(name)) { LinkedHashMap() }[video.relativePath] = video }
    }
    return java.util.TreeMap(out.mapValues { it.value.values.toList() })
}
