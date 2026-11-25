package com.skywing.desktop

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** The seconds a video lasts, as the tiles show them. */
internal fun formatLength(seconds: Double): String = formatClock(seconds)

/**
 * The length of each video, kept on this PC in lengths.json as `{"version":1,"entries":[{"key","seconds"}]}`. Keys are
 * [ThumbnailStore.keyOf] (path and modified time), so a changed file is read again. Writes are held back to one per
 * [WRITE_EVERY_MS]; [flush] writes at once. A file that can't be read is never written over.
 */
internal class VideoLengthStore(
    path: Path = libraryFile("lengths.json"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val file = JsonFile(path)
    private val lock = file.lock
    private val revisionState = mutableIntStateOf(0)

    /** Goes up after each successful write, so tiles read the lengths again. */
    val revision: State<Int> get() = revisionState

    private var loaded = false
    private var entries: MutableMap<String, Double>? = null
    private var dirty = false
    private var lastWrite = Long.MIN_VALUE

    /** False when the file can't be read, so nothing can be learned or kept; there is no point reading videos then. */
    internal val usable: Boolean get() = synchronized(lock) { entries() != null }

    fun get(key: String): Double? = synchronized(lock) { entries()?.get(key) }

    fun record(key: String, seconds: Double): Unit = synchronized(lock) {
        if (!seconds.isFinite() || seconds <= 0) return
        val map = entries() ?: return
        if (map[key] == seconds) return
        map[key] = seconds
        dirty = true
    }

    fun flushIfDue(): Unit = synchronized(lock) {
        if (dirty && (lastWrite == Long.MIN_VALUE || now() - lastWrite >= WRITE_EVERY_MS)) write()
    }

    fun flush(): Unit = synchronized(lock) { if (dirty) write() }

    private fun write() {
        val map = entries ?: return
        // Held back even when the write fails, so a broken disk is not hit again on every call.
        lastWrite = now()
        val list = JsonArray()
        map.forEach { (key, seconds) ->
            list.add(JsonObject().apply {
                addProperty("key", key)
                addProperty("seconds", seconds)
            })
        }
        file.write(JsonObject().apply {
            addProperty("version", 1)
            add("entries", list)
        })
        dirty = false
        revisionState.intValue++
    }

    /** Every length, read once; null when the file can't be read, has another version or has the wrong shape. */
    private fun entries(): MutableMap<String, Double>? {
        if (loaded) return entries
        loaded = true
        entries = when (val read = file.read()) {
            is JsonFile.Read.Found -> runCatching {
                val version = read.root.get("version")
                if (version != null && runCatching { version.asInt }.getOrNull() != 1) return@runCatching null
                val array = read.root.get("entries")
                if (array == null || !array.isJsonArray) return@runCatching null
                val map = LinkedHashMap<String, Double>()
                array.asJsonArray.forEach { element ->
                    runCatching {
                        val entry = element.asJsonObject
                        val seconds = entry.get("seconds").asDouble
                        val key = entry.get("key").asString
                        if (key.isNotBlank() && seconds.isFinite() && seconds > 0) map[key] = seconds
                    }
                }
                map
            }.getOrNull()
            JsonFile.Read.Missing -> LinkedHashMap()
            JsonFile.Read.Locked -> null
        }
        return entries
    }

    private companion object {
        const val WRITE_EVERY_MS = 10_000L
    }
}

/**
 * Learns the lengths the library still lacks, one file at a time. [idle] returns when nothing waits and nothing plays;
 * it is called before each read. Videos with a known length, and ones that failed this session, are skipped; three
 * failures in a row stop the run (the drive is probably away). Only the files given are read; no folder is looked at.
 */
internal class LengthCatchUp(private val read: suspend (File) -> Double?, private val store: VideoLengthStore) {
    private val failed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    suspend fun run(videos: List<Pair<File, Long>>, idle: suspend () -> Unit) {
        if (!store.usable) return
        try {
            var inARow = 0
            for ((video, stamp) in videos) {
                val key = ThumbnailStore.keyOf(video.path, stamp)
                if (key in failed || store.get(key) != null) continue
                idle()
                val seconds = try {
                    read(video)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                if (seconds != null && seconds.isFinite() && seconds > 0) {
                    inARow = 0
                    runCatching { store.record(key, seconds); store.flushIfDue() }
                } else {
                    failed += key
                    if (++inARow >= MAX_FAILURES_IN_A_ROW) break
                }
            }
        } finally {
            runCatching { store.flush() }
        }
    }

    private companion object {
        const val MAX_FAILURES_IN_A_ROW = 3
    }
}
