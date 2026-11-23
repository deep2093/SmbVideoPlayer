package com.skywing.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.prefs.Preferences
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

    private interface Mpr : com.sun.jna.Library {
        fun WNetGetConnectionW(localName: com.sun.jna.WString, remoteName: CharArray, length: com.sun.jna.ptr.IntByReference): Int
    }

    private val kernel: Kernel32? by lazy { runCatching { com.sun.jna.Native.load("kernel32", Kernel32::class.java) }.getOrNull() }
    private val mpr: Mpr? by lazy { runCatching { com.sun.jna.Native.load("mpr", Mpr::class.java) }.getOrNull() }
    private const val DRIVE_REMOTE = 4
    private const val NO_ERROR = 0
    private const val ERROR_CONNECTION_UNAVAIL = 1201

    private val drives = DriveLetters(
        isRemote = { letter -> kernel?.GetDriveTypeW(com.sun.jna.WString("$letter:\\")) == DRIVE_REMOTE },
        shareOf = ::connectionOf,
    )

    fun isNetwork(path: String): Boolean = drives.isNetwork(path)

    /** "network share", "local file" or "none", for reports. */
    fun kindOf(path: String?): String = drives.kindOf(path)

    /** [path] on a mapped drive letter, under the share's own address (`\\server\share\…`); null for any other path. */
    fun uncOf(path: String): String? = drives.uncOf(path)

    /** The share a drive letter is mapped to. Windows reads it from its own list; the share itself isn't contacted. */
    private fun connectionOf(letter: Char): String? {
        val api = mpr ?: return null
        val name = CharArray(1024)
        val result = runCatching { api.WNetGetConnectionW(com.sun.jna.WString("$letter:"), name, com.sun.jna.ptr.IntByReference(name.size)) }
            .getOrNull()
        // A remembered mapping that isn't connected now still names its share.
        if (result != NO_ERROR && result != ERROR_CONNECTION_UNAVAIL) return null
        return com.sun.jna.Native.toString(name).takeIf { it.startsWith("\\\\") }
    }
}

/**
 * What each drive letter is: on a network share or not, and which share a mapped letter shows (B29). Windows is asked
 * again after [keepMillis], so a letter mapped, disconnected or freed while SkyWing runs is seen within seconds.
 */
internal class DriveLetters(
    private val isRemote: (Char) -> Boolean,
    private val shareOf: (Char) -> String?,
    private val keepMillis: Long = 5_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Known(val network: Boolean, val share: String?, val at: Long)

    private val known = java.util.concurrent.ConcurrentHashMap<Char, Known>()

    fun isNetwork(path: String): Boolean {
        val p = path.trim()
        if (p.startsWith("\\\\") || p.startsWith("//")) return true
        return letterOf(p)?.let { of(it).network } ?: false
    }

    fun kindOf(path: String?): String = when {
        path == null -> "none"
        isNetwork(path) -> "network share"
        else -> "local file"
    }

    fun uncOf(path: String): String? {
        val p = path.trim()
        val share = letterOf(p)?.let { of(it).share }?.trimEnd('\\') ?: return null
        val rest = p.substring(2).replace('/', '\\').trimStart('\\')
        return if (rest.isEmpty()) share else share + "\\" + rest
    }

    private fun letterOf(p: String): Char? = if (p.length >= 2 && p[1] == ':' && p[0].isLetter()) p[0].uppercaseChar() else null

    private fun of(letter: Char): Known {
        val now = clock()
        known[letter]?.takeIf { now - it.at < keepMillis }?.let { return it }
        // A remembered mapping that isn't connected may not be a network drive to Windows, but its videos are on the share.
        val share = shareOf(letter)
        return Known(share != null || isRemote(letter), share, now).also { known[letter] = it }
    }
}

internal class LocalFolderStore(
    path: Path = libraryFile("local-folders.json"),
    /** Where SkyWing kept these folders before (B20): read once into the file, then removed. */
    private val legacy: () -> Preferences? = { registryNode(LocalFolderStore::class.java, "local-library-folders") },
) {
    private val file = JsonFile(path)

    @Synchronized
    fun load(): List<String> = (saved() ?: emptyList()).filterNot(NetworkPaths::isNetwork)

    @Synchronized
    fun add(folder: String): List<String> {
        val path = Paths.get(folder).toAbsolutePath().normalize().toString()
        require(!NetworkPaths.isNetwork(path)) { "That folder is on a network drive. Add the share under Network shares instead, so its videos are listed once." }
        checkReadable()
        val folders = (load() + path).distinctBy { it.lowercase() }
        require(folders.size <= MAX_FOLDERS) { "You can add up to " + MAX_FOLDERS + " folders" }
        write(folders)
        return folders
    }

    @Synchronized
    fun remove(folder: String): List<String> {
        checkReadable()
        val folders = load().filterNot { it.equals(folder, ignoreCase = true) }
        write(folders)
        return folders
    }

    /** Nothing is written over a folder list that can't be read now. */
    private fun checkReadable() {
        check(saved() != null) { "Your folder list can't be read right now (another program may be using it). Try again in a moment." }
    }

    private fun saved(): List<String>? = when (val read = file.read()) {
        is JsonFile.Read.Found -> foldersOf(read.root) ?: emptyList()
        JsonFile.Read.Missing -> moveFromRegistry()
        JsonFile.Read.Locked -> null
    }

    /** Copies every folder the registry holds into the file, and removes the registry values once it reads back. */
    private fun moveFromRegistry(): List<String> {
        val node = legacy() ?: return emptyList()
        val count = node.getInt("count", 0).coerceIn(0, MAX_FOLDERS)
        val folders = (0 until count).mapNotNull { node.get("folder.$it", null) }
        write(folders)
        if ((file.read() as? JsonFile.Read.Found)?.let { foldersOf(it.root) } == folders) node.removeMoved()
        return folders
    }

    private fun foldersOf(root: JsonObject): List<String>? = runCatching { root.getAsJsonArray("folders").map { it.asString } }.getOrNull()

    private fun write(folders: List<String>) {
        val list = JsonArray()
        folders.forEach(list::add)
        file.write(JsonObject().apply {
            addProperty("version", 1)
            add("folders", list)
        })
    }

    companion object {
        private const val MAX_FOLDERS = 100
    }
}

internal data class LocalVideoScanResult(
    val files: List<FoundVideo>,
    val unavailableFolders: Int,
    val truncated: Boolean,
    val unavailableRoots: List<String> = emptyList(),
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
    suspend fun scan(folders: List<String>, limit: Int = 20_000): LocalVideoScanResult = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, FoundVideo>()
        var unavailable = 0
        val unavailableRoots = ArrayList<String>()
        var truncated = false
        val context = currentCoroutineContext()
        for (folder in folders) {
            val root = runCatching { Paths.get(folder) }.getOrNull()?.takeUnless { NetworkPaths.isNetwork(folder) }
            if (root == null || !Files.isDirectory(root)) {
                unavailable++
                unavailableRoots += folder
                continue
            }
            try {
                truncated = VideoFiles.walk(root, files, limit) { context.ensureActive() }
            } catch (_: java.io.IOException) {
                unavailable++
                unavailableRoots += folder
            } catch (_: SecurityException) {
                unavailable++
                unavailableRoots += folder
            }
            if (truncated) break
        }
        LocalVideoScanResult(
            files.values.sortedBy { it.file.name.lowercase() },
            unavailable,
            truncated,
            unavailableRoots,
        )
    }
}
