package com.skywing.desktop

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.prefs.Preferences

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

internal class WindowsMediaIndex(private val testIndexPath: Path? = null) {
    private val indexPath: Path by lazy {
        testIndexPath ?: run {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
            Paths.get(base, "SkyWing", "library", "media-index.bin")
        }
    }

    @Synchronized
    fun load(): List<WindowsMediaEntry> {
        return try {
            if (!Files.isRegularFile(indexPath)) {
                emptyList()
            } else {
                DataInputStream(BufferedInputStream(Files.newInputStream(indexPath))).use { input ->
                    if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                        emptyList()
                    } else {
                        val count = input.readInt()
                        if (count !in 0..MAX_ENTRIES) {
                            emptyList()
                        } else {
                            buildList(count) {
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
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun replaceSource(sourceId: String, sourceType: String, sourceName: String, files: List<File>, keep: (String) -> String = { it }) {
        require(sourceType == "PC" || sourceType == "SMB")
        val seen = HashSet<String>()
        val fresh = files.mapNotNull { file ->
            // [keep] turns the path that was read into the one the library keeps (the drive's main address).
            val path = runCatching { keep(file.toPath().toAbsolutePath().normalize().toString()) }.getOrNull() ?: return@mapNotNull null
            if (!seen.add(path.lowercase(Locale.ROOT))) return@mapNotNull null
            WindowsMediaEntry(path, sourceId, sourceType, sourceName, file.length(), file.lastModified())
        }
        write(load().filterNot { it.sourceId == sourceId } + fresh)
    }

    private fun write(entries: List<WindowsMediaEntry>) {
        require(entries.size <= MAX_ENTRIES) { "The library index is too large to save" }
        val target = indexPath
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

internal class WindowsCustomFolderStore {
    private val preferences = Preferences.userNodeForPackage(WindowsCustomFolderStore::class.java).node("custom-library-folders")

    @Synchronized
    fun load(): List<WindowsCustomFolder> {
        val count = preferences.getInt("count", 0).coerceIn(0, MAX_FOLDERS)
        return (0 until count).mapNotNull { index ->
            val name = preferences.get("name." + index, null) ?: return@mapNotNull null
            val members = preferences.getInt("members." + index, 0).coerceIn(0, MAX_MEMBERS)
            val paths = (0 until members).mapNotNull { preferences.get("path." + index + "." + it, null) }
            name.takeIf { it.isNotBlank() }?.let { WindowsCustomFolder(it, paths) }
        }
    }

    @Synchronized
    fun save(name: String, paths: List<String>): List<WindowsCustomFolder> {
        require(name.isNotBlank()) { "Enter a custom folder name" }
        require(paths.isNotEmpty()) { "Select at least one video" }
        val current = load().filterNot { it.name.equals(name.trim(), ignoreCase = true) }
        val next = current + WindowsCustomFolder(name.trim(), paths.distinct().take(MAX_MEMBERS))
        require(next.size <= MAX_FOLDERS) { "You can create up to " + MAX_FOLDERS + " custom folders" }
        write(next)
        return next
    }

    @Synchronized
    fun delete(name: String): List<WindowsCustomFolder> {
        val next = load().filterNot { it.name == name }
        write(next)
        return next
    }

    private fun write(folders: List<WindowsCustomFolder>) {
        val oldCount = preferences.getInt("count", 0).coerceIn(0, MAX_FOLDERS)
        repeat(oldCount) { index ->
            val members = preferences.getInt("members." + index, 0).coerceIn(0, MAX_MEMBERS)
            repeat(members) { preferences.remove("path." + index + "." + it) }
            preferences.remove("name." + index)
            preferences.remove("members." + index)
        }
        folders.forEachIndexed { index, folder ->
            preferences.put("name." + index, folder.name)
            preferences.putInt("members." + index, folder.paths.size)
            folder.paths.forEachIndexed { member, path -> preferences.put("path." + index + "." + member, path) }
        }
        preferences.putInt("count", folders.size)
        preferences.flush()
    }

    companion object {
        private const val MAX_FOLDERS = 100
        private const val MAX_MEMBERS = 5000
    }
}

internal data class WindowsWatchRecord(val path: String, val position: Double, val duration: Double, val updated: Long)

internal class WindowsPlaybackHistoryStore {
    private val preferences = Preferences.userNodeForPackage(WindowsPlaybackHistoryStore::class.java).node("playback-history")

    @Synchronized
    fun load(): List<WindowsWatchRecord> {
        val count = preferences.getInt("count", 0).coerceIn(0, MAX_RECORDS)
        return (0 until count).mapNotNull { index ->
            val path = preferences.get("path." + index, null) ?: return@mapNotNull null
            val position = preferences.getDouble("position." + index, 0.0)
            val duration = preferences.getDouble("duration." + index, 0.0)
            val updated = preferences.getLong("updated." + index, 0L)
            if (path.isBlank() || position <= 0 || duration <= 0 || position >= duration - 30) null
            else WindowsWatchRecord(path, position, duration, updated)
        }.sortedByDescending { it.updated }
    }

    @Synchronized
    fun saveProgress(file: File, position: Double, duration: Double) {
        if (!position.isFinite() || !duration.isFinite() || duration <= 0) return
        // Kept under the drive's main address, so Continue watching finds it whichever address played it.
        val path = ShareHosts.canonical(file.absolutePath)
        val current = load().filterNot { it.path.equals(path, ignoreCase = true) }
        val next = if (position < duration - 30 && position > 5) {
            listOf(WindowsWatchRecord(path, position, duration, System.currentTimeMillis())) + current
        } else current
        write(next.take(MAX_RECORDS))
    }

    private fun write(records: List<WindowsWatchRecord>) {
        val oldCount = preferences.getInt("count", 0).coerceIn(0, MAX_RECORDS)
        repeat(oldCount) { index ->
            preferences.remove("path." + index)
            preferences.remove("position." + index)
            preferences.remove("duration." + index)
            preferences.remove("updated." + index)
        }
        records.forEachIndexed { index, record ->
            preferences.put("path." + index, record.path)
            preferences.putDouble("position." + index, record.position)
            preferences.putDouble("duration." + index, record.duration)
            preferences.putLong("updated." + index, record.updated)
        }
        preferences.putInt("count", records.size)
        preferences.flush()
    }

    companion object {
        private const val MAX_RECORDS = 100
    }
}

