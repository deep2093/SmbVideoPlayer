package com.skywing.desktop

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.UTFDataFormatException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.prefs.Preferences
import kotlin.math.abs
import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class WindowsMediaEntry(
    val path: String,
    val sourceId: String,
    val sourceType: String,
    val sourceName: String,
    val size: Long,
    val modified: Long,
) {
    /** Where the file is read from now: under another address of its network drive when the main one is down. */
    val file: File get() = File(ShareHosts.reachable(path))
}

/**
 * The saved library: every video of every source, in `media-index.bin`. It is read once and kept in memory; every
 * change goes through this object (one at a time) and is written straight through, followed by a good copy,
 * `media-index.bak`. An index that can't be read (antivirus lock, half-written file, disk error) is not an empty
 * library: it is read again a few times, then the good copy is used. With no good copy, a file that couldn't be opened
 * or read is left alone and nothing is written until SkyWing starts again, so one bad read never wipes the other
 * sources from the library. A file that was read but is damaged won't read better next time: it is kept as
 * `media-index.bin.damaged` and the library starts again from the next scan.
 */
internal class WindowsMediaIndex(private val testIndexPath: Path? = null) {
    private val indexPath: Path by lazy {
        testIndexPath ?: run {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
            Paths.get(base, "SkyWing", "library", "media-index.bin")
        }
    }
    private val copyPath: Path by lazy { indexPath.resolveSibling(indexPath.fileName.toString().substringBeforeLast('.') + ".bak") }

    /** Null until the index is first needed. */
    private var entries: List<WindowsMediaEntry>? = null
    /** False when the index or its good copy couldn't be opened and neither was read: then nothing is written. */
    private var writable = true

    /** What the library pages say about the index; null when it was read as saved (or there is none yet). */
    @Volatile var notice: String? = null
        private set

    @Synchronized
    fun load(): List<WindowsMediaEntry> = entries ?: read().also { entries = it }

    @Synchronized
    fun replaceSource(sourceId: String, sourceType: String, sourceName: String, files: List<FoundVideo>, keep: (String) -> String = { it }, preserve: List<WindowsMediaEntry> = emptyList()): Boolean {
        require(sourceType == "PC" || sourceType == "SMB")
        val seen = HashSet<String>()
        val fresh = files.mapNotNull { found ->
            // [keep] turns the path that was read into the one the library keeps (the drive's main address).
            val path = runCatching { keep(found.file.toPath().toAbsolutePath().normalize().toString()) }.getOrNull() ?: return@mapNotNull null
            if (!seen.add(path.lowercase(Locale.ROOT))) return@mapNotNull null
            WindowsMediaEntry(path, sourceId, sourceType, sourceName, found.size, found.modified)
        }
        val previous = load()
        val combined = fresh + preserve.filter { it.sourceId == sourceId && seen.add(it.path.lowercase(Locale.ROOT)) }
        val oldSource = previous.filter { it.sourceId == sourceId }
        if (oldSource.size == combined.size && oldSource.associateBy { it.path.lowercase(Locale.ROOT) } == combined.associateBy { it.path.lowercase(Locale.ROOT) }) return false
        save(previous.filterNot { it.sourceId == sourceId } + combined)
        return true
    }

    /** Drops entries matching [unwanted]; returns how many were dropped. */
    @Synchronized
    fun removeWhere(unwanted: (WindowsMediaEntry) -> Boolean): Int {
        val all = load()
        if (!writable) return 0
        val kept = all.filterNot(unwanted)
        if (kept.size != all.size) save(kept)
        return all.size - kept.size
    }

    /** Updates the library immediately after the underlying video file is renamed. */
    @Synchronized
    fun renamePath(old: String, new: String, file: File) {
        val current = load()
        require(current.none { it.path.equals(new, ignoreCase = true) }) { "A library entry already has that name" }
        val changed = current.map { entry ->
            if (entry.path.equals(old, ignoreCase = true))
                entry.copy(path = new, size = file.length(), modified = file.lastModified())
            else entry
        }
        if (changed != current) save(changed)
    }

    private sealed interface Read {
        data object Missing : Read
        /** The file couldn't be opened or read (another program holds it, a disk error): it may read next time. */
        data object Locked : Read
        /** The file was read but isn't a whole index: reading it again won't help. */
        data object Damaged : Read
        data class Entries(val entries: List<WindowsMediaEntry>) : Read
    }

    private fun read(): List<WindowsMediaEntry> {
        val main = readRetrying(indexPath)
        if (main is Read.Entries) return main.entries
        val copy = readRetrying(copyPath)
        if (copy is Read.Entries) return copy.entries.also { notice = RESTORED }
        val damaged = listOf(indexPath to main, copyPath to copy).filter { it.second == Read.Damaged }.map { it.first }
        if (main == Read.Locked || copy == Read.Locked || !damaged.all(::keepDamaged)) {
            writable = false
            notice = UNREADABLE
        } else if (damaged.isNotEmpty()) {
            notice = REBUILT
        }
        return emptyList()
    }

    /** Moves a damaged file out of the way, keeping it as it is; false when it can't be moved. */
    private fun keepDamaged(file: Path): Boolean = runCatching {
        Files.move(file, file.resolveSibling(file.fileName.toString() + ".damaged"), StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess

    /** A file another program holds for a moment (an antivirus scan) gets [READ_ATTEMPTS] tries. */
    private fun readRetrying(file: Path): Read {
        repeat(READ_ATTEMPTS - 1) {
            val result = read(file)
            if (result != Read.Locked) return result
            Thread.sleep(READ_RETRY_MS)
        }
        return read(file)
    }

    private fun read(file: Path): Read {
        if (!Files.exists(file)) return Read.Missing
        return try {
            DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return Read.Damaged
                val count = input.readInt()
                if (count !in 0..MAX_ENTRIES) return Read.Damaged
                val entries = buildList(count) {
                    repeat(count) {
                        val path = input.readUTF()
                        val sourceId = input.readUTF()
                        val sourceType = input.readUTF()
                        val sourceName = input.readUTF()
                        val size = input.readLong()
                        val modified = input.readLong()
                        if (path.isNotBlank() && sourceId.isNotBlank() && sourceType in setOf("PC", "SMB")) {
                            add(WindowsMediaEntry(path, sourceId, sourceType, sourceName, size, modified))
                        }
                    }
                }
                // Anything after the last entry means the file isn't what was written.
                if (input.read() != -1) Read.Damaged else Read.Entries(entries)
            }
        } catch (_: EOFException) {
            Read.Damaged // shorter than it says: half-written
        } catch (_: UTFDataFormatException) {
            Read.Damaged
        } catch (_: Exception) {
            Read.Locked
        }
    }

    /** Writes the index, then its good copy, and only then keeps [next] as the library. */
    private fun save(next: List<WindowsMediaEntry>) {
        check(writable) { UNREADABLE }
        require(next.size <= MAX_ENTRIES) { "The library index is too large to save" }
        write(indexPath, next)
        write(copyPath, next)
        entries = next
    }

    private fun write(target: Path, entries: List<WindowsMediaEntry>) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(entries.size)
            entries.forEach { entry ->
                output.writeUTF(entry.path)
                output.writeUTF(entry.sourceId)
                output.writeUTF(entry.sourceType)
                output.writeUTF(entry.sourceName)
                output.writeLong(entry.size)
                output.writeLong(entry.modified)
            }
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val MAGIC = 0x53574C49
        private const val VERSION = 1
        private const val MAX_ENTRIES = 100_000
        private const val READ_ATTEMPTS = 3
        private const val READ_RETRY_MS = 200L
        const val RESTORED = "Library couldn't be read. Restored the last good copy."
        const val UNREADABLE = "Library couldn't be opened (another program may be using it), so nothing is saved until it can be. Restart SkyWing to try again."
        const val REBUILT = "Library couldn't be read and had no good copy, so it's being made again from your PC folders; add network drives to it again. The old file is kept as media-index.bin.damaged."
    }
}

internal object WindowsVirtualNames {
    private val leadingTag = Regex("^\\s*(\\[[^\\]]*\\]|\\([^)]*\\))")
    private val spaces = Regex("\\s+")
    private val trailingNumber = Regex("\\s+(?:part\\s*)?\\d+$", RegexOption.IGNORE_CASE)

    fun identity(name: String): String = name.trim().replace(spaces, " ").lowercase(Locale.ROOT)

    fun display(name: String): String = name.trim().replace(spaces, " ").split(' ')
        .joinToString(" ") { it.lowercase(Locale.ROOT).replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }

    fun of(fileName: String, rules: GroupingSwitches = GroupingSwitches()): List<String> {
        var text = fileName.substringBeforeLast('.', fileName).trim()
        while (true) {
            val tag = leadingTag.find(text) ?: break
            text = text.substring(tag.range.last + 1).trimStart()
        }
        val hyphen = text.indexOf('-')
        val cutAtHyphen = rules.stopAtHyphen && hyphen >= 0
        val head = if (cutAtHyphen) text.substring(0, hyphen) else text
        val splitAtComma = rules.splitAtCommas && ',' in head
        var names = (if (splitAtComma) head.split(',') else listOf(head))
            .map(::clean).filter(String::isNotBlank)
        if (rules.firstTwoWords && !cutAtHyphen && !splitAtComma) {
            names = names.map { it.split(' ').filter(String::isNotBlank).take(2).joinToString(" ") }
        }
        val seen = HashSet<String>()
        return names.map(::display).filter { seen.add(identity(it)) }.ifEmpty { listOf("#") }
    }

    private fun clean(part: String): String = part.trim().trimStart { !it.isLetterOrDigit() }
        .replace(spaces, " ")
        .replace(trailingNumber, "")
        .trim(' ', '-', '_', '.', ',', ';', ':')
}

internal data class WindowsCustomFolder(val name: String, val paths: List<String>)

/**
 * Custom folders as SkyWing kept them in the registry, one value per video (B20). They moved to folders.bin
 * ([FolderOrganizerStore]) earlier; this is only read for that move, and then removed.
 */
internal class WindowsCustomFolderStore(
    private val node: () -> Preferences? = { registryNode(WindowsCustomFolderStore::class.java, "custom-library-folders") },
) {
    @Synchronized
    fun load(): List<WindowsCustomFolder> {
        val preferences = node() ?: return emptyList()
        val count = preferences.getInt("count", 0).coerceIn(0, MAX_FOLDERS)
        return (0 until count).mapNotNull { index ->
            val name = preferences.get("name." + index, null) ?: return@mapNotNull null
            val members = preferences.getInt("members." + index, 0).coerceIn(0, MAX_MEMBERS)
            val paths = (0 until members).mapNotNull { preferences.get("path." + index + "." + it, null) }
            name.takeIf { it.isNotBlank() }?.let { WindowsCustomFolder(it, paths) }
        }
    }

    /** Removes the registry copy; call only once folders.bin holds the folders. */
    @Synchronized
    fun clear() {
        node()?.removeMoved()
    }

    companion object {
        private const val MAX_FOLDERS = 100
        private const val MAX_MEMBERS = 5000
    }
}

internal data class WindowsWatchRecord(val path: String, val position: Double, val duration: Double, val updated: Long)

internal class WindowsPlaybackHistoryStore(
    path: Path = libraryFile("history.json"),
    /** Where SkyWing kept the history before (B20): read once into the file, then removed. */
    private val legacy: () -> Preferences? = { registryNode(WindowsPlaybackHistoryStore::class.java, "playback-history") },
) {
    private val file = JsonFile(path)
    /** Shared by every store on this file, so two can't move the registry history at once. */
    private val lock = file.lock

    /** The videos to show in Continue watching, newest first. */
    fun load(): List<WindowsWatchRecord> = synchronized(lock) {
        (all() ?: emptyList())
            .filter { it.path.isNotBlank() && it.position > 0 && it.duration > 0 && it.position < it.duration - 30 }
            .sortedByDescending { it.updated }
    }

    fun saveProgress(file: File, position: Double, duration: Double): Unit = synchronized(lock) {
        if (!position.isFinite() || !duration.isFinite() || duration <= 0) return
        // Nothing is written over a history that can't be read now.
        if (all() == null) return
        // Kept under the drive's main address, so Continue watching finds it whichever address played it.
        val path = ShareHosts.canonical(file.absolutePath)
        val current = load().filterNot { it.path.equals(path, ignoreCase = true) }
        val next = if (position < duration - 30 && position > 5) {
            listOf(WindowsWatchRecord(path, position, duration, System.currentTimeMillis())) + current
        } else current
        write(next.take(MAX_RECORDS))
    }

    fun renamePath(old: String, new: String): Unit = synchronized(lock) {
        if (all() == null) return
        val records = load()
        if (records.any { it.path.equals(old, ignoreCase = true) })
            write(records.map { if (it.path.equals(old, ignoreCase = true)) it.copy(path = new) else it })
    }

    /** Every saved record, or null when the file can't be read now. */
    private fun all(): List<WindowsWatchRecord>? = when (val read = file.read()) {
        is JsonFile.Read.Found -> recordsOf(read.root)
        JsonFile.Read.Missing -> moveFromRegistry()
        JsonFile.Read.Locked -> null
    }

    /** Copies every record the registry holds into the file, and removes the registry values once it reads back. */
    private fun moveFromRegistry(): List<WindowsWatchRecord> {
        val node = legacy() ?: return emptyList()
        val count = node.getInt("count", 0).coerceIn(0, MAX_RECORDS)
        val records = (0 until count).mapNotNull { index ->
            val path = node.get("path.$index", null)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WindowsWatchRecord(path, node.getDouble("position.$index", 0.0), node.getDouble("duration.$index", 0.0), node.getLong("updated.$index", 0L))
                .takeIf { it.position.isFinite() && it.duration.isFinite() }
        }
        write(records)
        if ((file.read() as? JsonFile.Read.Found)?.let { recordsOf(it.root) } == records) node.removeMoved()
        return records
    }

    private fun recordsOf(root: JsonObject): List<WindowsWatchRecord> = runCatching {
        root.getAsJsonArray("records").mapNotNull { element ->
            runCatching {
                val record = element.asJsonObject
                WindowsWatchRecord(record.get("path").asString, record.get("position").asDouble, record.get("duration").asDouble, record.get("updated").asLong)
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun write(records: List<WindowsWatchRecord>) {
        val list = JsonArray()
        records.forEach { record ->
            list.add(JsonObject().apply {
                addProperty("path", record.path)
                addProperty("position", record.position)
                addProperty("duration", record.duration)
                addProperty("updated", record.updated)
            })
        }
        file.write(JsonObject().apply {
            addProperty("version", 1)
            add("records", list)
        })
    }

    companion object {
        private const val MAX_RECORDS = 100
    }
}

/**
 * When the place of the playing video is written to the history (B20): at most every [everyMillis] while it plays,
 * and at the first check after a pause or a seek, but only when it moved by more than [minimumMoveSeconds] since the
 * last write. Stopping, closing and opening another video write it straight away, without asking this.
 */
internal class HistorySaves(
    startedAt: Long,
    private val everyMillis: Long = 30_000,
    private val minimumMoveSeconds: Double = 5.0,
    private val jumpSeconds: Double = 10.0,
) {
    private var savedAt = startedAt
    private var savedPosition: Double? = null
    private var lastAt: Long? = null
    private var lastPosition = 0.0
    private var wasPaused = false

    fun due(now: Long, position: Double, paused: Boolean): Boolean {
        val pausedNow = paused && !wasPaused
        val previousAt = lastAt
        // Where playing on from the last check would be: a place far from it is a seek.
        val jumped = previousAt != null &&
            abs(position - (lastPosition + if (paused) 0.0 else (now - previousAt) / 1000.0)) > jumpSeconds
        wasPaused = paused
        lastAt = now
        lastPosition = position
        val moved = savedPosition?.let { abs(position - it) > minimumMoveSeconds } ?: true
        return moved && (pausedNow || jumped || now - savedAt >= everyMillis)
    }

    fun saved(now: Long, position: Double) {
        savedAt = now
        savedPosition = position
    }
}

