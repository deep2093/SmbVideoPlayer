package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TAB_NAMES = listOf("Custom folders", "Virtual folders", "Original folders")

private data class OriginalTile(val folder: File, val count: Int, val cover: WindowsMediaEntry?)

private fun normalised(path: String): String =
    runCatching { java.nio.file.Paths.get(path).toAbsolutePath().normalize().toString() }.getOrDefault(path).lowercase()

private fun shareRootOf(path: String): String? {
    if (!path.startsWith("\\\\")) return null
    val parts = path.removePrefix("\\\\").split('\\')
    return if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) "\\\\" + parts[0] + "\\" + parts[1] else null
}

/**
 * The Folders page, laid out like the Android app: Custom, Virtual and Original folders as tabs, every
 * folder a card with a picture from its first video, and an opened folder showing its videos.
 */
@Composable
internal fun FoldersScreen(
    library: LibraryModel,
    custom: CustomFolders,
    hub: HubState,
    selection: SelectionState,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
) {
    val theme = LocalSkyTheme.current
    val query = hub.query.trim()
    val byPath = remember(library.all) { library.all.associateBy { it.path.lowercase() } }

    val openedCustom = hub.custom?.let { name -> custom.folders.firstOrNull { it.name == name } }
    val openedVirtual = hub.virtual?.let { key -> library.virtual.firstOrNull { it.key == key } }
    val insideFolder = when (hub.tab) {
        0 -> openedCustom != null
        1 -> openedVirtual != null
        else -> hub.original != null
    }

    // Original folders: the places that were added in Files, and what is inside the one that is open.
    val localRoots = remember(library.revision) { runCatching { LocalFolderStore().load() }.getOrDefault(emptyList()) }
    val shareRoots = remember(library.all) {
        library.all.filter { it.sourceType == "SMB" }.mapNotNull { shareRootOf(it.path) }.distinctBy { it.lowercase() }
    }
    val rootPaths = remember(localRoots, shareRoots) { localRoots + shareRoots }
    var originalTiles by remember { mutableStateOf(emptyList<OriginalTile>()) }
    var originalVideos by remember { mutableStateOf(emptyList<WindowsMediaEntry>()) }
    LaunchedEffect(hub.original, library.all, rootPaths, hub.tab) {
        if (hub.tab != 2) return@LaunchedEffect
        val current = hub.original
        val (tiles, videos) = withContext(Dispatchers.Default) {
            val contents = localOriginalFolderContents(rootPaths, current?.let { File(it) }, library.all.map { it.file })
            val prefixes = contents.folders.map { it.absolutePath.lowercase().trimEnd('\\') + "\\" }
            val counts = IntArray(prefixes.size)
            val covers = arrayOfNulls<WindowsMediaEntry>(prefixes.size)
            library.all.forEach { entry ->
                val lower = entry.path.lowercase()
                prefixes.forEachIndexed { index, prefix ->
                    if (lower.startsWith(prefix)) {
                        counts[index]++
                        if (covers[index] == null) covers[index] = entry
                    }
                }
            }
            val built = contents.folders.mapIndexed { index, folder -> OriginalTile(folder, counts[index], covers[index]) }
            built to contents.videos.mapNotNull { byPath[it.path.lowercase()] }
        }
        originalTiles = tiles
        originalVideos = videos
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Title line: the page name and search. Inside a folder the folder's own header is shown instead.
        if (!insideFolder) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PageTitle(
                    "Folders",
                    when {
                        library.loading -> "Loading your library…"
                        library.all.isEmpty() -> "Add folders or a network share in Files"
                        else -> "${library.all.size} videos"
                    },
                    Modifier.weight(1f),
                )
                SkyField(hub.query, { hub.query = it }, "Search folders", Modifier.width(300.dp), leading = SkyIcon.Search)
            }
            SkyChipBar(TAB_NAMES, hub.tab, { hub.tab = it; hub.query = ""; selection.clear() })
        }

        when (hub.tab) {
            0 -> {
                if (openedCustom != null) {
                    var renaming by remember { mutableStateOf(false) }
                    val videos = openedCustom.paths.mapNotNull { byPath[it.lowercase()] }
                        .distinctBy { it.path.lowercase() }
                        .filter { query.isEmpty() || it.file.name.contains(query, ignoreCase = true) }
                    FolderPage(
                        title = openedCustom.name,
                        entries = videos,
                        gridKey = "custom:" + openedCustom.name,
                        hub = hub, selection = selection, history = history, custom = custom, onPlay = onPlay,
                        onBack = { hub.custom = null; hub.query = ""; selection.clear() },
                        insideCustom = openedCustom.name,
                        extra = {
                            SkyButton("Rename", { renaming = true }, primary = false, icon = SkyIcon.Edit)
                        },
                    )
                    if (renaming) {
                        NameDialog("Rename folder", openedCustom.name, "Rename", { renaming = false }, { name ->
                            if (name.isNotBlank() && custom.rename(openedCustom.name, name) == null) hub.custom = name
                        })
                    }
                } else {
                    var options by remember { mutableStateOf<WindowsCustomFolder?>(null) }
                    val items = custom.folders
                        .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                        .sortedBy { it.name.lowercase() }
                        .map { folder ->
                            val members = folder.paths.mapNotNull { byPath[it.lowercase()] }.distinctBy { it.path.lowercase() }
                            FolderItem(
                                key = "custom:" + folder.name,
                                title = folder.name,
                                detail = "${members.size} videos",
                                cover = members.firstOrNull(),
                                onClick = { hub.custom = folder.name; hub.query = "" },
                                onLongClick = { options = folder },
                            )
                        }
                    FolderGridView(
                        items, hub.grid("custom"), Modifier.weight(1f),
                        emptyText = "No custom folders yet. Hold a video (or press Select) in Videos, then choose Add to folder.",
                    )
                    options?.let { folder ->
                        OptionsDialog(
                            folder.name,
                            listOf(
                                Triple("Open", SkyIcon.Folder, { hub.custom = folder.name }),
                                Triple("Play all", SkyIcon.Play, {
                                    val files = folder.paths.mapNotNull { byPath[it.lowercase()]?.file }
                                    files.firstOrNull()?.let { onPlay(it, files) }
                                }),
                                Triple("Delete folder", SkyIcon.Delete, { custom.delete(folder.name); Unit }),
                            ),
                            onDismiss = { options = null },
                        )
                    }
                }
            }
            1 -> {
                if (openedVirtual != null) {
                    val videos = openedVirtual.entries
                        .filter { query.isEmpty() || it.file.name.contains(query, ignoreCase = true) }
                    FolderPage(
                        title = openedVirtual.title,
                        entries = videos,
                        gridKey = "virtual:" + openedVirtual.key,
                        hub = hub, selection = selection, history = history, custom = custom, onPlay = onPlay,
                        onBack = { hub.virtual = null; hub.query = ""; selection.clear() },
                    )
                } else {
                    val items = library.virtual
                        .filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }
                        .map { group ->
                            FolderItem(
                                key = "virtual:" + group.key,
                                title = group.title,
                                detail = "${group.entries.size} videos",
                                cover = group.entries.firstOrNull(),
                                onClick = { hub.virtual = group.key; hub.query = "" },
                            )
                        }
                    FolderGridView(
                        items, hub.grid("virtual"), Modifier.weight(1f),
                        emptyText = if (library.loading) "Loading…" else "Virtual folders are made from your video names. Add videos in Files first.",
                    )
                }
            }
            else -> {
                val current = hub.original
                if (current != null) {
                    val currentName = File(current).name.ifBlank { current }
                    val videos = originalVideos.filter { query.isEmpty() || it.file.name.contains(query, ignoreCase = true) }
                    val tiles = originalTiles.filter { query.isEmpty() || it.folder.name.contains(query, ignoreCase = true) }
                    FolderPage(
                        title = currentName,
                        entries = videos,
                        gridKey = "original:" + current.lowercase(),
                        hub = hub, selection = selection, history = history, custom = custom, onPlay = onPlay,
                        onBack = {
                            val here = normalised(current)
                            hub.original = if (rootPaths.any { normalised(it) == here }) null else File(current).parentFile?.absolutePath
                            hub.query = ""
                            selection.clear()
                        },
                        subfolders = tiles.map { tile ->
                            FolderItem(
                                key = "orig:" + tile.folder.absolutePath.lowercase(),
                                title = tile.folder.name.ifBlank { tile.folder.absolutePath },
                                detail = "${tile.count} videos",
                                cover = tile.cover,
                                onClick = { hub.original = tile.folder.absolutePath; hub.query = "" },
                            )
                        },
                    )
                } else {
                    val items = originalTiles
                        .filter { query.isEmpty() || it.folder.name.contains(query, ignoreCase = true) || it.folder.absolutePath.contains(query, ignoreCase = true) }
                        .map { tile ->
                            val unc = shareRootOf(tile.folder.absolutePath)
                            val label = if (unc != null) {
                                library.all.firstOrNull { it.sourceType == "SMB" && it.path.lowercase().startsWith(unc.lowercase()) }?.sourceName
                                    ?.takeIf { it.isNotBlank() && it != unc } ?: unc
                            } else tile.folder.name.ifBlank { tile.folder.absolutePath }
                            FolderItem(
                                key = "orig:" + tile.folder.absolutePath.lowercase(),
                                title = label,
                                detail = (if (unc != null) "Network share  ·  " else "This PC  ·  ") + "${tile.count} videos",
                                cover = tile.cover,
                                onClick = { hub.original = tile.folder.absolutePath; hub.query = "" },
                            )
                        }
                    FolderGridView(
                        items, hub.grid("original"), Modifier.weight(1f),
                        emptyText = if (library.loading) "Loading…" else "No folders yet. Add a folder on this PC or a network share in Files.",
                    )
                }
            }
        }
    }
}

/** An opened folder: its header (or the selection bar), optional sub-folders, then its videos. */
@Composable
private fun FolderPage(
    title: String,
    entries: List<WindowsMediaEntry>,
    gridKey: String,
    hub: HubState,
    selection: SelectionState,
    history: Map<String, WindowsWatchRecord>,
    custom: CustomFolders,
    onPlay: (File, List<File>) -> Unit,
    onBack: () -> Unit,
    insideCustom: String? = null,
    subfolders: List<FolderItem> = emptyList(),
    extra: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (selection.active) {
            SelectionBar(selection, entries, custom, onPlay, insideCustom)
        } else {
            FolderHeader(title, "${entries.size} videos" + if (subfolders.isNotEmpty()) "  ·  ${subfolders.size} folders" else "", onBack) {
                extra()
                SkyButton("Select", { selection.active = true }, primary = false, icon = SkyIcon.Check, enabled = entries.isNotEmpty())
                SkyField(hub.query, { hub.query = it }, "Search here", Modifier.width(240.dp), leading = SkyIcon.Search)
                SkyButton("Play all", {
                    entries.firstOrNull()?.let { onPlay(it.file, entries.map { e -> e.file }) }
                }, enabled = entries.isNotEmpty(), icon = SkyIcon.Play)
            }
        }
        VideoGridView(
            entries = entries,
            state = hub.grid(gridKey),
            selection = selection,
            history = history,
            onPlay = onPlay,
            modifier = Modifier.weight(1f),
            header = if (subfolders.isEmpty()) null else ({
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    SubfolderRow(subfolders)
                }
            }),
            emptyText = "No videos in this folder.",
        )
    }
}

/** Folders inside the opened folder, shown above its videos. */
@Composable
private fun SubfolderRow(folders: List<FolderItem>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Folders")
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(folders.size) { index ->
                val item = folders[index]
                FolderCard(item.title, item.detail, item.cover, item.onClick, Modifier.width(210.dp), item.onLongClick)
            }
        }
    }
}
