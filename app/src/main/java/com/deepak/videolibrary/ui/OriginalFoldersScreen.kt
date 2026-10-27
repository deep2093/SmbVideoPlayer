package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.deepak.videolibrary.data.DirectorySummary
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.scan.ScanMode

@Composable
fun OriginalFoldersScreen(vm: LibraryViewModel, onPlay: (VideoEntity, List<VideoEntity>) -> Unit, active: Boolean = true) {
    val listing by vm.originalListing.collectAsStateWithLifecycle()
    val roots by vm.originalRoots.collectAsStateWithLifecycle()
    val root = vm.settings.rootPath.trim('/')
    // Only the top and folders inside the ones you added can be open; anything else goes back to the top.
    val path = vm.originalPath.takeIf { vm.originalPathAllowed(it, roots) } ?: root
    val query = vm.originalQuery
    val videos = remember(path,query,vm.videoSort,vm.sortDescending,roots) { vm.originalPagesFor(path,query,roots) }.collectAsLazyPagingItems()
    var adding by remember { mutableStateOf(false) }
    val ready = listing.ready && listing.path == path && listing.query == query
    val children = if(ready) listing.rows else emptyList()
    val count = if(ready) listing.totalCount else 0
    var searchExpanded by remember { mutableStateOf(query.isNotBlank()) }
    // One scroll position per folder level: going up restores where you were, entering starts at the top.
    val gridState = remember(path) { LazyGridState(vm.originalScroll[path]?.first ?: 0, vm.originalScroll[path]?.second ?: 0) }
    DisposableEffect(path, gridState) {
        onDispose {
            vm.originalScroll[path] =
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }
    }
    ScrollToTopOnChange(query, gridState)
    val aspect = cardAspect(vm, Pages.original(path))
    LaunchedEffect(active) {
        if (!active && searchExpanded) {
            vm.originalQuery = ""
            searchExpanded = false
        }
    }
    fun up() {
        vm.originalQuery = ""
        vm.originalPath = vm.originalParent(path)
    }
    BackHandler(enabled = query.isNotEmpty() || path != root) { if (query.isNotEmpty())vm.originalQuery = "" else up() }
    val selectPage = SelectionPages.ORIGINAL
    val selection = vm.ui.selectionOn(selectPage)
    BackHandler(enabled = selection != null) { vm.ui.endSelection(selectPage) }
    LaunchedEffect(active, path) { if (!active) vm.ui.endSelection(selectPage) }
    LaunchedEffect(path) { vm.ui.endSelection(selectPage) }
    ScrollTopOnRequest(vm, gridState, active)
    // Inside a sub-folder the page looks like an opened Custom or Virtual folder: ← and the folder's name at the
    // top (the app-name line and tabs step aside), sliding away while scrolling down.
    val inside = path != root
    val header = rememberCollapsingHeader()
    val bar: @Composable () -> Unit = {
        if (selection != null) {
            FolderSelectionBar(
                count = selection.keys.size,
                total = children.size,
                onClose = { vm.ui.endSelection(selectPage) },
                onSelectAll = { vm.ui.selectAllFolders(selectPage, children.map { it.path }) },
                onDownload = {
                    selection.keys.forEach(vm::downloadDirectory)
                    vm.ui.endSelection(selectPage)
                },
                // At the top the cards are the folders you added: they are taken out of this list, not hidden.
                onHide = if (inside) ({
                    selection.keys.forEach { vm.hidePath(it, true) }
                    vm.ui.endSelection(selectPage)
                }) else null,
                onRemove = if (inside) null else ({
                    vm.removeOriginalRoots(selection.keys)
                    vm.ui.endSelection(selectPage)
                }),
                removeLabel = "Remove from Original folders",
            )
        } else
        SearchableTopBar(
            title = if (inside) vm.originalDisplayName(path) else vm.sectionName(2),
            navigation = if (inside) {
                { IconButton(onClick = ::up) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            } else {
                null
            },
            searching = searchExpanded,
            onSearchingChange = { searchExpanded = it },
            query = query,
            onQueryChange = { vm.originalQuery = it },
            placeholder = stringResource(R.string.ui_search_folder_and_video_names_b2eeabc),
        ) {
            if (!inside) IconAction(Icons.Filled.CreateNewFolder, "Add folders", onClick = { adding = true })
            IconAction(Icons.Filled.PlayArrow, stringResource(R.string.ui_play_all_ebb2ffd), enabled = count > 0 || listing.totalCount > 0, onClick = { vm.playLibrary("originals", onPlay = onPlay) })
            CardShapeButton(vm, Pages.original(path))
            IconAction(Icons.Filled.Refresh, stringResource(R.string.ui_refresh_index_2b7da4d), onClick = { vm.startScan(ScanMode.REFRESH) })
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN).then(if (inside) Modifier.nestedScroll(header.connection) else Modifier)) {
        if (inside) {
            CollapsingHeader(header, resetKey = path) { bar() }
        } else {
            HubTopBar(bar)
        }
        FastScrollBox(
            state = gridState,
            modifier = Modifier.weight(1f),
            labelFor = if (vm.videoSort == VideoSort.NAME) {
                { index ->
                    // Sub-folders first, then the videos of this folder.
                    when {
                        index < children.size -> sectionLetter(vm.originalDisplayName(children[index].path))
                        index - children.size < videos.itemCount -> videos.peek(index - children.size)?.name?.let(::sectionLetter)
                        else -> null
                    }
                }
            } else {
                null
            },
            bottomInset = if (floatingPadding(vm)) 100.dp else 16.dp,
        ) {
        FolderTransition(path, Modifier.fillMaxSize()) {
        if(videos.loadState.refresh is androidx.paging.LoadState.Error) {
            Column(Modifier.fillMaxSize(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                Text(stringResource(R.string.ui_could_not_load_this_folder_567d2b5))
                TextButton(onClick = { videos.retry() }) { Text(stringResource(R.string.ui_retry_9f5cd8a)) }
            }
        } else if(!ready || videos.itemCount != listing.videoCount || videos.loadState.refresh is androidx.paging.LoadState.Loading) {
            Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) { CircularProgressIndicator() }
        } else LibraryPullRefresh(vm, Modifier.fillMaxSize()) { LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(if (vm.folderListMode)1 else libraryColumns(vm, folders = true)),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP),
            contentPadding = PaddingValues(bottom = if (floatingPadding(vm))112.dp else 24.dp),
            modifier = Modifier.fillMaxSize().testTag("original-folder-grid"),
        ) {
            if (count == 0 &&
                children.isEmpty()
            ) {
                item(span = {
                    GridItemSpan(maxLineSpan)
                }) {
                    if (!inside && query.isBlank() && roots.isEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Add the drive folders you want here. Their sub-folders show exactly as they are on the drive.")
                            Button(onClick = { adding = true }) { Text("Add folders") }
                        }
                    } else Text(
                        if (query.isBlank())"No visible indexed videos here. Pull down to refresh the library, or check Hidden items in Settings." else "No matching visible folders or videos.",
                    )
                }
            }
            items(children, key = { "folder:${it.path}" }) { folder ->
                OriginalFolderCard(vm, folder, aspect, vm.ui.tick(selectPage, folder.path)) {
                    if (vm.ui.selectionOn(selectPage) != null) {
                        vm.ui.toggleFolder(selectPage, folder.path)
                        return@OriginalFolderCard
                    }
                    vm.originalScroll.remove(folder.path)
                    vm.originalQuery =
                        ""
                    vm.originalPath = folder.path
                }
            }
            items(count = videos.itemCount, key = videos.itemKey { "video:${it.relativePath}" }) { index ->
                val video = videos[index]
                if(video != null) {
                    VideoCard(video, onClick = {
                        vm.playLibrary("originals", video, onPlay)
                    }, vm = vm, listMode = vm.folderListMode, selectionScope = "originals", aspect = aspect)
                } else {
                    if(vm.folderListMode) Box(Modifier.fillMaxWidth().height(68.dp))
                    else Column { Box(Modifier.fillMaxWidth().aspectRatio(aspect)); Text("", maxLines=1, style=CardTitleStyle()); Text("",style=CardDetailStyle()) }
                }
            }
            // The totals sit at the end of the list.
            if (count > 0 || children.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                ListTotal(buildString {
                    if (children.isNotEmpty()) append("${children.size} ${if (children.size == 1) "folder" else "folders"} · ")
                    append("$count videos including subfolders")
                })
            }
        }
        }
        }
        }
    }
    if (adding) {
        DrivePicker(
            vm = vm,
            title = "Original folders",
            explanation = "Tick the drive folders to list here. Inside each one, its sub-folders show exactly as they are on the drive. Untick to take a folder out. Nothing on your drive changes.",
            confirmLabel = "Done",
            withFiles = false,
            initialFolders = roots.toSet(),
            marked = roots.toSet(),
            onDismiss = { adding = false },
        ) { folders, _ ->
            vm.setOriginalRoots(folders)
            adding = false
        }
    }
}

@Composable
private fun OriginalFolderCard(vm: LibraryViewModel, folder: DirectorySummary, aspect: Float, selected: Boolean?, onClick: () -> Unit) {
    val key = vm.coverKey("original:${folder.path}", folder.count, folder.thumbsDone)
    // Keeps showing the current cover while a new one is prepared, so cards never blink empty.
    var cover by remember { mutableStateOf(vm.cachedCover(key)) }
    var menu by remember(folder.path) { mutableStateOf(false) }
    var changeCover by remember(folder.path) { mutableStateOf(false) }
    var rename by remember(folder.path) { mutableStateOf(false) }
    val artworkOn = rememberArtworkEnabled(vm)
    val hidden by vm.hiddenItems.collectAsStateWithLifecycle()
    LaunchedEffect(key, hidden) { cover = vm.originalCover(folder.path, key) }
    val title = vm.originalDisplayName(folder.path)
    Box {
        FolderTile(
            title = title,
            detail = "${folder.count} items",
            cover = cover,
            aspect = aspect,
            listMode = vm.folderListMode,
            onClick = onClick,
            onLongPress = { if (selected != null) { onClick() } else { menu = true } },
            onRename = { rename = true },
            selected = selected,
        )
        FileActionsMenu(menu, {
            menu = false
        }, vm, folder.path, directory = true, details = "${folder.path}\n${folder.count} visible indexed videos\nOriginal SMB folder",
            onChangeCover = if (artworkOn) ({ changeCover = true }) else null,
            onSelect = { vm.ui.toggleFolder(SelectionPages.ORIGINAL, folder.path) })
        if (changeCover) {
            ChangeCoverDialog(vm, CoverTarget(com.deepak.videolibrary.artwork.ArtworkKeys.originalFolder(folder.path), title, folder.path.substringAfterLast('/'), null), null) { changeCover = false }
        }
    }
    if (rename) {
        RenameDialog("Rename folder", title, "Only the name shown in this app changes; the folder on your drive keeps its name. To rename it on the drive, use Rename in the folder's menu.", { rename = false }) {
            vm.renameOriginalDisplay(folder.path, it)
        }
    }
}
