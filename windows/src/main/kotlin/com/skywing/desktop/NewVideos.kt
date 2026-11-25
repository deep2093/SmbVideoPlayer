package com.skywing.desktop

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.Locale

/** How long a video that arrived stays marked new. */
internal const val NEW_FOR_MS = 7 * 24 * 3600 * 1000L

private fun keyOf(path: String) = ShareHosts.canonical(path).lowercase(Locale.ROOT)

internal object NewVideos {
    /**
     * The lower-cased paths in [after] that are not in [before] and whose folder already had videos in [before].
     * The first scan of a folder, and a rebuilt index (empty [before]), mark nothing.
     */
    fun arrivals(before: List<WindowsMediaEntry>, after: List<WindowsMediaEntry>): List<String> {
        if (before.isEmpty()) return emptyList()
        val knownSources = before.mapTo(HashSet()) { it.sourceId }
        val known = before.mapTo(HashSet()) { keyOf(it.path) }
        return after.asSequence()
            .filter { it.sourceId in knownSources }
            .map { keyOf(it.path) }
            .filter { it !in known }
            .distinct()
            .toList()
    }
}

/** When each new video was first seen, and when each actor page was last opened (both in milliseconds). */
internal data class NewMarks(val firstSeen: Map<String, Long>, val opened: Map<String, Long>) {
    fun isNew(path: String, now: Long): Boolean = firstSeen[path]?.let { now - it <= NEW_FOR_MS } == true

    fun newCount(paths: Collection<String>, now: Long): Int = paths.count { isNew(it, now) }

    /** True when one of [paths] is new and was first seen after the page [key] was last opened. */
    fun dot(key: String, paths: Collection<String>, now: Long): Boolean {
        val openedAt = opened[key] ?: Long.MIN_VALUE
        return paths.any { isNew(it, now) && firstSeen.getValue(it) > openedAt }
    }
}

/**
 * The videos that arrived in known folders, kept on this PC in new-videos.json as
 * `{"version":1,"seen":[{"path","at"}],"opened":[{"key","at"}]}`. Paths are canonical and lower-cased. Entries older
 * than 7 days are dropped on every write. A file that can't be read, or isn't in this format, is never written over.
 */
internal class NewVideoStore(
    path: Path = libraryFile("new-videos.json"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val file = JsonFile(path)
    private val lock = file.lock

    /** Paths that were just renamed to: the next [add] drops them once, so a renamed video isn't an arrival. */
    private val renamed = HashSet<String>()

    fun add(paths: List<String>): Unit = synchronized(lock) {
        val skip = renamed.toSet()
        renamed.clear()
        val marks = all() ?: return
        val at = now()
        val seen = marks.firstSeen.toMutableMap()
        paths.map(::keyOf).filter { it !in skip && it !in seen }.forEach { seen[it] = at }
        if (seen.size != marks.firstSeen.size) write(NewMarks(seen, marks.opened))
    }

    fun played(path: String): Unit = synchronized(lock) {
        val marks = all() ?: return
        val key = keyOf(path)
        if (key in marks.firstSeen) write(NewMarks(marks.firstSeen - key, marks.opened))
    }

    fun openedActor(key: String): Unit = synchronized(lock) {
        val marks = all() ?: return
        write(NewMarks(marks.firstSeen, marks.opened + (key to now())))
    }

    fun renamePath(old: String, new: String): Unit = synchronized(lock) {
        val to = keyOf(new)
        renamed.add(to)
        val marks = all() ?: return
        val from = keyOf(old)
        val at = marks.firstSeen[from] ?: return
        write(NewMarks(marks.firstSeen - from + (to to at), marks.opened))
    }

    fun snapshot(): NewMarks = synchronized(lock) { all() ?: NewMarks(emptyMap(), emptyMap()) }

    /** Every mark, or null when the file can't be read now or isn't in this format. */
    private fun all(): NewMarks? = when (val read = file.read()) {
        is JsonFile.Read.Found -> runCatching {
            val version = read.root.get("version")
            if (version != null && runCatching { version.asInt }.getOrNull() != 1) return null
            val seen = read.root.get("seen")
            if (seen == null || !seen.isJsonArray) return null
            NewMarks(
                entries(seen.asJsonArray, "path"),
                read.root.get("opened")?.takeIf { it.isJsonArray }?.let { entries(it.asJsonArray, "key") } ?: emptyMap(),
            )
        }.getOrDefault(NewMarks(emptyMap(), emptyMap()))
        JsonFile.Read.Missing -> NewMarks(emptyMap(), emptyMap())
        JsonFile.Read.Locked -> null
    }

    /** The entries of [array] as name to time; a malformed entry is skipped. */
    private fun entries(array: JsonArray, name: String): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        array.forEach { element ->
            runCatching {
                val entry = element.asJsonObject
                entry.get(name).asString.takeIf { it.isNotBlank() }?.let { result[it] = entry.get("at").asLong }
            }
        }
        return result
    }

    private fun write(marks: NewMarks) {
        val limit = now() - NEW_FOR_MS
        fun list(map: Map<String, Long>, name: String) = JsonArray().also { array ->
            map.filterValues { it >= limit }.forEach { (key, at) ->
                array.add(JsonObject().apply {
                    addProperty(name, key)
                    addProperty("at", at)
                })
            }
        }
        file.write(JsonObject().apply {
            addProperty("version", 1)
            add("seen", list(marks.firstSeen, "path"))
            add("opened", list(marks.opened, "key"))
        })
    }
}
