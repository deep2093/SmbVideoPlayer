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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
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
    val root = vm.settings.rootPath.trim('/')
    val path = vm.originalPath.takeIf { isWithinFolder(it, root) } ?: root
    val query = vm.originalQuery
    val videos = remember(path,query,vm.videoSort,vm.sortDescending) { vm.originalPagesFor(path,query) }.collectAsLazyPagingItems()
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
        vm.originalPath = path.substringBeforeLast('/', "").takeIf { isWithinFolder(it, root) } ?: root
    }
    BackHandler(enabled = query.isNotEmpty() || path != root) { if (query.isNotEmpty())vm.originalQuery = "" else up() }
    val selectPage = SelectionPages.ORIGINAL
    val selection = vm.ui.selectionOn(selectPage)
    BackHandler(enabled = selection != null) { vm.ui.endSelection(selectPage) }
    LaunchedEffect(active, path) { if (!active) vm.ui.endSelection(selectPage) }
    LaunchedEffect(path) { vm.ui.endSelection(selectPage) }
    ScrollTopOnRequest(vm, gridState, active)
    val header = rememberCollapsingHeader()
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN).nestedScroll(header.connection)) {
        HubTopBar {
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
                onHide = {
                    selection.keys.forEach { vm.hidePath(it, true) }
                    vm.ui.endSelection(selectPage)
                },
                onRemove = null,
            )
        } else
        SearchableTopBar(
            title = vm.sectionName(2),
            searching = searchExpanded,
            onSearchingChange = { searchExpanded = it },
            query = query,
            onQueryChange = { vm.originalQuery = it },
            placeholder = stringResource(R.string.ui_search_folder_and_video_names_b2eeabc),
        ) {
            CardShapeButton(vm, Pages.original(path))
            IconAction(Icons.Filled.Refresh, stringResource(R.string.ui_refresh_index_2b7da4d), onClick = { vm.startScan(ScanMode.REFRESH) })
        }
        }
        // The path line and the count slide away while scrolling down and come back on any scroll up.
        CollapsingHeader(header, resetKey = path) {
        // At the top level the path line only said "Share root"; it appears once you are inside a folder.
        if (path != root) BreadcrumbBar(
            crumbs = breadcrumbs(path, root),
            onHome = {
                vm.originalQuery = ""
                vm.originalPath = root
            },
            onSelect = {
                vm.originalQuery = ""
                vm.originalPath = it
            },
            rootLabel = if (root.isEmpty()) "Share root" else root.substringAfterLast('/'),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$count videos including subfolders", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconAction(Icons.Filled.PlayArrow, stringResource(R.string.ui_play_all_ebb2ffd), enabled = count > 0, onClick = { vm.playLibrary("originals", onPlay = onPlay) })
        }
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
        } else LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(if (vm.folderListMode)1 else libraryColumns(vm.folderGridPreset, floatingPadding(vm))),
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
                    Text(
                        if (query.isBlank())"No visible indexed videos here. Scan your library or check Hidden items in Settings." else "No matching visible folders or videos.",
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
                    else Column { Box(Modifier.fillMaxWidth().aspectRatio(aspect)); Text("", minLines=2, maxLines=2, style=MaterialTheme.typography.bodyMedium); Text("",style=MaterialTheme.typography.labelSmall) }
                }
            }
        }
        }
        }
    }
}

@Composable
private fun OriginalFolderCard(vm: LibraryViewModel, folder: DirectorySummary, aspect: Float, selected: Boolean?, onClick: () -> Unit) {
    val key = vm.coverKey("original:${folder.path}", folder.count, folder.thumbsDone)
    var cover by remember(key) { mutableStateOf(vm.cachedCover(key)) }
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
