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
    /** [all] by lower-case path. Built with it, off the screen thread, so no page builds it while it draws (B9). */
    var byPath by mutableStateOf(emptyMap<String, WindowsMediaEntry>())
        private set
    /** The root (`\\server\share`) of every network share in [all], once each. */
    var shareRoots by mutableStateOf(emptyList<String>())
        private set
    /** Shown on the start page (Folders) when the saved index could not be read as it was saved. */
    var notice by mutableStateOf<String?>(null)
        private set

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
        val (distinct, paths, shares) = withContext(Dispatchers.Default) {
            val distinct = loaded.distinctBy { it.path.lowercase() }.sortedBy { it.file.name.lowercase() }
            val shares = distinct.filter { it.sourceType == "SMB" }.mapNotNull { LibrarySync.shareRootOf(it.path) }.distinctBy { it.lowercase() }
            Triple(distinct, distinct.associateBy { it.path.lowercase() }, shares)
        }
        all = distinct
        byPath = paths
        shareRoots = shares
        notice = store.notice
        loading = false
        PerfMonitor.mark("library loaded", "videos" to distinct.size)
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

    // The actor page: how its videos are sorted and whether watched ones are hidden, the same on every actor.
    var actorSort by mutableStateOf(VideoSort.NEWEST)
    var hideWatched by mutableStateOf(false)

    /**
     * True on an actor's own page in the tab on screen: a Custom folder's name group on tab 0, a Virtual folder's name
     * subfolder on the others, and not a drive folder in it. The other tab's place does not count.
     */
    val onActorPage: Boolean get() = drivePath == null &&
        if (tab == 0) matchId == null && ownId != null && ownGroup != null else setId != null && setGroup != null

    /** A card to tick ([page] to key) once the next move has ended any selection: Select on an actor page ticks it on the grid. */
    var tickAfterMove: Pair<String, String>? = null

    /** The actor page's own actor was renamed (old identity to new): once the old one is gone, the page goes to the new one. */
    var renamedActor: Pair<String, String>? = null

    /**
     * The card to give the keyboard focus to (its tile key): the actor's card after going back from an actor page, or the
     * video's card after coming back from playing it. The grid that shows that card focuses it, then clears this.
     */
    var focusTile by mutableStateOf<String?>(null)

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

    /**
     * The same parent navigation as the Back arrows on Custom and Virtual folder pages, for the tab on screen only
     * (FoldersScreen's order: tab 0 shows a self-filling folder, else an own folder; the others a Virtual folder).
     */
    fun navigateBack(data: OrganizerData): Boolean {
        if (folderPage != null) { endFolderSelection(); return true }
        val moved = if (tab == 0) customBack(data) else virtualBack(data)
        if (moved) query = ""
        return moved
    }

    private fun customBack(data: OrganizerData): Boolean {
        if (matchId != null) { matchId = null; return true }
        val own = data.own.firstOrNull { it.id == ownId }
        when {
            ownId == null -> return false
            drivePath != null -> driveBack(own?.folders.orEmpty())
            ownGroup != null -> { focusTile = actorFocusKey(ownGroup!!); ownGroup = null }
            else -> ownId = own?.parentId
        }
        return true
    }

    private fun virtualBack(data: OrganizerData): Boolean {
        val set = data.sets.firstOrNull { it.id == setId }
        when {
            setId == null -> return false
            drivePath != null -> driveBack(set?.directFolders.orEmpty())
            setGroup != null -> { focusTile = actorFocusKey(setGroup!!); setGroup = null }
            else -> setId = set?.parentId
        }
        return true
    }

    /** A drive folder goes up one level, and closes at one of [roots] (the folders its page was opened from). */
    private fun driveBack(roots: List<String>) {
        val current = drivePath!!
        val parent = File(current).parent
        drivePath = if (parent == null || roots.any { it.equals(current, ignoreCase = true) }) null else parent
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
