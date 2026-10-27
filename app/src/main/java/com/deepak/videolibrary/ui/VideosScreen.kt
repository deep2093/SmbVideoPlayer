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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.runtime.produceState
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
    // Each folder page (and the all-videos page) has its own card shape.
    val page = when {
        group != null -> Pages.virtualFolder(group!!)
        matchFolder != null && matchFolder!!.id > 0 -> Pages.customFolder(matchFolder!!.id)
        matchFolder != null -> Pages.drivePick(matchFolder!!.sourcePath.orEmpty())
        else -> Pages.VIDEOS
    }
    val aspect = cardAspect(vm, page)
    var searchExpanded by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    val total by vm.videoCount.collectAsStateWithLifecycle()
    val count by vm.videoResultsCount.collectAsStateWithLifecycle()
    val continueWatching by vm.continuing.collectAsStateWithLifecycle()
    var addDialog by remember { mutableStateOf(false) }
    var videoSearch by remember { mutableStateOf("") }
    // Scroll to top only when the filter changes while visible; returning from the player or
    // another tab keeps the previous position.
    ScrollToTopOnChange(Triple(query, group, matchFolder?.id), gridState)
    // The whole top area slides away while scrolling down and comes back on any scroll up.
    val header = rememberCollapsingHeader()
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN).nestedScroll(header.connection)) {
        CollapsingHeader(header, resetKey = page) {
        if (vm.selectedPaths.isNotEmpty() && vm.selectionScope == "videos") {
            VideoSelectionHeader(vm, Modifier.padding(top = 8.dp))
        } else if (vm.selectedPaths.isNotEmpty() && vm.selectionScope == CONTINUE_SCOPE) {
            ContinueSelectionHeader(vm, continueWatching.size, Modifier.padding(top = 8.dp))
        } else SearchableTopBar(
            title = selectedGroup ?: "Videos",
            searching = searchExpanded,
            onSearchingChange = { searchExpanded = it },
            query = query,
            onQueryChange = { vm.query.value = it },
            placeholder = stringResource(R.string.ui_search_videos_by_filename_1c7bc42),
            modifier = Modifier.padding(top = 8.dp),
            navigation = if (selectedGroup != null) {
                {
                    IconButton(onClick = {
                        // Going back to Folders clears the folder after the slide (see AppRoot), not now.
                        if (onBackToFolders != null) {
                            onBackToFolders()
                        } else {
                            vm.groupFilter.value = null
                            vm.matchFolderFilter.value = null
                        }
                    }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to folders") }
                }
            } else {
                null
            },
        ) {
            if (selectedGroup != null && matchFolder == null) {
                IconAction(Icons.Filled.Add, "Add videos to this folder", onClick = {
                    videoSearch = ""
                    vm.findVideosToAdd(selectedGroup, "")
                    addDialog = true
                })
            }
            RefreshLibraryAction(vm)
            IconAction(Icons.Filled.PlayArrow, stringResource(R.string.ui_play_all_ebb2ffd), enabled = count > 0, onClick = { vm.playLibrary("videos", onPlay = onPlay) })
            CardShapeButton(vm, page)
            LibraryDisplayMenu(vm)
        }
        }
        val columns = libraryColumns(vm, folders = false)
        // Grid rows before the first video, so the fast scroller can map a position to a filename.
        val headerRows = (if (group == null && matchFolder == null && query.isEmpty() && continueWatching.isNotEmpty()) 1 else 0) +
            (if (videos.loadState.refresh is androidx.paging.LoadState.Loading) 1 else 0) +
            (if (videos.loadState.refresh is androidx.paging.LoadState.Error) 1 else 0) +
            (if (count == 0 && videos.loadState.refresh is androidx.paging.LoadState.NotLoading) 1 else 0)
        FastScrollBox(
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            labelFor = if (vm.videoSort == VideoSort.NAME) {
                { index ->
                    val at = index - headerRows
                    if (at in 0 until videos.itemCount) videos.peek(at)?.name?.let(::sectionLetter) else null
                }
            } else {
                null
            },
            bottomInset = if (floatingPadding(vm)) 100.dp else 16.dp,
        ) {
            LibraryPullRefresh(vm, Modifier.fillMaxSize()) { LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(if (vm.videoListMode) 1 else columns),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP),
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
                                        selectionScope = CONTINUE_SCOPE,
                                    )
                                }
                            }
                        }
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
                        }, vm = vm, listMode = vm.videoListMode, aspect = aspect)
                    }
                }
                // The total sits at the end of the list.
                if (count > 0) item(span = { GridItemSpan(maxLineSpan) }, key = "count") { ListTotal("$count videos · ${vm.videoSort.label}") }
            } }
        }
    }

    if (addDialog && selectedGroup != null) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Add videos to $group") },
            text = {
                Column {
                    PillTextField(
                        value = videoSearch,
                        onValueChange = {
                            videoSearch = it
                            vm.findVideosToAdd(selectedGroup, it)
                        },
                        label = stringResource(R.string.ui_search_filenames_50f3cf4),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
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
    insideVirtualGeneratedFolder: Boolean = false,
    /** Card shape of the page: 16:9 (default) or 3:4. */
    aspect: Float = com.deepak.videolibrary.artwork.CoverCrop.CARD_ASPECT,
) {
    var menuOpen by remember(video.relativePath) { mutableStateOf(false) }
    // Selections are kept apart (Continue watching, the video list, a folder…): a card only ticks while its own
    // list is being selected.
    val selectingHere = vm.selectedPaths.isNotEmpty() && vm.selectionScope == selectionScope
    val context = LocalContext.current
    // Custom thumbnail first, then the automatic one; refreshed as soon as a custom thumbnail changes.
    val customRevision by com.deepak.videolibrary.thumbs.CustomThumbs.revision.collectAsStateWithLifecycle()
    val artRevision by vm.artworkRevision.collectAsStateWithLifecycle()
    val metadataRevision by vm.metadataPosterRevision.collectAsStateWithLifecycle()
    val metadataSettings by vm.metadataSettings.collectAsStateWithLifecycle()
    val artworkOn = rememberArtworkEnabled(vm)
    val tall = aspect < 1f
    val thumb: File? = remember(video.id, video.thumbStatus, video.customGroup, video.groupKey, customRevision, artRevision, metadataRevision, metadataSettings, tall, insideVirtualGeneratedFolder) {
        com.deepak.videolibrary.artwork.ArtworkRepository.forShape(vm.videoArtwork(video, insideVirtualGeneratedFolder), tall)
    }
    var pickThumbnail by remember(video.relativePath) { mutableStateOf(false) }
    var changeCover by remember(video.relativePath) { mutableStateOf(false) }
    val namespace by vm.namespace.collectAsStateWithLifecycle()
    val progressFlow = remember(video.relativePath, namespace) { vm.progressFlow(video.relativePath) }
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = null)
    val watchedMs = progress?.positionMs ?: 0L
    // Artwork changes must never remove the original video's time label.
    // Unplayed videos have no duration in watch history: probe the SMB container
    // only while their card is visible, off the main thread, and cache the result.
    val probedDuration by produceState<Long?>(null, video.relativePath, video.size, video.modified, progress?.durationMs) {
        value = progress?.durationMs?.takeIf { it > 0L }
            ?: com.deepak.videolibrary.metadata.SceneDurationLookup.durationMs(
                com.deepak.videolibrary.ServiceLocator.get(context), video
            )
    }
    val totalMs = progress?.durationMs?.takeIf { it > 0L } ?: probedDuration ?: 0L
    val artwork: @Composable () -> Unit = {
        Box(
            if (listMode) {
                Modifier.width(128.dp).height(if (tall) 170.dp else 80.dp)
            } else {
                Modifier.fillMaxWidth().aspectRatio(aspect)
            },
        ) {
            FileImage(thumb, Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)))
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
            if (onRemoveFromContinue != null && !selectingHere) {
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
            if (selectingHere) {
                Checkbox(checked = video.relativePath in vm.selectedPaths, onCheckedChange = {
                    vm.toggleSelected(video.relativePath, selectionScope)
                })
            }
            // Compact caption: the name on one line and the size, in small type, so the picture gets the room.
            // The bottom padding keeps the size clear of the card's rounded corner.
            Column(Modifier.weight(1f).padding(start = if (listMode) 12.dp else 4.dp, end = 4.dp, top = if (listMode) 0.dp else 5.dp, bottom = if (listMode) 0.dp else 6.dp)) {
                Text(
                    video.name,
                    style = CardTitleStyle(),
                    maxLines = if (listMode) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Formatter.formatShortFileSize(context, video.size),
                    style = CardDetailStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = {
                if (selectingHere) vm.toggleSelected(video.relativePath, selectionScope) else onClick()
            }, onLongClick = {
                // Holding a video opens its menu, the same as folders (Select is in the menu). While selecting,
                // holding ticks it like a tap.
                if (selectingHere) {
                    vm.toggleSelected(video.relativePath, selectionScope)
                } else {
                    menuOpen = true
                }
            }, onLongClickLabel = "Options for ${video.name}"),
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
            onChangeThumbnail = if (video.id > 0) ({ pickThumbnail = true }) else null,
            onChangeCover = if (video.id > 0) ({ changeCover = true }) else null,
        )
    }
    if (pickThumbnail) ThumbnailPicker(vm, video, onDismiss = { pickThumbnail = false })
    if (changeCover) {
        ChangeCoverDialog(
            vm,
            CoverTarget(com.deepak.videolibrary.artwork.ArtworkKeys.video(video.id), video.name, video.name, video),
            onPickFrame = { pickThumbnail = true },
            onDismiss = { changeCover = false },
        )
    }
}

@Composable
internal fun LibraryDisplayMenu(vm: LibraryViewModel) {
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

/**
 * Header shown while videos are selected (hold a video to start): close, the count, then icon-only
 * Select all, Unselect all and Delete. Cut, Copy, Paste and Merge sit in the overflow menu.
 */
@Composable
private fun VideoSelectionHeader(vm: LibraryViewModel, modifier: Modifier = Modifier) {
    var confirmDelete by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf<String?>(null) }
    val artworkOn = rememberArtworkEnabled(vm)
    var more by remember { mutableStateOf(false) }
    var merge by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val count = vm.selectedPaths.size
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, contentDescription = "Done") }
        Text(
            "$count selected",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        val results by vm.videoResultsCount.collectAsStateWithLifecycle()
        SelectAllToggle(allSelected = results in 1..count, onSelectAll = { vm.selectAll("videos") }, onDeselectAll = vm::clearSelection)
        IconButton(onClick = {
            vm.downloadFiles(vm.selectedPaths)
            vm.clearSelection()
        }) { Icon(Icons.Filled.Download, contentDescription = "Download selected") }
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete selected") }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More selection actions") }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_cut_38d13bd)) }, onClick = {
                    more = false
                    vm.stageSelected(true)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_copy_af74f7c)) }, onClick = {
                    more = false
                    vm.stageSelected(false)
                })
                if (vm.explorerClipboard != null) DropdownMenuItem(text = { Text(stringResource(R.string.ui_paste_9db2f69)) }, onClick = {
                    more = false
                    vm.pasteDestination = vm.settings.rootPath
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_merge_into_56f0e1a)) }, onClick = {
                    more = false
                    name = ""
                    merge = true
                })
                if (artworkOn) {
                    DropdownMenuItem(text = { Text("Generate frames") }, onClick = { more = false; bulk = "frames" })
                    DropdownMenuItem(text = { Text("Apply default cover") }, onClick = { more = false; bulk = "default" })
                    DropdownMenuItem(text = { Text("Reset covers") }, onClick = { more = false; bulk = "reset" })
                }
            }
        }
    }
    androidx.activity.compose.BackHandler { vm.clearSelection() }
    bulk?.let { action ->
        val what = when (action) {
            "frames" -> "Generate new automatic frames for $count ${if (count == 1) "video" else "videos"}? This reads each video from your server again and may use network data."
            "default" -> "Remove the cover overrides of $count ${if (count == 1) "video" else "videos"} so they follow the default? Hand-picked frames are kept."
            else -> "Reset the covers and hand-picked frames of $count ${if (count == 1) "video" else "videos"}? Source files are not changed."
        }
        AlertDialog(
            onDismissRequest = { bulk = null },
            title = { Text("Artwork") },
            text = { Text(what) },
            confirmButton = {
                TextButton(onClick = {
                    val paths = vm.selectedPaths.toList()
                    bulk = null
                    when (action) {
                        "frames" -> vm.bulkGenerateFrames(paths)
                        "default" -> vm.bulkApplyDefault(paths)
                        else -> vm.bulkResetOverrides(paths)
                    }
                    vm.clearSelection()
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { bulk = null }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $count ${if (count == 1) "video" else "videos"}?") },
            text = { Text("The selected files are deleted from your server. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelected()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.ui_cancel_77dfd21)) } },
        )
    }
    if (merge) {
        AlertDialog(
            onDismissRequest = { merge = false },
            title = { Text(stringResource(R.string.ui_merge_selected_videos_e03e58e)) },
            text = {
                Column {
                    Text(stringResource(R.string.ui_assign_selected_videos_to_an_existing_or_n_b820db7))
                    PillTextField(name, { name = it }, label = stringResource(R.string.ui_destination_virtual_folder_fda9d5d), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.mergeSelected(name)
                    merge = false
                }) { Text(stringResource(R.string.ui_merge_ea8f0d0)) }
            },
            dismissButton = { TextButton(onClick = { merge = false }) { Text(stringResource(R.string.ui_cancel_77dfd21)) } },
        )
    }
}


/** Selection scope of the Continue watching row, kept apart from the video list. */
internal const val CONTINUE_SCOPE = "continue"

/** While Continue watching items are selected: Select all (that row only) and Remove from Continue watching. */
@Composable
private fun ContinueSelectionHeader(vm: LibraryViewModel, total: Int, modifier: Modifier = Modifier) {
    val count = vm.selectedPaths.size
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, contentDescription = "Done") }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
        SelectAllToggle(allSelected = total in 1..count, onSelectAll = { vm.selectAll(CONTINUE_SCOPE) }, onDeselectAll = vm::clearSelection)
        IconAction(Icons.Filled.RemoveCircleOutline, "Remove from Continue watching", onClick = {
            vm.selectedPaths.forEach { vm.hideContinue(it) }
            vm.clearSelection()
        })
    }
}
