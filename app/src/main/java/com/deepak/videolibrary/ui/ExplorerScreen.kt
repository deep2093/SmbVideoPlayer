package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.ui.draw.clip
import com.deepak.videolibrary.thumbs.ThumbStore
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.data.VideoEntity

/** Real SMB filesystem browser. Unlike virtual folders, these actions change remote files. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerScreen(vm: LibraryViewModel, onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val completed = transfers.filter { it.state.isFinished }.map { it.id }
    var priorCompleted by remember { mutableStateOf<List<java.util.UUID>?>(null) }
    LaunchedEffect(completed) {
        if (priorCompleted != null &&
            priorCompleted != completed
        ) {
            vm.browse(focus = vm.explorerFocusPath)
            if (vm.explorerQuery.isNotBlank())vm.searchExplorer(vm.explorerQuery, vm.explorerPath)
        }
        priorCompleted =
            completed
    }
    val listState = rememberLazyListState()
    var searchExpanded by rememberSaveable { mutableStateOf(vm.explorerQuery.isNotBlank()) }
    val searching = vm.explorerQuery.isNotBlank()
    // Hidden items (Hide in a menu) and, unless turned off in Settings, system folders are left out.
    val hiddenItems by vm.hiddenItems.collectAsStateWithLifecycle()
    val hiddenPaths = remember(hiddenItems) { hiddenItems.filter { it.kind == "DIRECTORY" || it.kind == "FILE" }.map { it.target.trim('/') }.toSet() }
    val listed = (
        if (searching) {
            vm.explorerHits
        } else {
            vm.explorerEntries.map {
                ExplorerHit(listOf(vm.explorerPath, it.name).filter { part -> part.isNotBlank() }.joinToString("/"), it)
            }
        }
        ).filterNot { com.deepak.videolibrary.files.ExplorerHiding.shouldHide(it.path, it.entry.name, vm.hideSystemFolders, hiddenPaths) }
    val shown = remember(listed, vm.explorerSort, vm.explorerSortDescending) {
        sortExplorerHits(listed, vm.explorerSort, vm.explorerSortDescending)
    }
    val gridState = rememberLazyGridState()
    OnScrollTopRequest(vm) { if (vm.explorerGridMode) gridState.glideToTop() else listState.glideToTop() }
    // Thumbnails for the grid come from the library index (only looked up while the grid is shown).
    val indexed by produceState(emptyMap<String, VideoEntity>(), vm.explorerGridMode, shown.map { it.path }) {
        value = if (vm.explorerGridMode) vm.indexedVideos(shown.filter { isPlayable(it.entry) }.map { it.path }) else emptyMap()
    }
    LaunchedEffect(vm.explorerFocusPath, vm.explorerBusy, shown.map { it.path }) {
        val index = shown.indexOfFirst { it.path == vm.explorerFocusPath }
        if (!vm.explorerBusy && index >= 0) {
            if (vm.explorerGridMode) gridState.scrollToItem(index) else listState.scrollToItem(index)
        }
    }
    LaunchedEffect(vm.explorerPath) {
        if (vm.explorerFocusPath == null) {
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
    }
    val playQueue = shown.filter { isPlayable(it.entry) }.map { explorerVideo(it, emptyMap()) }.distinctBy { it.relativePath }
    DisposableEffect(Unit) { onDispose { vm.stopExplorerSearch() } }
    LaunchedEffect(vm.explorerQuery, vm.explorerPath) { vm.searchExplorer(vm.explorerQuery, vm.explorerPath) }
    val selectingHere = vm.selectedPaths.isNotEmpty() && vm.selectionScope == "explorer"
    fun open(hit: ExplorerHit) {
        // While selecting, a tap ticks or unticks the entry (folders too) instead of opening it.
        if (selectingHere && (hit.entry.isDirectory || isPlayable(hit.entry))) {
            vm.toggleSelected(hit.path, "explorer", directory = hit.entry.isDirectory)
            return
        }
        if (hit.entry.isDirectory) {
            vm.explorerQuery = ""
            vm.browse(hit.path)
        } else if (isPlayable(hit.entry)) {
            val item = explorerVideo(hit, emptyMap())
            onPlay(item, if (searching) playQueue else listOf(item))
        }
    }
    LaunchedEffect(Unit) { if (vm.explorerEntries.isEmpty()) vm.browse(focus = vm.explorerFocusPath) }
    BackHandler(enabled = searching || vm.explorerPath.isNotEmpty()) {
        if (searching) vm.explorerQuery = "" else vm.browse(vm.explorerPath.substringBeforeLast('/', ""))
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchableTopBar(
            title = stringResource(R.string.ui_file_explorer_700869d),
            searching = searchExpanded,
            onSearchingChange = { searchExpanded = it },
            query = vm.explorerQuery,
            onQueryChange = { vm.explorerQuery = it },
            placeholder = stringResource(R.string.ui_search_names_anywhere_in_this_folder_and_s_7fe98c1),
        ) {
            Box {
                var sortExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort files")
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    listOf(
                        "NAME" to "Name",
                        "DATE" to "Date modified",
                        "SIZE" to "Size",
                        "TYPE" to "Type",
                    ).forEach { (sort, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label + if (vm.explorerSort == sort) {
                                        if (vm.explorerSortDescending) " ↓" else " ↑"
                                    } else {
                                        ""
                                    },
                                )
                            },
                            onClick = {
                                vm.selectExplorerSort(sort)
                                sortExpanded = false
                            },
                        )
                    }
                }
            }
            if (vm.explorerGridMode) CardShapeButton(vm, Pages.explorer(vm.explorerPath))
            IconButton(onClick = vm::toggleExplorerLayout) {
                Icon(
                    if (vm.explorerGridMode) Icons.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = if (vm.explorerGridMode) "Show file list" else "Show file grid",
                )
            }
        }
        BreadcrumbBar(
            crumbs = breadcrumbs(vm.explorerPath),
            onHome = { vm.browse("") },
            onSelect = { vm.browse(it) },
            enabled = !vm.explorerBusy,
            trailing = {
                IconButton(
                    onClick = {
                        vm.browse()
                        vm.searchExplorer(vm.explorerQuery, vm.explorerPath)
                    },
                    enabled = !vm.explorerBusy,
                    modifier = Modifier.size(40.dp),
                ) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.size(22.dp)) }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${playQueue.size} ${if (searching) "matching" else "playable"} videos", modifier = Modifier.weight(1f))
            IconAction(Icons.Filled.PlayArrow, if (vm.explorerSearching) "Play found so far" else "Play all", enabled = playQueue.isNotEmpty(), onClick = { playQueue.firstOrNull()?.let { onPlay(it, playQueue) } })
        }
        if (vm.explorerSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.explorerSearchMessage?.takeIf { searching }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (vm.explorerBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.explorerMessage?.let { message ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { vm.browse() }, enabled = !vm.explorerBusy) { Text(stringResource(R.string.ui_try_again_042c862)) }
                }
            }
        }
        if (!vm.explorerBusy && !vm.explorerSearching && shown.isEmpty() && vm.explorerMessage == null) {
            Text(
                if (searching) "No matching files or folders." else "No files in this folder. Pull a new listing with Refresh, or check your SMB connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val aspect = cardAspect(vm, Pages.explorer(vm.explorerPath))
        FolderTransition(vm.explorerPath, Modifier.weight(1f)) {
        if (vm.explorerGridMode) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(libraryColumns(vm.videoGridPreset, floatingPadding(vm))),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP),
                contentPadding = PaddingValues(bottom = if (floatingPadding(vm)) 112.dp else 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown.size, key = { shown[it].path }) { index ->
                    val hit = shown[index]
                    Box {
                        ExplorerGridCard(
                            vm = vm,
                            hit = hit,
                            indexed = indexed[hit.path],
                            focused = hit.path == vm.explorerFocusPath,
                            showFolder = searching,
                            aspect = aspect,
                            onOpen = { open(hit) },
                        )
                        if (selectingHere && hit.entry.isDirectory) {
                            SelectionBadge(hit.path in vm.selectedPaths, Modifier.align(Alignment.TopEnd).padding(8.dp))
                        }
                    }
                }
            }
        } else LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = if (floatingPadding(vm))112.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.path }) { hit ->
                val entry = hit.entry
                var menu by remember(entry.name) { mutableStateOf(false) }
                val fullPath = hit.path
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (hit.path in vm.selectedPaths && selectingHere) {
                                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            } else if (hit.path ==
                                vm.explorerFocusPath
                            ) {
                                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)).semantics {
                                    contentDescription =
                                        "Target file: ${entry.name}"
                                }
                            } else {
                                Modifier
                            },
                        ).combinedClickable(onClick = { open(hit) }, onLongClick = { menu = true }, onLongClickLabel = "Options for ${entry.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val playable = isPlayable(entry)
                    Icon(
                        if (entry.isDirectory) {
                            Icons.Filled.Folder
                        } else if (playable) {
                            Icons.Filled.Movie
                        } else {
                            Icons.Filled.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp).size(28.dp),
                    )
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(entry.name, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        if (searching) {
                            Text(
                                fullPath.substringBeforeLast('/', "/"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Box {
                        FileActionsMenu(
                            menu,
                            { menu = false },
                            vm,
                            fullPath,
                            directory = entry.isDirectory,
                            onSelect = if (entry.isDirectory) ({ vm.toggleSelected(fullPath, "explorer", directory = true) }) else null,
                            selectionScope = "explorer",
                            onPlay = if (playable)({ open(hit) })else null,
                            details = "${entry.name}\n$fullPath\n${entry.size} bytes",
                        )
                    }
                }
                HorizontalDivider()
            }
        }
        }
    }
}

/** One File Explorer entry in grid mode: thumbnail (or folder cover / file icon), name and details. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerGridCard(
    vm: LibraryViewModel,
    hit: ExplorerHit,
    indexed: VideoEntity?,
    focused: Boolean,
    showFolder: Boolean,
    aspect: Float,
    onOpen: () -> Unit,
) {
    val entry = hit.entry
    val playable = isPlayable(entry)
    var menu by remember(hit.path) { mutableStateOf(false) }
    var changeCover by remember(hit.path) { mutableStateOf(false) }
    val artworkOn = rememberArtworkEnabled(vm)
    val image: java.io.File? = when {
        entry.isDirectory -> {
            val key = vm.coverKey("original:${hit.path}", 0, 0)
            var cover by remember(key) { mutableStateOf(vm.cachedCover(key)) }
            LaunchedEffect(key) { cover = vm.originalCover(hit.path, key) }
            cover
        }
        indexed != null -> {
            val customRevision by com.deepak.videolibrary.thumbs.CustomThumbs.revision.collectAsStateWithLifecycle()
            val artRevision by vm.artworkRevision.collectAsStateWithLifecycle()
            remember(indexed.id, indexed.thumbStatus, customRevision, artRevision) {
                vm.videoArtwork(indexed)
            }
        }
        else -> null
    }
    Box {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focused) Modifier.semantics { contentDescription = "Target file: ${entry.name}" } else Modifier)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = "Options for ${entry.name}"),
        ) {
            Column {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val shaped = remember(image, aspect) { com.deepak.videolibrary.artwork.ArtworkRepository.forShape(image, aspect < 1f) }
                    if (shaped != null) {
                        FileImage(shaped, Modifier.fillMaxSize())
                    } else {
                        Icon(
                            when {
                                entry.isDirectory -> Icons.Filled.Folder
                                playable -> Icons.Filled.Movie
                                else -> Icons.Filled.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (showFolder) {
                                hit.path.substringBeforeLast('/', "/")
                            } else if (entry.isDirectory) {
                                "Folder"
                            } else {
                                android.text.format.Formatter.formatShortFileSize(LocalContext.current, entry.size)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        FileActionsMenu(
            menu,
            { menu = false },
            vm,
            hit.path,
            directory = entry.isDirectory,
            onSelect = if (entry.isDirectory) ({ vm.toggleSelected(hit.path, "explorer", directory = true) }) else null,
            selectionScope = "explorer",
            onPlay = if (playable) onOpen else null,
            details = "${entry.name}\n${hit.path}\n${entry.size} bytes",
            onChangeCover = if (indexed != null && indexed.id > 0 && artworkOn) ({ changeCover = true }) else null,
        )
    }
    if (changeCover && indexed != null) {
        ChangeCoverDialog(vm, CoverTarget(com.deepak.videolibrary.artwork.ArtworkKeys.video(indexed.id), indexed.name, indexed.name, indexed), null) { changeCover = false }
    }
}
