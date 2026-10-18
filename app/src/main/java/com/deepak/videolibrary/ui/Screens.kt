package com.deepak.videolibrary.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepak.videolibrary.data.GroupRow
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.scan.ScanMode
import com.deepak.videolibrary.scan.ScanStatus
import com.deepak.videolibrary.settings.SmbSettings
import com.deepak.videolibrary.thumbs.ThumbStore
import java.io.File
import java.util.Locale

private val GRID_PRESETS = LibraryLayout.presets

private val TAB_LABELS = listOf("Folders", "Videos", "Browse", "Scan", "Settings")
private val TAB_ICONS = listOf(Icons.Filled.Folder, Icons.Filled.PlayArrow, Icons.Filled.Storage, Icons.Filled.Sync, Icons.Filled.Settings)


@Composable
fun AppRoot(vm: LibraryViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var folderDetails by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf<VideoEntity?>(null) }
    var playQueue by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    val foldersScrollState = rememberLazyGridState()
    BackHandler(enabled = playing != null || folderDetails || tab != 0) {
        when {
            playing != null -> playing = null
            folderDetails -> { folderDetails = false; vm.groupFilter.value = null; tab = 0 }
            else -> { tab = 0; vm.groupFilter.value = null }
        }
    }
    if (playing != null) {
        PlayerScreen(video = playing!!, settings = vm.settings, playlist = playQueue, onClose = { playing = null; playQueue = emptyList() })
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tabletLayout = maxWidth >= 700.dp
        Scaffold(
            bottomBar = {
                if (!tabletLayout) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TAB_LABELS.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            onClick = {
                                tab = index
                                folderDetails = false
                                vm.groupFilter.value = null
                            },
                            icon = { Icon(TAB_ICONS[index], contentDescription = label) }, label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (tabletLayout) {
                    androidx.compose.material3.NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        TAB_LABELS.forEachIndexed { index, label ->
                            androidx.compose.material3.NavigationRailItem(
                                selected = tab == index,
                                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                onClick = {
                                    tab = index
                                    folderDetails = false
                                    vm.groupFilter.value = null
                                },
                                icon = { Icon(TAB_ICONS[index], contentDescription = label) },
                                label = { Text(label) },
                                alwaysShowLabel = true,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    when (tab) {
                        0 -> FoldersScreen(vm, gridState = foldersScrollState, onOpenGroup = { folderDetails = true; tab = 1 }, onPlay = { video -> playing = video; playQueue = listOf(video) }, onOpenSettings = { tab = 4 })
                        1 -> VideosScreen(vm, onPlay = { video, queue -> playing = video; playQueue = queue }, onBackToFolders = if (folderDetails) ({
                            folderDetails = false; vm.groupFilter.value = null; tab = 0
                        }) else null)
                        2 -> ExplorerScreen(vm, onPlay = { video -> playing = video; playQueue = listOf(video) })
                        3 -> ScanScreen(vm)
                        else -> SettingsScreen(vm)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Videos

@Composable
fun VideosScreen(vm: LibraryViewModel, onPlay: (VideoEntity, List<VideoEntity>) -> Unit, onBackToFolders: (() -> Unit)? = null) {
    val videos by vm.videos.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val group by vm.groupFilter.collectAsStateWithLifecycle()
    val selectedGroup = group
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val total by vm.videoCount.collectAsStateWithLifecycle()
    var addDialog by remember { mutableStateOf(false) }
    var videoSearch by remember { mutableStateOf("") }
    val context = LocalContext.current
    val resumePrefs = remember { context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE) }
    var continueRefresh by remember { mutableIntStateOf(0) }
    val continueWatching = remember(videos, continueRefresh) {
        videos.filter { video ->
            val pos = resumePrefs.getLong("resume_path_${video.relativePath}", resumePrefs.getLong("resume_${video.id}", 0L))
            canContinue(pos, resumePrefs.getLong("duration_path_${video.relativePath}", 0L),
                resumePrefs.getBoolean("hide_continue_${video.relativePath}", false))
        }.take(16)
    }

    val displayedVideos = remember(videos, vm.videoSort, vm.sortDescending) {
        vm.videoSort.arrange(videos, vm.sortDescending)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (selectedGroup != null) IconButton(onClick = {
                if (onBackToFolders != null) onBackToFolders() else vm.groupFilter.value = null
            }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to folders") }
            Text(selectedGroup ?: "Videos", style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            LibraryDisplayMenu(vm)
            IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) vm.query.value = "" }) {
                Icon(if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchExpanded) "Close search" else "Search videos")
            }
        }
        if (searchExpanded) {
            OutlinedTextField(value = query, onValueChange = { vm.query.value = it },
                label = { Text("Search videos by filename") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
        if (selectedGroup != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Virtual folder", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { videoSearch = ""; vm.findVideosToAdd(selectedGroup, ""); addDialog = true }) { Text("+ Add") }
            }
        }
        val columns = LibraryLayout.columns(vm.videoGridPreset)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (vm.videoListMode) 1 else columns),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (group == null && query.isEmpty() && continueWatching.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        Column {
                            Text("Continue watching", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 12.dp))
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(continueWatching, key = { it.id }) { video ->
                                    VideoCard(video, onClick = { onPlay(video, listOf(video)) }, vm = vm,
                                        modifier = Modifier.width(210.dp), onRemoveFromContinue = {
                                            resumePrefs.edit().putBoolean("hide_continue_${video.relativePath}", true).apply()
                                            continueRefresh++
                                        })
                                }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("${videos.size} videos · ${vm.videoSort.label}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { displayedVideos.firstOrNull()?.let { onPlay(it, displayedVideos) } },
                            enabled = displayedVideos.isNotEmpty()) { Text("Play all") }
                    }
                }
                if (videos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (total == 0) "No videos yet. Add your server in Settings, then run a scan."
                        else "No videos match your search.", modifier = Modifier.padding(vertical = 24.dp))
                }
                gridItems(displayedVideos, key = { it.id }) { video ->
                    VideoCard(video, onClick = {
                        onPlay(video, if (group != null) displayedVideos else listOf(video))
                    }, vm = vm, listMode = vm.videoListMode)
                }
            }
        }
    }

    if (addDialog && selectedGroup != null) {
        AlertDialog(onDismissRequest = { addDialog = false }, title = { Text("Add videos to $group") },
            text = { Column {
                OutlinedTextField(value = videoSearch, onValueChange = { videoSearch = it; vm.findVideosToAdd(selectedGroup, it) }, label = { Text("Search filenames") })
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items(vm.assignCandidates, key = { it.id }) { video ->
                        TextButton(onClick = { vm.assignVideo(video.id, selectedGroup); vm.findVideosToAdd(selectedGroup, videoSearch) }) {
                            Text(video.name, maxLines = 2)
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { addDialog = false }) { Text("Done") } })
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoCard(video: VideoEntity, onClick: () -> Unit, vm: LibraryViewModel, modifier: Modifier = Modifier,
    onRemoveFromContinue: (() -> Unit)? = null, listMode: Boolean = false) {
    var menuOpen by remember(video.id) { mutableStateOf(false) }
    var renameOpen by remember(video.id) { mutableStateOf(false) }
    var deleteOpen by remember(video.id) { mutableStateOf(false) }
    var detailsOpen by remember(video.id) { mutableStateOf(false) }
    var newFileName by remember(video.id) { mutableStateOf(video.name) }
    val context = LocalContext.current
    val thumb: File? = if (video.thumbStatus == "DONE") ThumbStore.thumbFile(context, video.id) else null

    val prefs = remember(context) { context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE) }
    val watchedMs = prefs.getLong("resume_path_${video.relativePath}", prefs.getLong("resume_${video.id}", 0L))
    val totalMs = prefs.getLong("duration_path_${video.relativePath}", 0L)
    val artwork: @Composable () -> Unit = {
        Box(if (listMode) Modifier.width(120.dp).height(76.dp)
            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            FileImage(thumb, Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)))
            if (totalMs > 0L) Text(DateUtils.formatElapsedTime(totalMs / 1_000),
                color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(4.dp)
                    .background(androidx.compose.ui.graphics.Color(0xBB000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
            if (totalMs > 0L && watchedMs > 0L) Box(Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                .fillMaxWidth((watchedMs.toFloat() / totalMs).coerceIn(0f, 1f)).height(3.dp)
                .background(MaterialTheme.colorScheme.primary))
        }
    }
    val caption: @Composable () -> Unit = {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = if (listMode) 12.dp else 2.dp)) {
                Text(video.name, style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(Formatter.formatShortFileSize(context, video.size),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${video.name}")
            }
        }
    }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })) {
            if (listMode) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                artwork()
                Box(Modifier.weight(1f)) { caption() }
            } else {
                artwork()
                caption()
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Play") }, onClick = { menuOpen = false; onClick() })
            DropdownMenuItem(text = { Text("Play from start") }, onClick = {
                prefs.edit().putLong("resume_path_${video.relativePath}", 0L).putLong("resume_${video.id}", 0L).apply()
                menuOpen = false; onClick()
            })
            if (onRemoveFromContinue != null) {
                DropdownMenuItem(text = { Text("Remove from Continue Watching") }, onClick = {
                    menuOpen = false
                    onRemoveFromContinue()
                })
            }
            DropdownMenuItem(text = { Text("Copy") }, onClick = {
                menuOpen = false
                vm.stageExplorer(video.relativePath, false, false)
            })
            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                menuOpen = false; newFileName = video.name; renameOpen = true
            })
            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                menuOpen = false; deleteOpen = true
            })
            DropdownMenuItem(text = { Text("Details") }, onClick = {
                menuOpen = false; detailsOpen = true
            })
        }
    }
    if (renameOpen) {
        AlertDialog(onDismissRequest = { renameOpen = false }, title = { Text("Rename original SMB video") },
            text = { Column {
                Text("This changes the filename on your network drive.")
                OutlinedTextField(value = newFileName, onValueChange = { newFileName = it },
                    label = { Text("Full filename including extension") }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = {
                vm.renameOriginal(video.relativePath, newFileName); renameOpen = false
            }, enabled = newFileName.isNotBlank() && newFileName != video.name) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } })
    }
    if (deleteOpen) {
        AlertDialog(onDismissRequest = { deleteOpen = false }, title = { Text("Delete original video?") },
            text = { Text("Permanently delete '${video.name}' from your SMB drive? This cannot be undone.") },
            confirmButton = { TextButton(onClick = {
                vm.deleteOriginal(video.relativePath, false); deleteOpen = false
            }) { Text("Delete permanently") } },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel") } })
    }
    if (detailsOpen) {
        AlertDialog(onDismissRequest = { detailsOpen = false }, title = { Text("Video details") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Full name: ${video.name}")
                Text("File size: ${Formatter.formatFileSize(context, video.size)} (${video.size} bytes)")
                Text("SMB location: ${vm.originalVideoLocation(video.relativePath)}")
                Text("Relative path: ${video.relativePath}")
            } },
            confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text("Close") } })
    }
}

// ---------------------------------------------------------------- Virtual folders

@Composable
fun FoldersScreen(vm: LibraryViewModel, gridState: LazyGridState, onOpenGroup: () -> Unit, onPlay: (VideoEntity) -> Unit, onOpenSettings: () -> Unit) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val dashboardVideos by vm.videos.collectAsStateWithLifecycle()
    val dashboardContext = LocalContext.current
    val playbackPrefs = remember(dashboardContext) { dashboardContext.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE) }
    var continueRefresh by remember { mutableIntStateOf(0) }
    val recentlyWatched = remember(dashboardVideos, continueRefresh) {
        dashboardVideos.mapNotNull { video ->
            val pos = playbackPrefs.getLong("resume_path_${video.relativePath}", playbackPrefs.getLong("resume_${video.id}", 0L))
            val duration = playbackPrefs.getLong("duration_path_${video.relativePath}", 0L)
            if (canContinue(pos, duration, playbackPrefs.getBoolean("hide_continue_${video.relativePath}", false))) video else null
        }.take(18)
    }
    var folderQuery by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var sourceFolder by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf("") }
    var targetName by remember { mutableStateOf("") }
    var editFolders by rememberSaveable { mutableStateOf(false) }

    val shownGroups = remember(groups, folderQuery, vm.folderSort, vm.folderSortDescending, dashboardVideos) {
        val folderVideos = dashboardVideos.groupBy { it.customGroup?.takeIf { name -> name.isNotBlank() } ?: it.groupKey }
        val filtered = groups.filter { it.groupKey.contains(folderQuery.trim(), ignoreCase = true) }
        val comparator = when (vm.folderSort) {
            "COUNT" -> compareBy<GroupRow> { it.count }
            "DATE" -> compareBy<GroupRow> { folderVideos[it.groupKey]?.maxOfOrNull { video -> video.modified } ?: 0L }
            "SIZE" -> compareBy<GroupRow> { folderVideos[it.groupKey]?.sumOf { video -> video.size } ?: 0L }
            else -> compareBy<GroupRow> { it.groupKey.lowercase(Locale.ROOT) }
        }.thenBy { it.groupKey.lowercase(Locale.ROOT) }
        filtered.sortedWith(if (vm.folderSortDescending) comparator.reversed() else comparator)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Folders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) folderQuery = "" }) {
                Icon(if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchExpanded) "Close folder search" else "Search folders")
            }
            Box {
                var sortExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort virtual folders")
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    listOf("NAME" to "Name", "COUNT" to "Number of items",
                        "DATE" to "Latest by date", "SIZE" to "Total size").forEach { (sort, label) ->
                        DropdownMenuItem(
                            text = { Text(label + if (vm.folderSort == sort) {
                                if (vm.folderSortDescending) " ↓" else " ↑"
                            } else "") },
                            onClick = { vm.selectFolderSort(sort); sortExpanded = false }
                        )
                    }
                }
            }
            IconButton(onClick = vm::toggleFolderLayout) {
                Icon(if (vm.folderListMode) Icons.Filled.GridView else Icons.Filled.ViewList,
                    contentDescription = if (vm.folderListMode) "Show folder grid" else "Show folder list")
            }
            Box {
                var folderMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { folderMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Folder options") }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    DropdownMenuItem(text = { Text(if (editFolders) "Finish organizing" else "Organize folders") },
                        onClick = { editFolders = !editFolders; folderMenu = false })
                    DropdownMenuItem(text = { Text("Refresh library") }, onClick = { vm.startScan(ScanMode.REFRESH); folderMenu = false })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { folderMenu = false; onOpenSettings() })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (searchExpanded) {
            OutlinedTextField(value = folderQuery, onValueChange = { folderQuery = it },
                label = { Text("Search folders by name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
        val columns = LibraryLayout.columns(vm.folderGridPreset)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(if (vm.folderListMode) 1 else columns),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                    Column {
        if (recentlyWatched.isNotEmpty() && folderQuery.isBlank()) {
            Text("Continue Watching", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recentlyWatched, key = { it.id }) { video ->
                    Box(Modifier.width(218.dp)) {
                        VideoCard(video, onClick = { onPlay(video) }, vm = vm,
                            onRemoveFromContinue = {
                                playbackPrefs.edit().putBoolean("hide_continue_${video.relativePath}", true).apply()
                                continueRefresh++
                            })
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                    Text("${shownGroups.size} folders · " + when (vm.folderSort) {
                        "COUNT" -> "Number of items"; "DATE" -> "Latest by date"; "SIZE" -> "Total size"; else -> "Name"
                    } + if (vm.folderSortDescending) " ↓" else " ↑", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (shownGroups.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (folderQuery.isNotBlank()) "No matching folders." else "Your virtual folders appear after a scan.",
                        modifier = Modifier.padding(vertical = 24.dp))
                }
                gridItems(shownGroups, key = { it.groupKey }) { row ->
                    Column {
                        FolderCard(vm, row, listMode = vm.folderListMode) {
                            vm.groupFilter.value = row.groupKey
                            onOpenGroup()
                        }
                        var menuExpanded by remember(row.groupKey) { mutableStateOf(false) }
                        if (editFolders) Box {
                            TextButton(onClick = { menuExpanded = true }) { Text("⋮ Options") }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Rename folder") }, onClick = {
                                    menuExpanded = false; sourceFolder = row.groupKey; operation = "Rename"; targetName = row.groupKey
                                })
                                DropdownMenuItem(text = { Text("Merge into…") }, onClick = {
                                    menuExpanded = false; sourceFolder = row.groupKey; operation = "Merge"; targetName = ""
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    if (sourceFolder != null) {
        AlertDialog(onDismissRequest = { sourceFolder = null }, title = { Text("$operation ${sourceFolder}") },
            text = { Column {
                Text(if (operation == "Merge") "All videos assigned to this group will be assigned to the destination virtual folder." else "Rename this virtual folder. No SMB files are renamed.")
                OutlinedTextField(value = targetName, onValueChange = { targetName = it }, label = { Text(if (operation == "Merge") "Destination folder" else "New name") })
            } },
            confirmButton = { TextButton(onClick = {
                val from = sourceFolder!!
                if (operation == "Merge") vm.mergeFolders(from, targetName.trim()) else vm.renameFolder(from, targetName.trim())
                sourceFolder = null
            }) { Text(operation) } },
            dismissButton = { TextButton(onClick = { sourceFolder = null }) { Text("Cancel") } })
    }
}

@Composable
private fun FolderCard(vm: LibraryViewModel, row: GroupRow, modifier: Modifier = Modifier,
    listMode: Boolean = false, onClick: () -> Unit) {
    val cover by produceState<File?>(initialValue = null, row.groupKey, row.thumbsDone) {
        value = vm.coverFor(row.groupKey)
    }
    val artwork: @Composable () -> Unit = {
        Box((if (listMode) Modifier.width(104.dp).height(68.dp) else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            if (cover == null) Icon(Icons.Filled.Folder, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(36.dp).width(36.dp))
            else FileImage(cover, Modifier.fillMaxSize())
        }
    }
    val caption: @Composable () -> Unit = {
        Column(Modifier.padding(vertical = 6.dp, horizontal = if (listMode) 12.dp else 2.dp)) {
            Text(row.groupKey.replaceFirstChar { it.titlecase(Locale.ROOT) },
                style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${row.count} video" + if (row.count == 1) "" else "s",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        if (listMode) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            artwork(); Box(Modifier.weight(1f)) { caption() }
        } else { artwork(); caption() }
    }
}

@Composable
private fun LibraryDisplayMenu(vm: LibraryViewModel) {
    IconButton(onClick = vm::toggleVideoLayout) {
        Icon(if (vm.videoListMode) Icons.Filled.GridView else Icons.Filled.ViewList,
            contentDescription = if (vm.videoListMode) "Show video grid" else "Show video list")
    }
    Box {
        var expanded by remember { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.Sort, contentDescription = "Sort videos") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VideoSort.entries.forEach { sort ->
                DropdownMenuItem(text = { Text(sort.label + if (vm.videoSort == sort) {
                    if (vm.sortDescending) " ↓" else " ↑"
                } else "") }, onClick = { vm.selectVideoSort(sort); expanded = false })
            }
        }
    }
}

/** Four equally sized choices, with no incomplete last row or screen-height coupling. */
@Composable
private fun GridPresetSetting(label: String, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        GRID_PRESETS.chunked(2).forEach { options ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    Row(Modifier.weight(1f).heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected == option) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface)
                        .selectable(selected = selected == option, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = selected == option, onClick = null)
                        Text("$option columns", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Scan & Sync

@Composable
fun ScanScreen(vm: LibraryViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val pending by vm.pendingDirs.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val active by vm.scanActive.collectAsStateWithLifecycle()
    val total by vm.videoCount.collectAsStateWithLifecycle()
    val thumbsActive by vm.thumbsActive.collectAsStateWithLifecycle()
    val thumbsDone by vm.thumbsDone.collectAsStateWithLifecycle()
    val thumbsPending by vm.thumbsPending.collectAsStateWithLifecycle()
    val thumbsFailed by vm.thumbsFailed.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Scan & Sync", style = MaterialTheme.typography.titleLarge) }
        item { AutomaticSyncSummary() }

        vm.notice?.let { message ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message)
                        TextButton(onClick = vm::dismissNotice) { Text("OK") }
                    }
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val s = session
                    if (s == null) {
                        Text("No scan has run yet.")
                    } else {
                        val shownStatus = if (s.status == ScanStatus.RUNNING && !active) {
                            "INTERRUPTED"
                        } else {
                            s.status
                        }
                        Text("Status: ${shownStatus.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}")
                        Text("Mode: ${s.mode.lowercase().replaceFirstChar { it.uppercase() }}")
                        Text("Folders scanned: ${s.dirsScanned}   Waiting: $pending")
                        Text("Videos found this scan: ${s.videosFound}   In library: $total")
                        Text("Errors: ${s.errorCount}")
                        if (active && s.currentDir.isNotEmpty()) {
                            Text(
                                "Last folder: ${s.currentDir}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        s.finishedAt?.let {
                            Text(
                                "Finished ${DateUtils.getRelativeTimeSpanString(it)}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (active) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.startScan(ScanMode.INITIAL) }, enabled = !active) { Text("Full scan") }
                    OutlinedButton(onClick = { vm.startScan(ScanMode.REFRESH) }, enabled = !active) { Text("Refresh") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.startScan(ScanMode.RESUME) }, enabled = !active) { Text("Resume") }
                    OutlinedButton(onClick = { vm.startScan(ScanMode.RETRY) }, enabled = !active) { Text("Retry failed") }
                    if (active || thumbsActive) TextButton(onClick = vm::stopScan) { Text("Stop") }
                }
            }
        }

        item {
            val busy = active || thumbsActive
            val all = thumbsDone + thumbsPending + thumbsFailed
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Thumbnails", style = MaterialTheme.typography.titleMedium)
                    Text("Ready: $thumbsDone   Waiting: $thumbsPending   Failed: $thumbsFailed")
                    if (busy && thumbsPending > 0) {
                        LinearProgressIndicator(
                            progress = { thumbsDone.toFloat() / all.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = vm::startThumbnails, enabled = !busy && thumbsPending > 0) {
                            Text("Generate")
                        }
                        OutlinedButton(
                            onClick = vm::retryFailedThumbnails,
                            enabled = !busy && thumbsFailed > 0,
                        ) { Text("Retry failed") }
                    }
                    TextButton(onClick = vm::clearThumbnailCache, enabled = !busy) {
                        Text("Clear thumbnail cache")
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            item { Text("Errors", style = MaterialTheme.typography.titleMedium) }
            items(errors, key = { it.id }) { e ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        e.path.ifEmpty { "(connection)" },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        e.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AutomaticSyncSummary() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(
        com.deepak.videolibrary.scan.PeriodicSyncWorker.DIAGNOSTICS, android.content.Context.MODE_PRIVATE) }
    val snapshot by produceState(initialValue = prefs.getString("last_status", "Not run yet") to prefs.getLong("last_success", 0L), prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            value = prefs.getString("last_status", "Not run yet") to prefs.getLong("last_success", 0L)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        value = prefs.getString("last_status", "Not run yet") to prefs.getLong("last_success", 0L)
        awaitDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val status = when (val raw = snapshot.first.orEmpty()) {
        "RUNNING" -> "Syncing…"
        "COMPLETED" -> "Up to date"
        "INTERRUPTED" -> "Interrupted · will try again"
        else -> when {
            raw.startsWith("SKIPPED:") -> raw.removePrefix("SKIPPED:").trim()
            raw.startsWith("FAILED:") -> "Couldn't sync · will retry"
            else -> "Not run yet"
        }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Automatic library sync", style = MaterialTheme.typography.titleMedium)
            Text("About every 12 hours when your server is reachable.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text("Last successful sync: " + if (snapshot.second == 0L) "Never" else
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(snapshot.second)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Settings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    val saved = vm.settings
    var host by remember { mutableStateOf(saved.host) }
    var share by remember { mutableStateOf(saved.share) }
    var root by remember { mutableStateOf(saved.rootPath) }
    var user by remember { mutableStateOf(saved.username) }
    var password by remember { mutableStateOf(saved.password) }
    var savePassword by remember { mutableStateOf(saved.savePassword) }
    val context = LocalContext.current
    val gesturePrefs = remember(context) { context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE) }
    var swipeGesturesEnabled by remember {
        mutableStateOf(gesturePrefs.getBoolean("volume_gesture_enabled", true) &&
            gesturePrefs.getBoolean("brightness_gesture_enabled", true))
    }
    val keyboardScope = rememberCoroutineScope()
    val passwordBringIntoView = remember { BringIntoViewRequester() }
    fun current() = SmbSettings(
        host = host,
        share = share,
        rootPath = root,
        username = user,
        password = password,
        savePassword = savePassword,
    )

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Personalize your library, playback controls and network connection.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var gesturesExpanded by rememberSaveable { mutableStateOf(false) }
        var layoutExpanded by rememberSaveable { mutableStateOf(false) }
        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { gesturesExpanded = !gesturesExpanded },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Player gestures", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (gesturesExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
                }
                if (gesturesExpanded) {
                    Text("Volume is controlled from the right side; brightness from the left.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Brightness and volume swipe")
                            Text("Left: brightness · Right: volume", style = MaterialTheme.typography.labelSmall)
                        }
                        androidx.compose.material3.Switch(checked = swipeGesturesEnabled, onCheckedChange = {
                            swipeGesturesEnabled = it
                            gesturePrefs.edit().putBoolean("volume_gesture_enabled", it)
                                .putBoolean("brightness_gesture_enabled", it).apply()
                        })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { layoutExpanded = !layoutExpanded },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Library layout", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (layoutExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
                }
                if (layoutExpanded) {
                    Text("Choose the number of columns. Thumbnails keep their 16:9 shape in every layout.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Show videos as a list", modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = vm.videoListMode, onCheckedChange = { vm.toggleVideoLayout() })
                    }
                    GridPresetSetting("Folders grid", vm.folderGridPreset, vm::updateFolderGridPreset)
                    GridPresetSetting("Videos grid", vm.videoGridPreset, vm::updateVideoGridPreset)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("SMB connection", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Server address") },
            placeholder = { Text("e.g. 192.168.71.250") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Enter the SMB server IP or hostname and share manually. Automatic discovery was removed because it was unreliable.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = share,
            onValueChange = { share = it },
            label = { Text("Share name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = root,
            onValueChange = { root = it },
            label = { Text("Starting folder (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username (blank for guest)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(passwordBringIntoView)
                .onFocusChanged { state ->
                    if (state.isFocused) keyboardScope.launch {
                        kotlinx.coroutines.delay(250)
                        passwordBringIntoView.bringIntoView()
                    }
                },
        )
        Row(
            Modifier.fillMaxWidth().clickable { savePassword = !savePassword },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(checked = savePassword, onCheckedChange = { savePassword = it })
            Text(
                "Save password (encrypted with the Android Keystore)",
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (!savePassword) {
            Text(
                "Without a saved password, background scans only work while the app has been opened since it last started.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveSettings(current()) }) { Text("Save") }
            OutlinedButton(onClick = { vm.testConnection(current()) }) { Text("Test connection") }
        }

        when (val t = vm.testState) {
            TestState.Idle -> Unit
            TestState.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is TestState.Success -> Text(t.message, color = MaterialTheme.colorScheme.primary)
            is TestState.Failure -> Text(t.message, color = MaterialTheme.colorScheme.error)
        }
    }
}
