package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.thumbs.ThumbStore
import java.io.File

@Composable
fun VideosScreen(
    vm: LibraryViewModel,
    gridState: LazyGridState,
    onPlay: (VideoEntity, List<VideoEntity>) -> Unit,
    onBackToFolders: (() -> Unit)? = null,
) {
    val videos = vm.videoPages.collectAsLazyPagingItems()
    val query by vm.query.collectAsStateWithLifecycle()
    val group by vm.groupFilter.collectAsStateWithLifecycle()
    val matchFolder by vm.matchFolderFilter.collectAsStateWithLifecycle()
    val selectedGroup = group ?: matchFolder?.name
    var searchExpanded by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    val total by vm.videoCount.collectAsStateWithLifecycle()
    val count by vm.videoResultsCount.collectAsStateWithLifecycle()
    val continueWatching by vm.continuing.collectAsStateWithLifecycle()
    var addDialog by remember { mutableStateOf(false) }
    var videoSearch by remember { mutableStateOf("") }
    // Scroll to top only when the filter changes while visible; returning from the player or
    // another tab keeps the previous position.
    ScrollToTopOnChange(Triple(query, group, matchFolder?.id), gridState)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (selectedGroup != null) {
                IconButton(onClick = {
                    if (onBackToFolders != null) onBackToFolders() else vm.groupFilter.value = null
                    vm.matchFolderFilter.value = null
                }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to folders") }
            }
            Text(
                selectedGroup ?: "Videos",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LibraryDisplayMenu(vm)
            IconButton(onClick = {
                searchExpanded = !searchExpanded
                if (!searchExpanded) vm.query.value = ""
            }) {
                Icon(
                    if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchExpanded) "Close search" else "Search videos",
                )
            }
        }
        if (searchExpanded) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                label = { Text(stringResource(R.string.ui_search_videos_by_filename_1c7bc42)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        if (selectedGroup != null && matchFolder == null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_virtual_folder_2723d68),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    videoSearch = ""
                    vm.findVideosToAdd(selectedGroup, "")
                    addDialog = true
                }) { Text(stringResource(R.string.ui_add_109b936)) }
            }
        }
        val columns = libraryColumns(vm.videoGridPreset, floatingPadding(vm))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(if (vm.videoListMode) 1 else columns),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = if (floatingPadding(vm))112.dp else 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (group == null && matchFolder == null && query.isEmpty() && continueWatching.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        Column {
                            Text(stringResource(R.string.ui_continue_watching_8fca30f),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(continueWatching, key = { it.id }) { video ->
                                    VideoCard(
                                        video,
                                        onClick = { onPlay(video, listOf(video)) },
                                        vm = vm,
                                        modifier = Modifier.width(280.dp),
                                        onRemoveFromContinue = {
                                            vm.hideContinue(video.relativePath)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "$count videos · ${vm.videoSort.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.playLibrary("videos", onPlay = onPlay) }, enabled = count > 0) { Text(stringResource(R.string.ui_play_all_ebb2ffd)) }
                    }
                }
                if (videos.loadState.refresh is androidx.paging.LoadState.Loading) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                        Row(Modifier.padding(vertical = 24.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                            Text("Loading videos…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (videos.loadState.refresh is androidx.paging.LoadState.Error) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "retry") {
                        Column(Modifier.padding(vertical = 20.dp)) {
                            Text("Couldn’t load videos. Try loading your library again.")
                            TextButton(onClick = { videos.retry() }) { Text("Retry") }
                        }
                    }
                }
                if (count == 0 && videos.loadState.refresh is androidx.paging.LoadState.NotLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (total == 0) {
                                "No videos yet. Add your server in Settings, then run a scan."
                            } else {
                                "No videos match your search."
                            },
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(count = videos.itemCount, key = videos.itemKey { it.relativePath }, contentType = { "video" }) { index ->
                    videos[index]?.let { video ->
                        VideoCard(video, onClick = {
                            if (group != null ||
                                matchFolder != null ||
                                query.isNotBlank()
                            ) {
                                vm.playLibrary("videos", video, onPlay)
                            } else {
                                onPlay(video, listOf(video))
                            }
                        }, vm = vm, listMode = vm.videoListMode)
                    }
                }
            }
        }
    }

    if (addDialog && selectedGroup != null) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Add videos to $group") },
            text = {
                Column {
                    OutlinedTextField(value = videoSearch, onValueChange = {
                        videoSearch = it
                        vm.findVideosToAdd(selectedGroup, it)
                    }, label = { Text(stringResource(R.string.ui_search_filenames_50f3cf4)) })
                    LazyColumn(Modifier.heightIn(max = 350.dp)) {
                        items(vm.assignCandidates, key = { it.id }) { video ->
                            TextButton(onClick = {
                                vm.assignVideo(video.id, selectedGroup)
                                vm.findVideosToAdd(selectedGroup, videoSearch)
                            }) {
                                Text(video.name, maxLines = 2)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addDialog = false }) { Text(stringResource(R.string.ui_done_e9b450d)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoCard(
    video: VideoEntity,
    onClick: () -> Unit,
    vm: LibraryViewModel,
    modifier: Modifier = Modifier,
    onRemoveFromContinue: (() -> Unit)? = null,
    listMode: Boolean = false,
    selectionScope: String = "videos",
) {
    var menuOpen by remember(video.relativePath) { mutableStateOf(false) }
    val context = LocalContext.current
    val thumb: File? = if (video.thumbStatus == "DONE") ThumbStore.thumbFile(vm.artworkContext, video.id) else null
    val namespace by vm.namespace.collectAsStateWithLifecycle()
    val progressFlow = remember(video.relativePath, namespace) { vm.progressFlow(video.relativePath) }
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = null)
    val watchedMs = progress?.positionMs ?: 0L
    val totalMs = progress?.durationMs ?: 0L
    val artwork: @Composable () -> Unit = {
        Box(
            if (listMode) {
                Modifier.width(128.dp).height(80.dp)
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            },
        ) {
            FileImage(thumb, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
            if (totalMs > 0L) {
                Text(
                    DateUtils.formatElapsedTime(totalMs / 1_000),
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            androidx.compose.ui.graphics
                                .Color(0xBB000000),
                            RoundedCornerShape(3.dp),
                        ).padding(horizontal = 4.dp),
                )
            }
            if (onRemoveFromContinue != null && vm.selectedPaths.isEmpty()) {
                androidx.compose.material3.FilledIconButton(
                    onClick = onClick,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center).width(48.dp).height(48.dp),
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, "Resume ${video.name}") }
            }
            if (totalMs > 0L && watchedMs > 0L) {
                Box(
                    Modifier
                        .align(androidx.compose.ui.Alignment.BottomStart)
                        .fillMaxWidth((watchedMs.toFloat() / totalMs).coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
    val caption: @Composable () -> Unit = {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (vm.selectedPaths.isNotEmpty()) {
                Checkbox(checked = video.relativePath in vm.selectedPaths, onCheckedChange = {
                    vm.toggleSelected(video.relativePath, selectionScope)
                })
            }
            Column(Modifier.weight(1f).padding(start = if (listMode) 12.dp else 0.dp, top = if (listMode) 0.dp else 8.dp)) {
                Text(
                    video.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    minLines = if (listMode) 1 else 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Formatter.formatShortFileSize(context, video.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${video.name}")
            }
        }
    }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = {
                if (vm.selectedPaths.isNotEmpty())vm.toggleSelected(video.relativePath, selectionScope) else onClick()
            }, onLongClick = {
                menuOpen =
                    true
            }),
        ) {
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
        FileActionsMenu(
            menuOpen,
            { menuOpen = false },
            vm,
            video.relativePath,
            onPlay = onClick,
            selectionScope = selectionScope,
            onPlayFromStart = { vm.playFromStart(video.relativePath, onClick) },
            onRemoveContinue = onRemoveFromContinue,
            details = "${video.name}\n${Formatter.formatFileSize(context,video.size)}\n${vm.originalVideoLocation(video.relativePath)}",
        )
    }
}

@Composable
internal fun LibraryDisplayMenu(vm: LibraryViewModel) {
    IconButton(onClick = vm::toggleVideoLayout) {
        Icon(
            if (vm.videoListMode) Icons.Filled.GridView else Icons.Filled.ViewList,
            contentDescription = if (vm.videoListMode) "Show video grid" else "Show video list",
        )
    }
    Box {
        var expanded by remember { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.Sort, contentDescription = "Sort videos") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VideoSort.entries.forEach { sort ->
                DropdownMenuItem(text = {
                    Text(
                        sort.label + if (vm.videoSort == sort) {
                            if (vm.sortDescending) " ↓" else " ↑"
                        } else {
                            ""
                        },
                    )
                }, onClick = {
                    vm.selectVideoSort(sort)
                    expanded = false
                })
            }
        }
    }
}
