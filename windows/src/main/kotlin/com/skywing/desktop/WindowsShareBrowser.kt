package com.skywing.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Browses a Windows share through the current Windows sign-in. No credentials are stored. */
internal class WindowsShareBrowser {
    data class Entry(val file: File, val directory: Boolean)
    data class Listing(val entries: List<Entry>, val truncated: Boolean)

    fun shareRoot(address: String): Path {
        val clean = address.trim().trimEnd('\\', '/')
        require(clean.startsWith("\\\\")) { "Enter a network path such as \\\\server\\share" }
        val components = clean.removePrefix("\\\\").split('\\')
        require(components.size >= 2 && components[0].isNotBlank() && components[1].isNotBlank()) {
            "Enter both the server and share name"
        }
        require(components.none { it == "." || it == ".." || it.isBlank() }) { "Invalid network path" }
        return Paths.get(clean).normalize()
    }

    suspend fun list(root: Path, directory: Path): Listing = withContext(Dispatchers.IO) {
        val safeDirectory = directory.normalize()
        require(safeDirectory.startsWith(root)) { "Folder is outside the selected share" }
        require(Files.isDirectory(safeDirectory)) { "Network folder is unavailable" }
        val entries = ArrayList<Entry>()
        var truncated = false
        Files.newDirectoryStream(safeDirectory).use { stream ->
            for (path in stream) {
                val isDirectory = Files.isDirectory(path)
                if (!isDirectory && path.toFile().extension.lowercase() !in VIDEO_EXTENSIONS) continue
                if (entries.size == 500) { truncated = true; break }
                entries += Entry(path.toFile(), isDirectory)
            }
        }
        Listing(entries.sortedWith(compareBy<Entry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }), truncated)
    }

    data class ScanResult(val files: List<File>, val truncated: Boolean)

    suspend fun scan(root: Path, directory: Path = root, limit: Int = 20_000): ScanResult = withContext(Dispatchers.IO) {
        val safeDirectory = directory.normalize()
        require(safeDirectory.startsWith(root)) { "Folder is outside the selected share" }
        require(Files.isDirectory(safeDirectory)) { "Network folder is unavailable" }
        val files = LinkedHashMap<String, File>()
        var truncated = false
        Files.walk(safeDirectory).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) continue
                if (path.toFile().extension.lowercase() !in VIDEO_EXTENSIONS) continue
                files.putIfAbsent(path.toAbsolutePath().normalize().toString().lowercase(), path.toFile())
                if (files.size >= limit) { truncated = true; break }
            }
        }
        ScanResult(files.values.sortedBy { it.name.lowercase() }, truncated)
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "ts", "m2ts", "hevc", "flv", "mpg", "mpeg")
    }
}
