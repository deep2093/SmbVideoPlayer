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

/**
 * Tells folders on this PC from network drives. A network share reached through a drive letter (a mapped drive such
 * as Y:) or a \server address belongs under Network shares, so This PC never lists those videos a second time.
 */
internal object NetworkPaths {
    private interface Kernel32 : com.sun.jna.Library {
        fun GetDriveTypeW(root: com.sun.jna.WString): Int
    }

    private val kernel: Kernel32? by lazy { runCatching { com.sun.jna.Native.load("kernel32", Kernel32::class.java) }.getOrNull() }
    private val drives = java.util.concurrent.ConcurrentHashMap<Char, Boolean>()
    private const val DRIVE_REMOTE = 4

    fun isNetwork(path: String): Boolean {
        val p = path.trim()
        if (p.startsWith("\\\\") || p.startsWith("//")) return true
        if (p.length < 2 || p[1] != ':' || !p[0].isLetter()) return false
        val letter = p[0].uppercaseChar()
        return drives.getOrPut(letter) { kernel?.GetDriveTypeW(com.sun.jna.WString("$letter:\\")) == DRIVE_REMOTE }
    }
}

internal class LocalFolderStore {
    private val preferences = Preferences.userNodeForPackage(LocalFolderStore::class.java).node("local-library-folders")

    @Synchronized
    fun load(): List<String> {
        val count = preferences.getInt("count", 0).coerceIn(0, MAX_FOLDERS)
        return (0 until count).mapNotNull { preferences.get("folder." + it, null) }.filterNot(NetworkPaths::isNetwork)
    }

    @Synchronized
    fun add(folder: String): List<String> {
        val path = Paths.get(folder).toAbsolutePath().normalize().toString()
        require(!NetworkPaths.isNetwork(path)) { "That folder is on a network drive. Add the share under Network shares instead, so its videos are listed once." }
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

internal data class LocalOriginalFolderContents(val folders: List<File>, val videos: List<File>)

internal fun localOriginalFolderContents(
    selectedRoots: List<String>,
    currentFolder: File?,
    videos: List<File>,
): LocalOriginalFolderContents {
    val currentPath = currentFolder?.toPath()?.toAbsolutePath()?.normalize()
    if (currentPath == null) {
        val roots = selectedRoots.mapNotNull { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() }
            .filter { root -> videos.any { it.toPath().toAbsolutePath().normalize().startsWith(root) } }
            .distinctBy { it.toString().lowercase() }
            .map { it.toFile() }
            .sortedBy { it.name.lowercase() }
        return LocalOriginalFolderContents(roots, emptyList())
    }
    val childFolders = linkedMapOf<String, Path>()
    val directVideos = videos.filter { video ->
        video.toPath().toAbsolutePath().normalize().parent == currentPath
    }
    videos.forEach { video ->
        val parent = video.toPath().toAbsolutePath().normalize().parent ?: return@forEach
        if (parent == currentPath || !parent.startsWith(currentPath)) return@forEach
        val relative = currentPath.relativize(parent)
        if (relative.nameCount > 0) {
            val child = currentPath.resolve(relative.getName(0))
            childFolders.putIfAbsent(child.toString().lowercase(), child)
        }
    }
    return LocalOriginalFolderContents(
        childFolders.values.map { it.toFile() }.sortedBy { it.name.lowercase() },
        directVideos.sortedBy { it.name.lowercase() },
    )
}

internal object LocalVideoScanner {
    private val extensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "ts", "m2ts", "hevc", "flv", "mpg", "mpeg")

    suspend fun scan(folders: List<String>, limit: Int = 20_000): LocalVideoScanResult = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, File>()
        var unavailable = 0
        var truncated = false
        val context = currentCoroutineContext()
        for (folder in folders) {
            val root = runCatching { Paths.get(folder) }.getOrNull()?.takeUnless { NetworkPaths.isNetwork(folder) }
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
