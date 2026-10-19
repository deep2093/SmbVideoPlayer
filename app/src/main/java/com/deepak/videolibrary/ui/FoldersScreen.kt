package com.deepak.videolibrary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.deepak.videolibrary.data.FolderSummary
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.scan.ScanMode
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
internal fun FoldersHub(
    vm: LibraryViewModel,
    gridState: LazyGridState,
    customGridState: LazyGridState,
    onOpenGroup: () -> Unit,
    onPlay: (VideoEntity, List<VideoEntity>) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = vm.foldersTab, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.settledPage) { vm.foldersTab = pager.settledPage }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TabRow(
            selectedTabIndex = pager.currentPage,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            listOf("Custom folders", "Virtual folders", "Original folders").forEachIndexed { index, title ->
                androidx.compose.material3.Tab(selected = pager.currentPage == index, onClick = {
                    scope.launch { pager.animateScrollToPage(index) }
                }, text = { Text(title) })
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> MatchingFoldersScreen(vm, customGridState, onOpenGroup, onPlay)
                1 -> FoldersScreen(vm, gridState, onOpenGroup, onPlay, onOpenSettings)
                else -> OriginalFoldersScreen(vm, onPlay)
            }
        }
    }
}

@Composable
fun FoldersScreen(
    vm: LibraryViewModel,
    gridState: LazyGridState,
    onOpenGroup: () -> Unit,
    onPlay: (VideoEntity, List<VideoEntity>) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val shownGroups by vm.groups.collectAsStateWithLifecycle()
    val recentlyWatched by vm.continuing.collectAsStateWithLifecycle()
    var folderQuery by vm::folderSearch
    var searchExpanded by rememberSaveable { mutableStateOf(folderQuery.isNotBlank()) }
    var sourceFolder by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf("") }
    var targetName by remember { mutableStateOf("") }
    var editFolders by rememberSaveable { mutableStateOf(false) }
    val matchingVideos = vm.folderPages.collectAsLazyPagingItems()
    val matchingCount by vm.folderResultsCount.collectAsStateWithLifecycle()
    // Jump to the top only when the search text changes while this screen is visible.
    // Re-entering the screen (e.g. coming back from a folder) must keep the saved position.
    ScrollToTopOnChange(folderQuery, gridState)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Folders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                searchExpanded = !searchExpanded
                if (!searchExpanded) folderQuery = ""
            }) {
                Icon(
                    if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchExpanded) "Close folder search" else "Search folders",
                )
            }
            Box {
                var sortExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort virtual folders")
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    listOf(
                        "NAME" to "Name",
                        "COUNT" to "Number of items",
                        "DATE" to "Latest by date",
                        "SIZE" to "Total size",
                    ).forEach { (sort, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label + if (vm.folderSort == sort) {
                                        if (vm.folderSortDescending) " ↓" else " ↑"
                                    } else {
                                        ""
                                    },
                                )
                            },
                            onClick = {
                                vm.selectFolderSort(sort)
                                sortExpanded = false
                            },
                        )
                    }
                }
            }
            IconButton(onClick = vm::toggleFolderLayout) {
                Icon(
                    if (vm.folderListMode) Icons.Filled.GridView else Icons.Filled.ViewList,
                    contentDescription = if (vm.folderListMode) "Show folder grid" else "Show folder list",
                )
            }
            Box {
                var folderMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { folderMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Folder options") }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (editFolders) "Finish organizing" else "Organize folders") },
                        onClick = {
                            editFolders = !editFolders
                            folderMenu = false
                        },
                    )
                    DropdownMenuItem(text = { Text("Refresh library") }, onClick = {
                        vm.startScan(ScanMode.REFRESH)
                        folderMenu = false
                    })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = {
                        folderMenu = false
                        onOpenSettings()
                    })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (searchExpanded) {
            OutlinedTextField(
                value = folderQuery,
                onValueChange = { folderQuery = it },
                label = { Text("Search folder and video names") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        val columns = LibraryLayout.columns(vm.folderGridPreset)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(if (vm.folderListMode) 1 else columns),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = if (floatingPadding(vm))112.dp else 24.dp),
                modifier = Modifier.fillMaxSize().testTag("virtual-folder-grid"),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                    Column {
                        if (recentlyWatched.isNotEmpty() && folderQuery.isBlank()) {
                            Text(
                                "Continue Watching",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                            )
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(recentlyWatched, key = { it.id }) { video ->
                                    Box(Modifier.width(218.dp)) {
                                        VideoCard(
                                            video,
                                            onClick = { onPlay(video, listOf(video)) },
                                            vm = vm,
                                            onRemoveFromContinue = {
                                                vm.hideContinue(video.relativePath)
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                    Text(
                        "${shownGroups.size} folders · " + when (vm.folderSort) {
                            "COUNT" -> "Number of items"
                            "DATE" -> "Latest by date"
                            "SIZE" -> "Total size"
                            else -> "Name"
                        } + if (vm.folderSortDescending) " ↓" else " ↑",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                if (folderQuery.isNotBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "play-matches") {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("$matchingCount matching videos", modifier = Modifier.weight(1f))
                            TextButton(
                                enabled = matchingCount > 0,
                                onClick = { vm.playLibrary("folders", onPlay = onPlay) },
                            ) { Text("Play all results") }
                        }
                    }
                }
                if (folderQuery.isNotBlank()) {
                    items(count = matchingVideos.itemCount, key = matchingVideos.itemKey { "result:${it.relativePath}" }) { index ->
                        matchingVideos[index]?.let { video ->
                            VideoCard(
                                video,
                                onClick = { vm.playLibrary("folders", video, onPlay) },
                                vm = vm,
                                listMode = vm.folderListMode,
                                selectionScope = "folders",
                            )
                        }
                    }
                }
                if (shownGroups.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (folderQuery.isNotBlank()) "No matching folders." else "Your virtual folders appear after a scan.",
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                gridItems(shownGroups, key = { it.groupKey }, contentType = { "folder" }) { row ->
                    Column {
                        FolderCard(vm, row, listMode = vm.folderListMode) {
                            vm.matchFolderFilter.value = null
                            vm.groupFilter.value = row.groupKey
                            vm.query.value = if (matchesFilename(row.groupKey, folderQuery)) "" else folderQuery
                            onOpenGroup()
                        }
                        var menuExpanded by remember(row.groupKey) { mutableStateOf(false) }
                        if (editFolders) {
                            Box {
                                TextButton(onClick = { menuExpanded = true }) { Text("⋮ Options") }
                                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                    DropdownMenuItem(text = { Text("Rename folder") }, onClick = {
                                        menuExpanded = false
                                        sourceFolder = row.groupKey
                                        operation = "Rename"
                                        targetName = row.groupKey
                                    })
                                    DropdownMenuItem(text = { Text("Merge into…") }, onClick = {
                                        menuExpanded = false
                                        sourceFolder = row.groupKey
                                        operation = "Merge"
                                        targetName = ""
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (sourceFolder != null) {
        AlertDialog(
            onDismissRequest = { sourceFolder = null },
            title = { Text("$operation $sourceFolder") },
            text = {
                Column {
                    Text(
                        if (operation ==
                            "Merge"
                        ) {
                            "All videos assigned to this group will be assigned to the destination virtual folder."
                        } else {
                            "Rename this virtual folder. No SMB files are renamed."
                        },
                    )
                    OutlinedTextField(value = targetName, onValueChange = { targetName = it }, label = {
                        Text(
                            if (operation ==
                                "Merge"
                            ) {
                                "Destination folder"
                            } else {
                                "New name"
                            },
                        )
                    })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val from = sourceFolder!!
                    if (operation == "Merge") vm.mergeFolders(from, targetName.trim()) else vm.renameFolder(from, targetName.trim())
                    sourceFolder = null
                }) { Text(operation) }
            },
            dismissButton = { TextButton(onClick = { sourceFolder = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    vm: LibraryViewModel,
    row: FolderSummary,
    modifier: Modifier = Modifier,
    listMode: Boolean = false,
    onClick: () -> Unit,
) {
    var actions by remember(row.groupKey) { mutableStateOf(false) }
    val key = vm.coverKey("group:${row.groupKey}", row.count, row.thumbsDone, row.lastModified)
    var cover by remember(key) { mutableStateOf(vm.cachedCover(key)) }
    LaunchedEffect(key) { cover = vm.coverFor(row.groupKey, key) }
    val artwork: @Composable () -> Unit = {
        Box(
            (if (listMode)Modifier.width(104.dp).height(68.dp)else Modifier.fillMaxWidth().aspectRatio(16f / 9f)),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            if (cover !=
                null
            ) {
                FileImage(cover, Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Folder, null, modifier = Modifier.height(36.dp).width(36.dp))
            }
        }
    }
    val caption: @Composable () -> Unit = {
        Column(Modifier.padding(6.dp)) {
            Text(row.groupKey.replaceFirstChar { it.titlecase(Locale.ROOT) }, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${row.count} videos", style = MaterialTheme.typography.labelSmall)
        }
    }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { actions = true })) {
            if (listMode) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    artwork()
                    Box(Modifier.weight(1f)) { caption() }
                }
            } else {
                artwork()
                caption()
            }
        }
        FileActionsMenu(actions, {
            actions = false
        }, vm, row.groupKey, directory = true, virtual = true, details = "${row.groupKey}\n${row.count} videos\nVirtual library folder")
    }
}
