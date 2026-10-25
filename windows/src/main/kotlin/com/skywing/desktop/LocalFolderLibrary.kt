package com.skywing.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class LocalFolderStore {
    private val preferences = Preferences.userNodeForPackage(LocalFolderStore::class.java).node("local-library-folders")

    @Synchronized
    fun load(): List<String> {
        val count = preferences.getInt("count", 0).coerceIn(0, MAX_FOLDERS)
        return (0 until count).mapNotNull { preferences.get("folder." + it, null) }
    }

    @Synchronized
    fun add(folder: String): List<String> {
        val path = Paths.get(folder).toAbsolutePath().normalize().toString()
        val folders = (load() + path).distinctBy { it.lowercase() }
        require(folders.size <= MAX_FOLDERS) { "You can add up to " + MAX_FOLDERS + " folders" }
        write(folders)
        return folders
    }

    @Synchronized
    fun remove(folder: String): List<String> {
        val folders = load().filterNot { it.equals(folder, ignoreCase = true) }
        write(folders)
        return folders
    }

    private fun write(folders: List<String>) {
        val oldCount = preferences.getInt("count", 0).coerceIn(0, MAX_FOLDERS)
        repeat(maxOf(oldCount, folders.size)) { preferences.remove("folder." + it) }
        folders.forEachIndexed { index, path -> preferences.put("folder." + index, path) }
        preferences.putInt("count", folders.size)
        preferences.flush()
    }

    companion object {
        private const val MAX_FOLDERS = 100
    }
}

internal data class LocalVideoScanResult(
    val files: List<File>,
    val unavailableFolders: Int,
    val truncated: Boolean,
)

internal object LocalVideoScanner {
    private val extensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "ts", "m2ts", "hevc", "flv", "mpg", "mpeg")

    suspend fun scan(folders: List<String>, limit: Int = 20_000): LocalVideoScanResult = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, File>()
        var unavailable = 0
        var truncated = false
        val context = currentCoroutineContext()
        for (folder in folders) {
            val root = runCatching { Paths.get(folder) }.getOrNull()
            if (root == null || !Files.isDirectory(root)) {
                unavailable++
                continue
            }
            try {
                Files.walk(root).use { paths ->
                    val iterator = paths.iterator()
                    while (iterator.hasNext()) {
                        context.ensureActive()
                        val path = iterator.next()
                        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) continue
                        if (path.fileName.toString().substringAfterLast('.', "").lowercase() !in extensions) continue
                        files.putIfAbsent(path.toAbsolutePath().normalize().toString().lowercase(), path.toFile())
                        if (files.size >= limit) {
                            truncated = true
                            break
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                unavailable++
            } catch (_: SecurityException) {
                unavailable++
            }
            if (truncated) break
        }
        LocalVideoScanResult(
            files.values.sortedBy { it.name.lowercase() },
            unavailable,
            truncated,
        )
    }
}
