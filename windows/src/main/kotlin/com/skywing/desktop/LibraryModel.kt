package com.skywing.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.grid.LazyGridState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class VirtualFolder(val key: String, val title: String, val entries: List<WindowsMediaEntry>)

/** Everything the screens read: the saved library index, virtual folders worked out from file names. */
internal class LibraryModel(val store: WindowsMediaIndex) {
    var all by mutableStateOf(emptyList<WindowsMediaEntry>())
    var virtual by mutableStateOf(emptyList<VirtualFolder>())
    var loading by mutableStateOf(true)
    var revision by mutableStateOf(0)

    /** Call after sources were scanned, so every screen reloads. */
    fun changed() {
        revision++
    }

    suspend fun reload() {
        loading = true
        val loaded = withContext(Dispatchers.IO) { store.load() }
        val distinct = withContext(Dispatchers.Default) {
            loaded.distinctBy { it.path.lowercase() }.sortedBy { it.file.name.lowercase() }
        }
        val groups = withContext(Dispatchers.Default) {
            val grouped = linkedMapOf<String, Pair<String, LinkedHashMap<String, WindowsMediaEntry>>>()
            distinct.forEach { entry ->
                WindowsVirtualNames.of(entry.file.name).forEach { title ->
                    val key = WindowsVirtualNames.identity(title)
                    val current = grouped.getOrPut(key) { title to linkedMapOf() }
                    current.second.putIfAbsent(entry.path.lowercase(), entry)
                }
            }
            grouped.map { (key, value) -> VirtualFolder(key, value.first, value.second.values.toList()) }
                .sortedBy { it.title.lowercase() }
        }
        all = distinct
        virtual = groups
        loading = false
    }
}

/** The user's own folders, shared by every screen so a change shows up everywhere at once. */
internal class CustomFolders(private val store: WindowsCustomFolderStore) {
    var folders by mutableStateOf(runCatching { store.load() }.getOrDefault(emptyList()))
        private set

    /** Returns a message when something went wrong, otherwise null. */
    private fun attempt(block: () -> List<WindowsCustomFolder>): String? = try {
        folders = block()
        null
    } catch (error: Exception) {
        error.message ?: "Could not change the folder"
    }

    fun create(name: String, paths: Collection<String>): String? = attempt { store.save(name, paths.toList()) }

    fun addTo(name: String, paths: Collection<String>): String? = attempt {
        val existing = folders.firstOrNull { it.name == name }?.paths.orEmpty()
        store.save(name, existing + paths)
    }

    fun removeFrom(name: String, paths: Collection<String>): String? = attempt {
        val remaining = folders.firstOrNull { it.name == name }?.paths.orEmpty().filterNot { path ->
            paths.any { it.equals(path, ignoreCase = true) }
        }
        require(remaining.isNotEmpty()) { "A folder keeps at least one video. Delete the folder instead." }
        store.save(name, remaining)
    }

    fun delete(name: String): String? = attempt { store.delete(name) }

    fun rename(old: String, new: String): String? = attempt {
        val paths = folders.firstOrNull { it.name == old }?.paths.orEmpty()
        require(paths.isNotEmpty()) { "That folder no longer exists" }
        store.delete(old)
        store.save(new, paths)
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
}

/** Where the user is inside the Folders page, kept while they visit other pages or play a video. */
internal class HubState {
    var tab by mutableStateOf(0)
    var custom by mutableStateOf<String?>(null)
    var virtual by mutableStateOf<String?>(null)
    var original by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    // The Videos page keeps its own search, filter and order.
    var videoQuery by mutableStateOf("")
    var videoSource by mutableStateOf(0)
    var videoSort by mutableStateOf(0)

    private val states = HashMap<String, LazyGridState>()

    /** One scroll position per list, so coming back from a video lands where the user was. */
    fun grid(key: String): LazyGridState = states.getOrPut(key) { LazyGridState() }
}

internal fun watchedFraction(history: Map<String, WindowsWatchRecord>, entry: WindowsMediaEntry): Float? {
    val record = history[entry.path.lowercase()] ?: return null
    if (record.duration <= 0) return null
    return (record.position / record.duration).toFloat().coerceIn(0f, 1f)
}

internal fun File.isUnder(root: File): Boolean = runCatching {
    toPath().toAbsolutePath().normalize().startsWith(root.toPath().toAbsolutePath().normalize())
}.getOrDefault(false)
