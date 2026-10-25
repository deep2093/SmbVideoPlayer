package com.skywing.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.grid.LazyGridState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the screens read: the saved library index. */
internal class LibraryModel(val store: WindowsMediaIndex) {
    var all by mutableStateOf(emptyList<WindowsMediaEntry>())
    var loading by mutableStateOf(true)
    var revision by mutableStateOf(0)

    /** Call after sources were scanned, so every screen reloads. */
    fun changed() {
        revision++
    }

    suspend fun reload() {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            // Videos on a network drive added under This PC (a mapped drive) are listed under Network only.
            store.removeWhere { it.sourceType == "PC" && NetworkPaths.isNetwork(it.path) }
            store.load()
        }
        val distinct = withContext(Dispatchers.Default) {
            loaded.distinctBy { it.path.lowercase() }.sortedBy { it.file.name.lowercase() }
        }
        all = distinct
        loading = false
    }
}

/** Which videos are ticked. Selecting starts with a long press (or the Select button) and ends when cleared. */
internal class SelectionState {
    var active by mutableStateOf(false)
    var paths by mutableStateOf(emptySet<String>())

    fun toggle(path: String) {
        paths = if (path in paths) paths - path else paths + path
        active = true
    }

    fun begin(path: String) {
        paths = paths + path
        active = true
    }

    fun clear() {
        paths = emptySet()
        active = false
    }

    /** Ticks exactly [chosen] (a drag box or a Shift range); ticking nothing ends selecting. */
    fun set(chosen: Set<String>) {
        paths = chosen
        active = chosen.isNotEmpty()
    }
}

/** Where the user is inside the Folders page, kept while they visit other pages or play a video. */
internal class HubState {
    /** The shown tab: 0 Custom, 1 Virtual. */
    var tab by mutableStateOf(0)
    var query by mutableStateOf("")

    // Custom folders: an opened own folder, a name group inside it, a drive folder opened from it, a self-filling folder.
    var ownId by mutableStateOf<String?>(null)
    var ownGroup by mutableStateOf<String?>(null)
    var drivePath by mutableStateOf<String?>(null)
    var matchId by mutableStateOf<String?>(null)

    // Virtual folders: the opened folder and the generated subfolder open inside it.
    var setId by mutableStateOf<String?>(null)
    var setGroup by mutableStateOf<String?>(null)

    /** Folder cards ticked on the page named [folderPage]; empty when not selecting folders. */
    var folderPage by mutableStateOf<String?>(null)
    var folderKeys by mutableStateOf(emptySet<String>())

    // The Videos page keeps its own search, filter and order.
    var videoQuery by mutableStateOf("")
    var videoSource by mutableStateOf(0)
    var videoSort by mutableStateOf(0)

    private val states = HashMap<String, LazyGridState>()
    private val results = HashMap<String, Any?>()

    /** One scroll position per list, so coming back from a video lands where the user was. */
    fun grid(key: String): LazyGridState = states.getOrPut(key) { LazyGridState() }

    /**
     * The last list worked out for a page. A page shown again starts with it instead of an empty list, which would
     * move its grid back to the top.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> lastResult(name: String): T? = synchronized(results) { results[name] as T? }

    fun keepResult(name: String, value: Any?) {
        synchronized(results) { results[name] = value }
    }

    fun ticked(page: String, key: String): Boolean? = if (folderPage == page) key in folderKeys else null

    fun toggleFolder(page: String, key: String) {
        val keys = if (folderPage == page) folderKeys else emptySet()
        val next = if (key in keys) keys - key else keys + key
        folderPage = if (next.isEmpty()) null else page
        folderKeys = next
    }

    fun endFolderSelection() {
        folderPage = null
        folderKeys = emptySet()
    }
}

internal fun watchedFraction(history: Map<String, WindowsWatchRecord>, entry: WindowsMediaEntry): Float? {
    val record = history[entry.path.lowercase()] ?: return null
    if (record.duration <= 0) return null
    return (record.position / record.duration).toFloat().coerceIn(0f, 1f)
}

internal fun File.isUnder(root: File): Boolean = runCatching {
    toPath().toAbsolutePath().normalize().startsWith(root.toPath().toAbsolutePath().normalize())
}.getOrDefault(false)
