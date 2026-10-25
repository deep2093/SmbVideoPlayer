package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skywing.desktop.artwork.ArtworkKeys
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The Folders page, laid out like the Android app: Custom, Virtual and Original folders as tabs (which can be
 * renamed, hidden and reordered), every folder a card with a picture from its first video, and every page scrolling
 * top to bottom with a scroll bar. Holding a card (or a right click) opens its options; Select ticks several.
 */

private fun shareRootOf(path: String): String? {
    if (!path.startsWith("\\\\")) return null
    val parts = path.removePrefix("\\\\").split('\\')
    return if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) "\\\\" + parts[0] + "\\" + parts[1] else null
}

private fun plural(count: Int, one: String, many: String = one + "s") = "$count ${if (count == 1) one else many}"

private fun leafOf(path: String) = path.trimEnd('\\').substringAfterLast('\\').ifBlank { path }

/**
 * Work that can take a moment on a big library runs off the screen thread. The last result is kept in [hub] under
 * [name], so a page shown again (after playing a video) starts with it and keeps its scroll position.
 */
@Composable
private fun <T> computed(hub: HubState, name: String, vararg keys: Any?, block: () -> T): T? =
    produceState(hub.lastResult<T>(name), *keys) {
        value = withContext(Dispatchers.Default) { block() }.also { hub.keepResult(name, it) }
    }.value

/* ---------- Dialogs opened from menus, shown once at the page root ---------- */

private data class ConfirmSpec(val title: String, val text: String, val label: String, val action: () -> Unit)
private data class NameSpec(val title: String, val initial: String, val hint: String, val label: String, val action: (String) -> Unit)
private data class PickerSpec(
    val title: String,
    val explanation: String,
    val withFiles: Boolean,
    val folders: List<String>,
    val files: List<String>,
    val subfolderChoice: Boolean,
    val onDone: (List<String>, List<String>, Boolean) -> Unit,
)

private class FolderDialogs {
    var filing by mutableStateOf<Pair<Boolean, List<String>>?>(null)
    var info by mutableStateOf<Pair<String, String>?>(null)
    var confirm by mutableStateOf<ConfirmSpec?>(null)
    var naming by mutableStateOf<NameSpec?>(null)
    var picker by mutableStateOf<PickerSpec?>(null)
    var cover by mutableStateOf<CoverTarget?>(null)
}

/** Videos shown by each Custom and Virtual folder, worked out together. */
private class Memberships(
    val own: Map<String, List<WindowsMediaEntry>>,
    val match: Map<String, List<WindowsMediaEntry>>,
    val sets: Map<String, List<WindowsMediaEntry>>,
)

/** What every Folders page needs. */
private class Folders(
    val library: LibraryModel,
    val organizer: Organizer,
    val hub: HubState,
    val history: Map<String, WindowsWatchRecord>,
    val onPlay: (File, List<File>) -> Unit,
    val roots: List<String>,
    val dialogs: FolderDialogs,
    val members: Memberships?,
    val artworkOn: Boolean,
) {
    val data: OrganizerData get() = organizer.data
    val prefs: FolderPrefs get() = organizer.data.prefs
    val query: String get() = hub.query.trim()

    fun matches(name: String) = query.isEmpty() || name.contains(query, ignoreCase = true)

    fun play(list: List<WindowsMediaEntry>) {
        list.firstOrNull()?.let { onPlay(it.file, list.map { e -> e.file }) }
    }

    /** Moves somewhere else on the Folders page; the search box starts empty there. */
    fun go(change: HubState.() -> Unit) {
        hub.change()
        hub.query = ""
    }

    fun byPaths(paths: Collection<String>): List<WindowsMediaEntry> {
        val wanted = paths.map { it.lowercase(Locale.ROOT) }.toHashSet()
        return library.all.filter { it.path.lowercase(Locale.ROOT) in wanted }
    }

    /** "Change cover" for a card, when artwork is on (Settings > Artwork). */
    fun coverAction(key: String, title: String, videos: List<WindowsMediaEntry>, video: WindowsMediaEntry? = null): MenuAction? =
        if (!artworkOn) null else MenuAction("Change cover", SkyIcon.Image) { dialogs.cover = CoverTarget(key, title, video, videos) }

    /** The options of a video card. */
    fun videoMenu(entry: WindowsMediaEntry, queue: List<WindowsMediaEntry>, page: String): List<MenuAction> = listOfNotNull(
        MenuAction("Play", SkyIcon.Play) { onPlay(entry.file, queue.map { it.file }) },
        MenuAction("Select", SkyIcon.Check) { hub.toggleFolder(page, "file:" + entry.path) },
        MenuAction("Add to folder…", SkyIcon.Folder) { dialogs.filing = false to listOf(entry.path) },
        MenuAction("Add to virtual folder…", SkyIcon.Grid) { dialogs.filing = true to listOf(entry.path) },
        coverAction(ArtworkKeys.video(entry.path), entry.file.nameWithoutExtension, listOf(entry), entry),
        MenuAction("Details", SkyIcon.Info) {
            dialogs.info = entry.file.name to listOf(entry.path, formatSize(entry.size), entry.sourceName).filter { it.isNotBlank() }.joinToString("\n")
        },
    )

    fun videoTiles(list: List<WindowsMediaEntry>, page: String): List<VideoTile> = list.map { entry ->
        VideoTile(
            "file:" + entry.path.lowercase(Locale.ROOT), entry, list, videoMenu(entry, list, page),
            hub.ticked(page, "file:" + entry.path), { hub.toggleFolder(page, "file:" + entry.path) },
        )
    }

    /** Selection actions that work on any ticked videos. */
    fun videoActions(videos: () -> List<WindowsMediaEntry>): List<MenuAction> = listOf(
        MenuAction("Play", SkyIcon.Play) { play(videos()); hub.endFolderSelection() },
        MenuAction("Add to folder", SkyIcon.Folder) { dialogs.filing = false to videos().map { it.path } },
        MenuAction("Add to virtual folder", SkyIcon.Grid) { dialogs.filing = true to videos().map { it.path } },
    )

    fun ownTile(folder: OwnFolder, page: String?): FolderTile {
        val videos = members?.own?.get(folder.id).orEmpty()
        return FolderTile(
            key = "own:" + folder.id,
            title = folder.name,
            detail = if (folder.isEmpty) "Empty · click to choose" else plural(videos.size, "video"),
            coverKey = ArtworkKeys.ownFolder(folder.id),
            coverVideos = videos,
            onOpen = { if (folder.isEmpty) chooseOwn(folder) else go { ownId = folder.id; ownGroup = null; drivePath = null } },
            menu = listOfNotNull(
                MenuAction("Open", SkyIcon.Folder) { go { ownId = folder.id; ownGroup = null; drivePath = null } },
                page?.let { MenuAction("Select", SkyIcon.Check) { hub.toggleFolder(it, "own:" + folder.id) } },
                MenuAction("Choose folders and files", SkyIcon.Edit) { chooseOwn(folder) },
                MenuAction("Play all", SkyIcon.Play, enabled = videos.isNotEmpty()) { play(videos) },
                coverAction(ArtworkKeys.ownFolder(folder.id), folder.name, videos),
                MenuAction("Rename", SkyIcon.Edit) {
                    dialogs.naming = NameSpec("Rename folder", folder.name, "Only the name in this app changes.", "Rename") { organizer.renameOwn(folder.id, it) }
                },
                MenuAction("Remove from Custom folders…", SkyIcon.Delete) {
                    dialogs.confirm = ConfirmSpec("Remove ${folder.name}?", "The folder is removed from Custom folders. Files on your disk are not touched.", "Remove") {
                        organizer.deleteOwn(folder.id)
                    }
                },
            ),
            selected = page?.let { hub.ticked(it, "own:" + folder.id) },
            onSelect = page?.let { { hub.toggleFolder(it, "own:" + folder.id) } },
        )
    }

    fun chooseOwn(folder: OwnFolder) {
        dialogs.picker = PickerSpec(
            "Choose what ${folder.name} shows",
            "Tick folders (everything inside them is included) and single videos. Untick to take them out. Files on your disk are not changed.",
            withFiles = true, folders = folder.folders, files = folder.files, subfolderChoice = false,
        ) { dirs, files, _ -> organizer.setOwnMembers(folder.id, dirs, files) }
    }

    fun setTile(set: VirtualSet, page: String?): FolderTile {
        val videos = members?.sets?.get(set.id).orEmpty()
        return FolderTile(
            key = "set:" + set.id,
            title = set.name,
            detail = if (set.isEmpty) "Empty · click to choose" else plural(videos.size, "video"),
            coverKey = ArtworkKeys.virtualSet(set.id),
            coverVideos = videos,
            onOpen = { if (set.isEmpty) chooseSet(set) else go { setId = set.id; setGroup = null } },
            menu = listOfNotNull(
                MenuAction("Open", SkyIcon.Folder) { go { setId = set.id; setGroup = null } },
                page?.let { MenuAction("Select", SkyIcon.Check) { hub.toggleFolder(it, "set:" + set.id) } },
                MenuAction("Choose folders and files", SkyIcon.Edit) { chooseSet(set) },
                MenuAction("Play all", SkyIcon.Play, enabled = videos.isNotEmpty()) { play(videos) },
                coverAction(ArtworkKeys.virtualSet(set.id), set.name, videos),
                MenuAction("Rename", SkyIcon.Edit) {
                    dialogs.naming = NameSpec("Rename virtual folder", set.name, "Only the name in this app changes.", "Rename") { organizer.renameSet(set.id, it) }
                },
                MenuAction("Delete virtual folder…", SkyIcon.Delete) {
                    dialogs.confirm = ConfirmSpec(
                        "Delete ${set.name}?",
                        "The virtual folder and its subfolders are removed from this app. Your videos and folders on disk are not touched.",
                        "Delete",
                    ) { organizer.deleteSet(set.id) }
                },
            ),
            selected = page?.let { hub.ticked(it, "set:" + set.id) },
            onSelect = page?.let { { hub.toggleFolder(it, "set:" + set.id) } },
        )
    }

    fun chooseSet(set: VirtualSet) {
        dialogs.picker = PickerSpec(
            "Choose for ${set.name}",
            "Tick folders (everything inside them is included) and single videos. They are sorted into subfolders by the names in the file names. Untick to take something out. Files on your disk are not changed.",
            withFiles = true, folders = set.folders, files = set.files, subfolderChoice = false,
        ) { dirs, files, _ -> organizer.setSetMembers(set.id, dirs, files) }
    }
}

/* ---------- The page ---------- */

@Composable
internal fun FoldersScreen(
    library: LibraryModel,
    organizer: Organizer,
    hub: HubState,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
) {
    val prefs = organizer.data.prefs
    val tabs = prefs.tabs
    LaunchedEffect(tabs) { if (hub.tab !in tabs) hub.tab = tabs.first() }

    // The places added in Files: folders on this PC and network shares. The folder choosers start there.
    val localRoots = remember(library.revision) { runCatching { LocalFolderStore().load() }.getOrDefault(emptyList()) }
    val shareRoots = remember(library.all) {
        library.all.filter { it.sourceType == "SMB" }.mapNotNull { shareRootOf(it.path) }.distinctBy { it.lowercase() }
    }
    val roots = remember(localRoots, shareRoots) { localRoots + shareRoots }
    val dialogs = remember { FolderDialogs() }
    val data = organizer.data
    val members = computed(hub, "members", data.own, data.match, data.sets, library.all) {
        Memberships(
            own = data.own.associate { it.id to FolderLogic.members(it.folders, it.files, library.all) },
            match = data.match.associate { it.id to FolderLogic.matchVideos(it, library.all) },
            sets = data.sets.associate { it.id to FolderLogic.members(it.folders, it.files, library.all) },
        )
    }
    val f = Folders(library, organizer, hub, history, onPlay, roots, dialogs, members, LocalCovers.current?.artwork?.settings?.enabled == true)

    val inside = when (hub.tab) {
        0 -> hub.ownId != null || hub.matchId != null
        else -> hub.setId != null
    }
    // Moving to another place ends a selection, like on Android.
    LaunchedEffect(hub.tab, hub.ownId, hub.ownGroup, hub.drivePath, hub.matchId, hub.setId, hub.setGroup) {
        hub.endFolderSelection()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!inside) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PageTitle(
                    "Folders",
                    when {
                        library.loading -> "Loading your library…"
                        library.all.isEmpty() -> "Add folders or a network share in Files"
                        else -> plural(library.all.size, "video")
                    },
                    Modifier.weight(1f),
                )
                SkyField(hub.query, { hub.query = it }, "Search " + prefs.tabName(hub.tab).lowercase(), Modifier.width(300.dp), leading = SkyIcon.Search)
            }
            FolderTabsRow(f)
        }
        organizer.problem?.let { Text(it, color = LocalSkyTheme.current.accent, style = MaterialTheme.typography.bodySmall) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (hub.tab) {
                0 -> when {
                    hub.matchId != null -> MatchPage(f)
                    hub.ownId != null -> OwnPage(f)
                    else -> CustomList(f)
                }
                else -> if (hub.setId != null) SetPage(f) else VirtualList(f)
            }
        }
    }
    FolderDialogsHost(f)
}

/** The tabs: click to switch, hold (or right click) to rename, hide or bring back a tab. One tab always stays. */
@Composable
private fun FolderTabsRow(f: Folders) {
    val prefs = f.prefs
    var menuFor by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.skyGlass(RoundedCornerShape(50)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        prefs.tabs.forEach { tab ->
            Box {
                SkyChip(
                    prefs.tabName(tab), f.hub.tab == tab,
                    onClick = { if (f.hub.tab != tab) { f.hub.tab = tab; f.hub.query = "" } },
                    onLongClick = { menuFor = tab },
                )
                SkyMenu(
                    menuFor == tab, { menuFor = null },
                    listOf(
                        MenuAction("Rename", SkyIcon.Edit) {
                            f.dialogs.naming = NameSpec(
                                "Rename tab", prefs.tabName(tab),
                                "Shown on the tab. Leave it as \"${FolderTabs.DEFAULTS[tab]}\" to keep the usual name.", "Rename",
                            ) { name -> f.organizer.updatePrefs { it.copy(tabNames = it.tabNames + (tab to name)) } }
                        },
                        MenuAction("Hide this tab", SkyIcon.Close, enabled = prefs.tabs.size > 1) {
                            f.organizer.updatePrefs { it.copy(tabs = it.tabs - tab) }
                        },
                    ) + FolderTabs.ALL.filterNot { it in prefs.tabs }.map { hidden ->
                        MenuAction("Show ${prefs.tabName(hidden)}", SkyIcon.Add) { f.organizer.updatePrefs { it.copy(tabs = it.tabs + hidden) } }
                    },
                )
            }
        }
    }
}

/** A button with a word that opens a menu under it (the Add button). */
@Composable
private fun MenuTextButton(text: String, icon: SkyIcon, actions: List<MenuAction>, primary: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        SkyButton(text, { open = true }, primary = primary, icon = icon)
        SkyMenu(open, { open = false }, actions)
    }
}

/** Grid or list, and the number of columns, the same menu entry on every page. */
private fun layoutActions(f: Folders): List<MenuAction> = listOf(
    MenuAction(if (f.prefs.listMode) "Show as grid" else "Show as list", if (f.prefs.listMode) SkyIcon.Grid else SkyIcon.Queue) {
        f.organizer.updatePrefs { it.copy(listMode = !it.listMode) }
    },
)

private val SORTS = listOf("NAME" to "Name", "COUNT" to "Number of items", "DATE" to "Latest by date", "SIZE" to "Total size")

private fun sortActions(f: Folders): List<MenuAction> = SORTS.map { (sort, label) ->
    val mark = if (f.prefs.sort == sort) (if (f.prefs.descending) "  ↓" else "  ↑") else ""
    MenuAction(label + mark, SkyIcon.Sort) { f.organizer.selectSort(sort) }
}

/** The row under the tabs on a list page: the page's buttons on the right, or the selection bar while selecting. */
@Composable
private fun ListToolbar(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.weight(1f))
        content()
    }
}

/* ---------- Custom folders ---------- */

@Composable
private fun CustomList(f: Folders) {
    val page = "custom"
    val own = f.data.own.filter { it.parentId == null && f.matches(it.name) }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val match = f.data.match.filter { !it.hidden && f.matches(it.name) }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val hiddenCount = f.data.match.count { it.hidden }
    val allKeys = own.map { "own:" + it.id } + match.map { "match:" + it.id }

    fun videosOf(keys: Set<String>): List<WindowsMediaEntry> = keys.flatMap { key ->
        when {
            key.startsWith("own:") -> f.members?.own?.get(key.removePrefix("own:")).orEmpty()
            key.startsWith("match:") -> f.members?.match?.get(key.removePrefix("match:")).orEmpty()
            else -> emptyList()
        }
    }.distinctBy { it.path.lowercase(Locale.ROOT) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.hub.folderPage == page) {
            val keys = f.hub.folderKeys
            val ownChosen = keys.any { it.startsWith("own:") }
            FolderSelectionBar(
                count = keys.size,
                total = allKeys.size,
                onClose = { f.hub.endFolderSelection() },
                onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = allKeys.toSet() },
                actions = listOf(
                    MenuAction("Play", SkyIcon.Play) { f.play(videosOf(keys)); f.hub.endFolderSelection() },
                    // Folders you made yourself are removed rather than hidden.
                    MenuAction("Hide", SkyIcon.Close, enabled = !ownChosen) {
                        f.organizer.hideMatch(keys.filter { it.startsWith("match:") }.map { it.removePrefix("match:") })
                        f.hub.endFolderSelection()
                    },
                ),
                confirm = MenuAction("Remove", SkyIcon.Delete) {
                    keys.forEach { key ->
                        if (key.startsWith("own:")) f.organizer.deleteOwn(key.removePrefix("own:"))
                        if (key.startsWith("match:")) f.organizer.deleteMatch(key.removePrefix("match:"))
                    }
                    f.hub.endFolderSelection()
                },
            )
        } else {
            ListToolbar {
                MenuTextButton(
                    "Add", SkyIcon.Add,
                    listOf(
                        MenuAction("New folder", SkyIcon.Folder) {
                            f.dialogs.naming = NameSpec(
                                "New folder", "", "Give it any name, then choose the folders and videos it shows. Nothing on your disk changes.", "Create",
                            ) { name -> f.organizer.createOwn(name)?.let { id -> f.data.own.firstOrNull { it.id == id }?.let(f::chooseOwn) } }
                        },
                        MenuAction("Add drive folders", SkyIcon.Computer) {
                            f.dialogs.picker = PickerSpec(
                                "Add drive folders",
                                "Tick the folders to keep in Custom folders. Each one shows the videos inside it; switch off Include sub folders to show only the videos directly in it.",
                                withFiles = false, folders = emptyList(), files = emptyList(), subfolderChoice = true,
                            ) { dirs, _, sub -> f.organizer.addDriveFolders(dirs, sub) }
                        },
                        MenuAction("Create filename collection", SkyIcon.Search) {
                            f.dialogs.naming = NameSpec(
                                "Create automatic custom folder", "",
                                "The folder name is matched anywhere in video file names, ignoring case. New matches appear after the library is scanned again.",
                                "Create",
                            ) { f.organizer.createCollection(it) }
                        },
                    ),
                )
                SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = allKeys.isNotEmpty())
                GridControls(f.organizer, page)
                SkyMenuButton(
                    SkyIcon.MoreVert, "Folder options",
                    layoutActions(f) + listOfNotNull(
                        if (hiddenCount > 0) MenuAction("Show hidden folders ($hiddenCount)", SkyIcon.Refresh) { f.organizer.showAllMatch() } else null,
                    ),
                )
            }
        }
        val tiles = own.map { f.ownTile(it, page) } + match.map { matchTile(f, it, page) }
        TileGrid(
            tiles = tiles,
            state = f.hub.grid(page),
            organizer = f.organizer,
            page = page,
            history = f.history,
            onPlay = f.onPlay,
            modifier = Modifier.weight(1f),
            emptyText = if (f.query.isNotEmpty()) "No custom folders found."
            else "Keep your favourite folders together. Use Add to make a folder with your own name, add folders from your drive, or collect videos by filename.",
            footer = plural(tiles.size, "folder"),
        )
    }
}

private fun matchTile(f: Folders, folder: MatchFolder, page: String): FolderTile {
    val videos = f.members?.match?.get(folder.id).orEmpty()
    val key = "match:" + folder.id
    return FolderTile(
        key = key,
        title = folder.name,
        detail = plural(videos.size, "video"),
        coverKey = ArtworkKeys.matchFolder(folder.id),
        coverVideos = videos,
        onOpen = { f.go { matchId = folder.id } },
        menu = listOfNotNull(
            MenuAction("Open", SkyIcon.Folder) { f.go { matchId = folder.id } },
            MenuAction("Play all", SkyIcon.Play, enabled = videos.isNotEmpty()) { f.play(videos) },
            MenuAction("Select", SkyIcon.Check) { f.hub.toggleFolder(page, key) },
            f.coverAction(ArtworkKeys.matchFolder(folder.id), folder.name, videos),
            MenuAction("Rename", SkyIcon.Edit) {
                f.dialogs.naming = NameSpec(
                    "Rename ${folder.name}", folder.name,
                    if (folder.sourcePath == null) "Renaming also changes the automatic filename match." else "Change the display name. Your folder on disk keeps its name.",
                    "Rename",
                ) { f.organizer.renameMatch(folder.id, it) }
            },
            MenuAction("Merge into", SkyIcon.Grid) {
                f.dialogs.naming = NameSpec(
                    "Merge ${folder.name}", "",
                    "Files the matching videos under this name in Virtual folders. This folder stays available.", "Merge",
                ) { name -> f.organizer.assign(videos.map { it.path }, name) }
            },
            MenuAction("Details", SkyIcon.Info) {
                f.dialogs.info = folder.name to (
                    folder.sourcePath?.let { "${plural(videos.size, "indexed video")} in $it. " + if (folder.includeSubfolders) "Includes sub folders." else "This folder only." }
                        ?: "${plural(videos.size, "video")} with '${folder.term}' anywhere in the file name, ignoring case. The list updates by itself."
                    )
            },
            MenuAction("Hide", SkyIcon.Close) { f.organizer.hideMatch(listOf(folder.id)) },
            MenuAction("Remove from Custom folders…", SkyIcon.Delete) {
                f.dialogs.confirm = ConfirmSpec(
                    "Remove ${folder.name}?",
                    if (folder.sourcePath == null) "Remove this automatic collection? Your files and the videos filed by hand are kept."
                    else "Remove this folder from Custom folders? Files on your disk are kept.",
                    "Remove",
                ) { f.organizer.deleteMatch(folder.id) }
            },
        ),
        selected = f.hub.ticked(page, key),
        onSelect = { f.hub.toggleFolder(page, key) },
    )
}

/** A self-filling Custom folder opened: its videos. */
@Composable
private fun MatchPage(f: Folders) {
    val folder = f.data.match.firstOrNull { it.id == f.hub.matchId }
    if (folder == null) {
        LaunchedEffect(Unit) { f.hub.matchId = null }
        return
    }
    val page = "match:" + folder.id
    val all = f.members?.match?.get(folder.id).orEmpty()
    val videos = all.filter { f.matches(it.file.name) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.hub.folderPage == page) {
            VideoSelectionBar(f, page, videos)
        } else {
            FolderHeader(folder.name, plural(all.size, "video"), { f.go { matchId = null } }) {
                SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = videos.isNotEmpty())
                SkyField(f.hub.query, { f.hub.query = it }, "Search here", Modifier.width(220.dp), leading = SkyIcon.Search)
                GridControls(f.organizer, page)
                SkyMenuButton(SkyIcon.MoreVert, "Folder options", layoutActions(f))
                SkyButton("Play all", { f.play(videos) }, enabled = videos.isNotEmpty(), icon = SkyIcon.Play)
            }
        }
        TileGrid(
            f.videoTiles(videos, page), f.hub.grid(page), f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
            emptyText = if (f.members == null) "Loading…" else "No videos match yet.",
            footer = plural(videos.size, "video"),
        )
    }
}

/** The selection bar of a page that lists videos. */
@Composable
private fun VideoSelectionBar(f: Folders, page: String, videos: List<WindowsMediaEntry>, takeOut: MenuAction? = null) {
    val keys = f.hub.folderKeys
    FolderSelectionBar(
        count = keys.size,
        total = videos.size,
        onClose = { f.hub.endFolderSelection() },
        onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = videos.map { "file:" + it.path }.toSet() },
        actions = f.videoActions { f.byPaths(keys.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }) },
        confirm = takeOut,
    )
}

/** A Custom folder you filled yourself, opened: folders made inside it, its chosen folders, then its single videos. */
@Composable
private fun OwnPage(f: Folders) {
    val folder = f.data.own.firstOrNull { it.id == f.hub.ownId }
    if (folder == null) {
        LaunchedEffect(Unit) { f.hub.ownId = null; f.hub.ownGroup = null; f.hub.drivePath = null }
        return
    }
    val drive = f.hub.drivePath
    if (drive != null) {
        PhysicalFolderPage(
            f, drive,
            onBack = {
                val parent = File(drive).parent
                f.go { drivePath = if (folder.folders.any { it.equals(drive, ignoreCase = true) } || parent == null) null else parent }
            },
            onOpen = { path -> f.go { drivePath = path } },
        )
        return
    }
    val children = f.data.own.filter { it.parentId == folder.id }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val rules = f.prefs.customRules
    val assignments = f.data.assignments
    val content = computed(f.hub, "own:" + folder.id, folder, f.library.all, rules, assignments) {
        val singles = FolderLogic.members(emptyList(), folder.files, f.library.all)
        val dirCounts = folder.folders.associateWith { FolderLogic.members(listOf(it), emptyList(), f.library.all) }
        Triple(singles, FolderLogic.groupByNames(singles, rules, assignments), dirCounts)
    }
    val singles = content?.first.orEmpty()
    val groups = content?.second.orEmpty()
    val dirVideos = content?.third.orEmpty()
    val group = f.hub.ownGroup
    val page = "own:" + folder.id + (group?.let { "|$it" } ?: "")
    val everything = f.members?.own?.get(folder.id).orEmpty()

    val tiles: List<Tile>
    val groupVideos: List<WindowsMediaEntry>
    if (group == null) {
        groupVideos = emptyList()
        val childTiles = children.filter { f.matches(it.name) }.map { f.ownTile(it, null) }
        val dirTiles = folder.folders.filter { f.matches(leafOf(it)) }.map { path ->
            val videos = dirVideos[path].orEmpty()
            val key = "dir:$path"
            FolderTile(
                key, FolderLogic.originalLabel(leafOf(path)), plural(videos.size, "video"), driveCoverKey(path), videos,
                onOpen = { f.go { drivePath = path } },
                menu = listOfNotNull(
                    MenuAction("Open", SkyIcon.Folder) { f.go { drivePath = path } },
                    MenuAction("Select", SkyIcon.Check) { f.hub.toggleFolder(page, key) },
                    MenuAction("Play all", SkyIcon.Play, enabled = videos.isNotEmpty()) { f.play(videos) },
                    MenuAction("Details", SkyIcon.Info) { f.dialogs.info = leafOf(path) to "$path\n${plural(videos.size, "video")}" },
                    f.coverAction(driveCoverKey(path), FolderLogic.originalLabel(leafOf(path)), videos),
                    MenuAction("Take out of this folder", SkyIcon.Close) { f.organizer.takeOutOfOwn(folder.id, listOf(path), emptyList()) },
                ),
                selected = f.hub.ticked(page, key),
                onSelect = { f.hub.toggleFolder(page, key) },
            )
        }
        val rest: List<Tile> = if (folder.grouped) {
            groups.entries.filter { f.matches(FolderLogic.groupTitle(it.key)) }.map { (identity, videos) ->
                val key = "group:$identity"
                FolderTile(
                    key, FolderLogic.groupTitle(identity), plural(videos.size, "video"), ArtworkKeys.virtualFolder(identity), videos,
                    onOpen = { f.go { ownGroup = identity } },
                    menu = listOfNotNull(
                        MenuAction("Open", SkyIcon.Folder) { f.go { ownGroup = identity } },
                        MenuAction("Select", SkyIcon.Check) { f.hub.toggleFolder(page, key) },
                        MenuAction("Play all", SkyIcon.Play) { f.play(videos) },
                        f.coverAction(ArtworkKeys.virtualFolder(identity), FolderLogic.groupTitle(identity), videos),
                    ),
                    selected = f.hub.ticked(page, key),
                    onSelect = { f.hub.toggleFolder(page, key) },
                )
            }
        } else {
            f.videoTiles(singles.filter { f.matches(it.file.name) }, page)
        }
        tiles = childTiles + dirTiles + rest
    } else {
        groupVideos = groups[group].orEmpty().filter { f.matches(it.file.name) }
        tiles = f.videoTiles(groupVideos, page)
    }
    // A name group that disappears (its videos were taken out) returns to the folder.
    LaunchedEffect(content != null, group, groups.containsKey(group ?: "")) {
        if (content != null && group != null && !groups.containsKey(group)) f.hub.ownGroup = null
    }

    fun pathsOf(keys: Set<String>): List<String> = keys.flatMap { key ->
        when {
            key.startsWith("group:") -> groups[key.removePrefix("group:")].orEmpty().map { it.path }
            key.startsWith("file:") -> listOf(key.removePrefix("file:"))
            else -> emptyList()
        }
    }
    fun videosOf(keys: Set<String>): List<WindowsMediaEntry> =
        (keys.filter { it.startsWith("dir:") }.flatMap { dirVideos[it.removePrefix("dir:")].orEmpty() } + f.byPaths(pathsOf(keys)))
            .distinctBy { it.path.lowercase(Locale.ROOT) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.hub.folderPage == page) {
            val keys = f.hub.folderKeys
            val selectable = tiles.filter { (it as? FolderTile)?.onSelect != null || it is VideoTile }.map { if (it is VideoTile) "file:" + it.entry.path else it.key }
            FolderSelectionBar(
                count = keys.size,
                total = selectable.size,
                onClose = { f.hub.endFolderSelection() },
                onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = selectable.toSet() },
                actions = f.videoActions { videosOf(keys) },
                confirm = MenuAction("Take out", SkyIcon.Close) {
                    f.organizer.takeOutOfOwn(folder.id, keys.filter { it.startsWith("dir:") }.map { it.removePrefix("dir:") }, pathsOf(keys))
                    f.hub.endFolderSelection()
                },
                confirmText = "They are taken out of ${folder.name} only. Files on your disk are not touched.",
            )
        } else {
            val title = group?.let(FolderLogic::groupTitle) ?: folder.name
            val folderCount = if (group == null) children.size + folder.folders.size + (if (folder.grouped) groups.size else 0) else 0
            val subtitle = if (group == null) "${plural(folderCount, "folder")} · ${plural(everything.size, "video")}" else plural(groupVideos.size, "video")
            FolderHeader(title, subtitle, {
                if (group != null) f.go { ownGroup = null }
                else f.go { ownId = folder.parentId; ownGroup = null }
            }) {
                if (group == null) {
                    SkyIconButton(SkyIcon.Add, "New folder inside ${folder.name}", {
                        f.dialogs.naming = NameSpec(
                            "New folder inside ${folder.name}", "",
                            "Give it any name, then choose the folders and videos it shows. Nothing on your disk changes.", "Create",
                        ) { name -> f.organizer.createOwn(name, parentId = folder.id)?.let { id -> f.data.own.firstOrNull { it.id == id }?.let(f::chooseOwn) } }
                    })
                    SkyIconButton(SkyIcon.Edit, "Choose folders and files", { f.chooseOwn(folder) })
                    if (singles.isNotEmpty()) {
                        SkyIconButton(
                            if (folder.grouped) SkyIcon.Queue else SkyIcon.Grid,
                            if (folder.grouped) "Show single videos one by one" else "Group single videos into folders by name",
                            { f.organizer.setOwnGrouped(folder.id, !folder.grouped) },
                        )
                    }
                }
                SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = tiles.isNotEmpty())
                SkyField(f.hub.query, { f.hub.query = it }, "Search here", Modifier.width(200.dp), leading = SkyIcon.Search)
                GridControls(f.organizer, page)
                SkyMenuButton(SkyIcon.MoreVert, "Folder options", layoutActions(f))
                SkyButton("Play all", { f.play(if (group == null) everything else groupVideos) }, enabled = (if (group == null) everything else groupVideos).isNotEmpty(), icon = SkyIcon.Play)
            }
        }
        if (folder.isEmpty && children.isEmpty() && group == null) {
            EmptyAction("Nothing here yet", "Choose folders and videos for this folder.", "Choose folders and files") { f.chooseOwn(folder) }
        } else {
            TileGrid(
                tiles, f.hub.grid(page), f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
                emptyText = if (content == null) "Loading…" else "Nothing matches.",
                footer = if (group == null) "${plural(tiles.count { it is FolderTile }, "folder")} · ${plural(tiles.count { it is VideoTile } + (if (folder.grouped) singles.size else 0), "video")}"
                else plural(groupVideos.size, "video"),
            )
        }
    }
}

/** A message with one button, for an empty folder. */
@Composable
private fun EmptyAction(title: String, text: String, button: String, onClick: () -> Unit) {
    val theme = LocalSkyTheme.current
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text(text, color = theme.muted, style = MaterialTheme.typography.bodyMedium)
        SkyButton(button, onClick, icon = SkyIcon.Edit)
    }
}

/* ---------- Virtual folders ---------- */

@Composable
private fun VirtualList(f: Folders) {
    val page = "virtual"
    val sets = f.data.sets.filter { it.parentId == null && f.matches(it.name) }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val allKeys = sets.map { "set:" + it.id }
    fun newSet() {
        f.dialogs.naming = NameSpec(
            "New virtual folder", "",
            "Give it any name, such as Hollywood Movies, then choose the folders it reads. Its subfolders are made from the names in your file names. Nothing on your disk changes.",
            "Create",
        ) { name -> f.organizer.createSet(name)?.let { id -> f.data.sets.firstOrNull { it.id == id }?.let(f::chooseSet) } }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.hub.folderPage == page) {
            val keys = f.hub.folderKeys
            FolderSelectionBar(
                count = keys.size,
                total = allKeys.size,
                onClose = { f.hub.endFolderSelection() },
                onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = allKeys.toSet() },
                actions = listOf(
                    MenuAction("Play", SkyIcon.Play) {
                        f.play(keys.flatMap { f.members?.sets?.get(it.removePrefix("set:")).orEmpty() }.distinctBy { it.path.lowercase(Locale.ROOT) })
                        f.hub.endFolderSelection()
                    },
                ),
                confirm = MenuAction("Delete", SkyIcon.Delete) {
                    keys.forEach { f.organizer.deleteSet(it.removePrefix("set:")) }
                    f.hub.endFolderSelection()
                },
                confirmText = "The virtual folders and their subfolders are removed from this app. Your videos and folders on disk are not touched.",
            )
        } else {
            ListToolbar {
                SkyButton("New virtual folder", { newSet() }, icon = SkyIcon.Add)
                SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = sets.isNotEmpty())
                GridControls(f.organizer, page)
                SkyMenuButton(SkyIcon.MoreVert, "Folder options", layoutActions(f))
            }
        }
        if (sets.isEmpty() && f.query.isEmpty()) {
            EmptyAction(
                "Make your own virtual folders",
                "Make a folder, such as Hollywood Movies. Choose the folders it reads and its subfolders are made from the names in your file names.",
                "New virtual folder",
            ) { newSet() }
        } else {
            TileGrid(
                sets.map { f.setTile(it, page) }, f.hub.grid(page), f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
                emptyText = "No matching folders.",
                footer = plural(sets.size, "folder"),
            )
        }
    }
}

/**
 * The inside of a Virtual folder: folders made inside it, then one subfolder per name found in the file names of
 * what it reads. A video with several names before the first hyphen is listed in each, never copied.
 */
@Composable
private fun SetPage(f: Folders) {
    val set = f.data.sets.firstOrNull { it.id == f.hub.setId }
    if (set == null) {
        LaunchedEffect(Unit) { f.hub.setId = null; f.hub.setGroup = null }
        return
    }
    val children = f.data.sets.filter { it.parentId == set.id }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val videos = f.members?.sets?.get(set.id).orEmpty()
    val prefs = f.prefs
    val assignments = f.data.assignments
    val groups = computed(f.hub, "set:" + set.id, set, videos, prefs.virtualRules, assignments, prefs.sort, prefs.descending) {
        FolderLogic.sorted(FolderLogic.buildGroups(set, videos, prefs.virtualRules, assignments), prefs.sort, prefs.descending)
    }
    val group = f.hub.setGroup
    val current = groups?.firstOrNull { it.identity == group }
    // A subfolder that disappears (renamed, taken out, emptied by a scan) returns to the list.
    LaunchedEffect(groups != null, group, current == null) {
        if (groups != null && group != null && current == null) f.hub.setGroup = null
    }
    val page = "set:" + set.id + (group?.let { "|$it" } ?: "")
    val shownGroups = groups.orEmpty().filter { f.matches(it.title) }
    val inside = current?.videos.orEmpty().filter { f.matches(it.file.name) }

    val tiles: List<Tile> = if (group == null) {
        children.filter { f.matches(it.name) }.map { f.setTile(it, null) } + shownGroups.map { g ->
            val key = "group:" + g.identity
            FolderTile(
                key, g.title, plural(g.videos.size, "video"), ArtworkKeys.virtualSub(set.id, g.identity), g.videos,
                onOpen = { f.go { setGroup = g.identity } },
                menu = listOfNotNull(
                    MenuAction("Open", SkyIcon.Folder) { f.go { setGroup = g.identity } },
                    MenuAction("Select", SkyIcon.Check) { f.hub.toggleFolder(page, key) },
                    MenuAction("Play all", SkyIcon.Play) { f.play(g.videos) },
                    f.coverAction(ArtworkKeys.virtualSub(set.id, g.identity), g.title, g.videos),
                    MenuAction("Rename", SkyIcon.Edit) {
                        f.dialogs.naming = NameSpec(
                            "Rename folder", g.title,
                            "Only the name in this app changes. Giving it the name of another subfolder joins the two. Your files keep their names.",
                            "Rename",
                        ) { f.organizer.renameSubfolder(set.id, g.identity, it) }
                    },
                    MenuAction("Details", SkyIcon.Info) {
                        f.dialogs.info = "Details" to "${g.title}\n${plural(g.videos.size, "video")} · ${formatSize(g.totalSize)}\nSubfolder of ${set.name}\nA video listed in several subfolders is the same file, never a copy."
                    },
                    MenuAction("Take out of this folder", SkyIcon.Close) { f.organizer.hideSubfolders(set.id, listOf(g.identity)) },
                ),
                selected = f.hub.ticked(page, key),
                onSelect = { f.hub.toggleFolder(page, key) },
            )
        }
    } else {
        f.videoTiles(inside, page)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.hub.folderPage == page) {
            if (group == null) {
                val keys = f.hub.folderKeys
                val allKeys = shownGroups.map { "group:" + it.identity }
                FolderSelectionBar(
                    count = keys.size,
                    total = allKeys.size,
                    onClose = { f.hub.endFolderSelection() },
                    onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = allKeys.toSet() },
                    actions = f.videoActions {
                        keys.flatMap { k -> groups.orEmpty().firstOrNull { "group:" + it.identity == k }?.videos.orEmpty() }.distinctBy { it.path.lowercase(Locale.ROOT) }
                    },
                    confirm = MenuAction("Take out", SkyIcon.Close) {
                        f.organizer.hideSubfolders(set.id, keys.map { it.removePrefix("group:") })
                        f.hub.endFolderSelection()
                    },
                    confirmText = "The subfolders are taken out of ${set.name}. Show folders you took out brings them back. Files on your disk are not touched.",
                )
            } else {
                VideoSelectionBar(f, page, inside)
            }
        } else {
            val subtitle = if (group == null) "${plural(children.size + (groups?.size ?: 0), "folder")} · ${plural(videos.size, "video")}" else plural(current?.videos?.size ?: 0, "video")
            FolderHeader(current?.title ?: set.name, subtitle, {
                if (group != null) f.go { setGroup = null } else f.go { setId = set.parentId; setGroup = null }
            }) {
                if (group == null) {
                    SkyIconButton(SkyIcon.Add, "New folder inside ${set.name}", {
                        f.dialogs.naming = NameSpec(
                            "New folder inside ${set.name}", "",
                            "Give it any name, then choose the folders and videos it reads. Nothing on your disk changes.", "Create",
                        ) { name -> f.organizer.createSet(name, parentId = set.id)?.let { id -> f.data.sets.firstOrNull { it.id == id }?.let(f::chooseSet) } }
                    })
                    SkyIconButton(SkyIcon.Edit, "Choose folders and files", { f.chooseSet(set) })
                    if (set.hidden.isNotEmpty()) SkyIconButton(SkyIcon.Refresh, "Show folders you took out", { f.organizer.restoreSubfolders(set.id) })
                }
                SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = tiles.isNotEmpty())
                SkyField(f.hub.query, { f.hub.query = it }, "Search here", Modifier.width(200.dp), leading = SkyIcon.Search)
                GridControls(f.organizer, page)
                SkyMenuButton(SkyIcon.MoreVert, "Folder options", (if (group == null) sortActions(f) else emptyList()) + layoutActions(f))
                val playList = if (group == null) videos else inside
                SkyButton("Play all", { f.play(playList) }, enabled = playList.isNotEmpty(), icon = SkyIcon.Play)
            }
        }
        when {
            set.isEmpty && children.isEmpty() ->
                EmptyAction("Nothing here yet", "Choose the folders and videos this virtual folder reads.", "Choose folders and files") { f.chooseSet(set) }
            !set.isEmpty && groups != null && groups.isEmpty() && children.isEmpty() && f.members != null ->
                EmptyAction(
                    "No videos found yet",
                    "Subfolders appear once the chosen folders are in your library. Scan them again in Files if something is missing.",
                    "Choose folders and files",
                ) { f.chooseSet(set) }
            else -> TileGrid(
                tiles, f.hub.grid(page), f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
                emptyText = if (groups == null) "Loading…" else "Nothing matches.",
                footer = if (group == null) "${plural(tiles.size, "folder")} · ${plural(videos.size, "video")}" else plural(inside.size, "video"),
                letters = group != null || prefs.sort == "NAME",
            )
        }
    }
}

/* ---------- Drive folders (opened from a Custom folder) ---------- */

private fun physicalTile(
    f: Folders,
    page: String,
    path: String,
    title: String,
    detail: String,
    videos: List<WindowsMediaEntry>,
    onOpen: () -> Unit,
): FolderTile {
    val key = "dir:$path"
    return FolderTile(
        key, title, detail, driveCoverKey(path), videos, onOpen,
        menu = listOfNotNull(
            MenuAction("Open", SkyIcon.Folder, action = onOpen),
            MenuAction("Select", SkyIcon.Check) { f.hub.toggleFolder(page, key) },
            MenuAction("Play all", SkyIcon.Play, enabled = videos.isNotEmpty()) { f.play(videos) },
            MenuAction("Add to Custom folders", SkyIcon.Add) { f.organizer.addDriveFolders(listOf(path), true) },
            MenuAction("Add to folder…", SkyIcon.Folder) { f.dialogs.filing = false to videos.map { it.path } },
            f.coverAction(driveCoverKey(path), title, videos),
            MenuAction("Details", SkyIcon.Info) { f.dialogs.info = leafOf(path) to "$path\n${plural(videos.size, "video")}" },
        ),
        selected = f.hub.ticked(page, key),
        onSelect = { f.hub.toggleFolder(page, key) },
    )
}

/**
 * A real folder opened: its sub folders first, then its videos, all in one grid that scrolls top to bottom. Used by
 * Original folders and by a drive folder opened from a Custom folder.
 */
@Composable
private fun PhysicalFolderPage(f: Folders, path: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val page = "orig:" + path.lowercase(Locale.ROOT)
    val files = remember(f.library.all) { f.library.all.map { File(it.path) } }
    val byPath = remember(f.library.all) { f.library.all.associateBy { it.path.lowercase(Locale.ROOT) } }
    val content = computed(f.hub, "orig:" + path.lowercase(Locale.ROOT), path, files, f.roots) {
        val contents = localOriginalFolderContents(f.roots, File(path), files)
        val subs = contents.folders.map { it.absolutePath to FolderLogic.members(listOf(it.absolutePath), emptyList(), f.library.all) }
        subs to contents.videos.mapNotNull { byPath[it.path.lowercase(Locale.ROOT)] }
    }
    val subs = content?.first.orEmpty().filter { f.matches(leafOf(it.first)) }
    val videos = content?.second.orEmpty().filter { f.matches(it.file.name) }
    val everything = remember(content) { FolderLogic.members(listOf(path), emptyList(), f.library.all) }
    val tiles: List<Tile> = buildList {
        if (subs.isNotEmpty() && videos.isNotEmpty()) add(LabelTile("label:folders", "Folders"))
        subs.forEach { (sub, members) ->
            add(physicalTile(f, page, sub, FolderLogic.originalLabel(leafOf(sub)), plural(members.size, "video"), members) { onOpen(sub) })
        }
        if (subs.isNotEmpty() && videos.isNotEmpty()) add(LabelTile("label:videos", "Videos"))
        addAll(f.videoTiles(videos, page))
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.hub.folderPage == page) {
            val keys = f.hub.folderKeys
            val selectable = tiles.mapNotNull { if (it is VideoTile) "file:" + it.entry.path else (it as? FolderTile)?.key }
            FolderSelectionBar(
                count = keys.size,
                total = selectable.size,
                onClose = { f.hub.endFolderSelection() },
                onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = selectable.toSet() },
                actions = f.videoActions {
                    (keys.filter { it.startsWith("dir:") }.flatMap { k -> subs.firstOrNull { "dir:" + it.first == k }?.second.orEmpty() } +
                        f.byPaths(keys.filter { it.startsWith("file:") }.map { it.removePrefix("file:") })).distinctBy { it.path.lowercase(Locale.ROOT) }
                } + MenuAction("Add to Custom folders", SkyIcon.Add, enabled = keys.any { it.startsWith("dir:") }) {
                    f.organizer.addDriveFolders(keys.filter { it.startsWith("dir:") }.map { it.removePrefix("dir:") }, true)
                    f.hub.endFolderSelection()
                },
            )
        } else {
            val name = File(path).name.ifBlank { path }
            val subtitle = "${plural(content?.first?.size ?: 0, "folder")} · ${plural(everything.size, "video")}"
            FolderHeader(name, subtitle, onBack) {
                SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = tiles.isNotEmpty())
                SkyField(f.hub.query, { f.hub.query = it }, "Search here", Modifier.width(220.dp), leading = SkyIcon.Search)
                GridControls(f.organizer, page)
                SkyMenuButton(SkyIcon.MoreVert, "Folder options", layoutActions(f))
                SkyButton("Play all", { f.play(videos) }, enabled = videos.isNotEmpty(), icon = SkyIcon.Play)
            }
        }
        TileGrid(
            tiles, f.hub.grid(page), f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
            emptyText = if (content == null) "Loading…" else "No videos in this folder.",
            footer = "${plural(subs.size, "folder")} · ${plural(videos.size, "video")}",
        )
    }
}

/* ---------- Dialogs ---------- */

@Composable
private fun FolderDialogsHost(f: Folders) {
    val d = f.dialogs
    d.filing?.let { (virtual, paths) ->
        FileIntoFolderDialog(f.organizer, virtual, paths, onDismiss = { d.filing = null }, onDone = { f.hub.endFolderSelection() })
    }
    d.info?.let { (title, text) -> InfoDialog(title, text) { d.info = null } }
    d.cover?.let { target -> ChangeCoverDialog(target) { d.cover = null } }
    d.confirm?.let { spec -> ConfirmDialog(spec.title, spec.text, spec.label, { d.confirm = null }, spec.action) }
    d.naming?.let { spec -> NameDialog(spec.title, spec.initial, spec.label, { d.naming = null }, spec.action, hint = spec.hint) }
    d.picker?.let { spec ->
        DrivePicker(
            title = spec.title,
            explanation = spec.explanation,
            roots = f.roots,
            library = f.library.all,
            withFiles = spec.withFiles,
            initialFolders = spec.folders,
            initialFiles = spec.files,
            onDismiss = { d.picker = null },
            subfolderChoice = spec.subfolderChoice,
            confirmLabel = if (spec.subfolderChoice) "Add" else "Done",
            onDone = spec.onDone,
        )
    }
}
