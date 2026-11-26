package com.skywing.desktop

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Path

/** The last seconds of a video: the resume list drops a video inside them, and they count as watched to the end. */
internal const val FINISHED_TAIL_SECONDS = 30.0

internal fun finishedAt(position: Double, duration: Double): Boolean =
    position.isFinite() && duration.isFinite() && duration > 0 && position >= duration - FINISHED_TAIL_SECONDS

/**
 * The videos watched to the end, kept on this PC in watched.json as `{"version":1,"watched":[{"path","at"}]}`.
 * Paths are canonical (as the history keeps them) and lower-cased. A file that can't be read is never written over.
 */
internal class WindowsWatchedStore(path: Path = libraryFile("watched.json")) {
    private val file = JsonFile(path)
    private val lock = file.lock

    /** The lower-cased canonical paths of the watched videos. */
    fun load(): Set<String> = synchronized(lock) { (all() ?: emptyList()).map { it.first }.toSet() }

    fun mark(file: File, at: Long = System.currentTimeMillis()) = mark(listOf(file), at)

    /** Marks every one of [files] watched at [at], with one read and one write of the file. */
    fun mark(files: Collection<File>, at: Long = System.currentTimeMillis()): Unit = synchronized(lock) {
        val marks = all() ?: return
        val paths = files.mapTo(LinkedHashSet()) { markKey(it.absolutePath) }
        write(marks.filterNot { it.first in paths } + paths.map { it to at })
    }

    fun unmark(file: File) = unmark(listOf(file))

    /** Takes the marks of [files] off, with one read and at most one write of the file. */
    fun unmark(files: Collection<File>): Unit = synchronized(lock) {
        val marks = all() ?: return
        val paths = files.mapTo(HashSet()) { markKey(it.absolutePath) }
        if (marks.any { it.first in paths }) write(marks.filterNot { it.first in paths })
    }

    fun renamePath(old: String, new: String): Unit = synchronized(lock) {
        val marks = all() ?: return
        val from = markKey(old)
        if (marks.none { it.first == from }) return
        val to = markKey(new)
        val at = marks.first { it.first == from }.second
        write(marks.filterNot { it.first == from || it.first == to } + (to to at))
    }

    /** Every mark as path to time, or null when the file can't be read now. */
    private fun all(): List<Pair<String, Long>>? = when (val read = file.read()) {
        is JsonFile.Read.Found -> runCatching {
            // A file of another version or shape is left alone, like one that can't be read.
            val version = read.root.get("version")
            if (version != null && runCatching { version.asInt }.getOrNull() != 1) return null
            val watched = read.root.get("watched")
            if (watched == null || !watched.isJsonArray) return null
            watched.asJsonArray.mapNotNull { element ->
                runCatching {
                    val mark = element.asJsonObject
                    mark.get("path").asString.takeIf { it.isNotBlank() }?.let { it to mark.get("at").asLong }
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        JsonFile.Read.Missing -> emptyList()
        JsonFile.Read.Locked -> null
    }

    private fun write(marks: List<Pair<String, Long>>) {
        val list = JsonArray()
        marks.forEach { (path, at) ->
            list.add(JsonObject().apply {
                addProperty("path", path)
                addProperty("at", at)
            })
        }
        file.write(JsonObject().apply {
            addProperty("version", 1)
            add("watched", list)
        })
    }
}
