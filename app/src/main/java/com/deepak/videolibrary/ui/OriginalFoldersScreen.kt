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
fun OriginalFoldersScreen(vm: LibraryViewModel, onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
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
    fun up() {
        vm.originalQuery = ""
        vm.originalPath = path.substringBeforeLast('/', "").takeIf { isWithinFolder(it, root) } ?: root
    }
    BackHandler(enabled = query.isNotEmpty() || path != root) { if (query.isNotEmpty())vm.originalQuery = "" else up() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SearchableTopBar(
            title = stringResource(R.string.ui_original_folders_38a7a75),
            searching = searchExpanded,
            onSearchingChange = { searchExpanded = it },
            query = query,
            onQueryChange = { vm.originalQuery = it },
            placeholder = stringResource(R.string.ui_search_folder_and_video_names_b2eeabc),
        ) {
            TextButton(onClick = { vm.startScan(ScanMode.REFRESH) }) { Text(stringResource(R.string.ui_refresh_index_2b7da4d)) }
        }
        BreadcrumbBar(
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
            Text("$count videos including subfolders", Modifier.weight(1f))
            TextButton(
                enabled =
                    count > 0,
                onClick = { vm.playLibrary("originals", onPlay = onPlay) },
            ) { Text(stringResource(R.string.ui_play_all_ebb2ffd)) }
        }
        FolderTransition(path, Modifier.weight(1f)) {
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
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
                OriginalFolderCard(vm, folder) {
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
                    }, vm = vm, listMode = vm.folderListMode, selectionScope = "originals")
                } else {
                    if(vm.folderListMode) Box(Modifier.fillMaxWidth().height(68.dp))
                    else Column { Box(Modifier.fillMaxWidth().aspectRatio(16f/9f)); Text("", minLines=2, maxLines=2, style=MaterialTheme.typography.bodyMedium); Text("",style=MaterialTheme.typography.labelSmall) }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OriginalFolderCard(vm: LibraryViewModel, folder: DirectorySummary, onClick: () -> Unit) {
    val key = vm.coverKey("original:${folder.path}", folder.count, folder.thumbsDone)
    var cover by remember(key) { mutableStateOf(vm.cachedCover(key)) }
    var menu by remember(folder.path) { mutableStateOf(false) }
    val hidden by vm.hiddenItems.collectAsStateWithLifecycle()
    LaunchedEffect(key, hidden) { cover = vm.originalCover(folder.path, key) }
    Box {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                    if (cover !=
                        null
                    ) {
                        FileImage(cover, Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Filled.Folder, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.padding(12.dp)) {
                    Text(originalFolderLabel(folder.path), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${folder.count} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FileActionsMenu(menu, {
            menu = false
        }, vm, folder.path, directory = true, details = "${folder.path}\n${folder.count} visible indexed videos\nOriginal SMB folder")
    }
}
