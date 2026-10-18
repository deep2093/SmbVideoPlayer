package com.deepak.videolibrary.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
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

/** Presets represent columns × target visible rows in the scrollable library viewport. */
private val GRID_PRESETS = listOf("3×3", "3×4", "4×3", "4×4", "5×4", "5×5")
private fun gridDimensions(choice: String): Pair<Int, Int> = when (choice) {
    "3×3" -> 3 to 3
    "3×4" -> 3 to 4
    "4×3" -> 4 to 3
    "4×4" -> 4 to 4
    "5×4" -> 5 to 4
    "5×5" -> 5 to 5
    else -> 3 to 3
}

private val TAB_LABELS = listOf("Folders", "Videos", "Browse", "Scan", "Settings")
private val TAB_GLYPHS = listOf("▦", "▶", "▤", "⟳", "⚙")


@Composable
fun AppRoot(vm: LibraryViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var folderDetails by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf<VideoEntity?>(null) }
    var playQueue by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
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
                if (!tabletLayout) NavigationBar {
                    TAB_LABELS.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = {
                                tab = index
                                folderDetails = false
                                vm.groupFilter.value = null
                            },
                            icon = { Text(TAB_GLYPHS[index]) }, label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (tabletLayout) {
                    androidx.compose.material3.NavigationRail {
                        TAB_LABELS.forEachIndexed { index, label ->
                            androidx.compose.material3.NavigationRailItem(
                                selected = tab == index,
                                onClick = {
                                    tab = index
                                    folderDetails = false
                                    vm.groupFilter.value = null
                                },
                                icon = { Text(TAB_GLYPHS[index]) },
                                label = { Text(label) },
                                alwaysShowLabel = true,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    when (tab) {
                        0 -> FoldersScreen(vm, onOpenGroup = { folderDetails = true; tab = 1 }, onPlay = { video -> playing = video; playQueue = listOf(video) }, onOpenSettings = { tab = 4 })
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
    val continueWatching = remember(videos) {
        videos.filter { resumePrefs.getLong("resume_path_${it.relativePath}", resumePrefs.getLong("resume_${it.id}", 0L)) > 15_000L }.take(16)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (selectedGroup == null) "Videos" else "Videos in folder", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
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
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { if (onBackToFolders != null) onBackToFolders() else vm.groupFilter.value = null }) {
                    Text("‹  $selectedGroup")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (videos.isNotEmpty()) onPlay(videos.first(), videos.toList()) }, enabled = videos.isNotEmpty()) {
                    Text("▶ Play all (${videos.size})")
                }
                TextButton(onClick = { videoSearch = ""; vm.findVideosToAdd(selectedGroup, ""); addDialog = true }) { Text("+ Add") }
            }
        }
        if (group == null && query.isEmpty() && continueWatching.isNotEmpty()) {
            Text("Continue watching", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(continueWatching, key = { it.id }) { video ->
                    androidx.compose.foundation.layout.Box(Modifier.width(210.dp)) {
                        VideoCard(video, onClick = { onPlay(video, listOf(video)) }, vm = vm)
                    }
                }
            }
        }
        Text(
            "${videos.size} shown of $total indexed",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (total == 0) {
            Text(
                "No videos yet. Add your server in Settings, then run a scan.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val (columns, _) = gridDimensions(vm.videoGridPreset)
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                gridItems(videos, key = { it.id }) { video ->
                    VideoCard(video, onClick = { onPlay(video, if (group != null) videos.toList() else listOf(video)) }, vm = vm)
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
private fun VideoCard(video: VideoEntity, onClick: () -> Unit, vm: LibraryViewModel, modifier: Modifier = Modifier) {
    var menuOpen by remember(video.id) { mutableStateOf(false) }
    var renameOpen by remember(video.id) { mutableStateOf(false) }
    var deleteOpen by remember(video.id) { mutableStateOf(false) }
    var detailsOpen by remember(video.id) { mutableStateOf(false) }
    var newFileName by remember(video.id) { mutableStateOf(video.name) }
    val context = LocalContext.current
    val thumb: File? = if (video.thumbStatus == "DONE") ThumbStore.thumbFile(context, video.id) else null

    Box(modifier) {
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(8.dp)) {
                FileImage(thumb, Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)))
                val prefs = remember(context) { context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE) }
                val watchedMs = prefs.getLong("resume_path_${video.relativePath}", prefs.getLong("resume_${video.id}", 0L))
                val totalMs = prefs.getLong("duration_path_${video.relativePath}", 0L)
                if (totalMs > 0L && watchedMs > 0L) {
                    // A continuous left-to-right watched indicator, without Material3's
                    // separate end-of-track stop dot.
                    val watchedFraction = (watchedMs.toFloat() / totalMs).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(
                            Modifier.fillMaxWidth(watchedFraction).fillMaxHeight()
                                .background(androidx.compose.ui.graphics.Color(0xFFE53935))
                        )
                    }
                }
                Text(video.name, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(Formatter.formatShortFileSize(context, video.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
fun FoldersScreen(vm: LibraryViewModel, onOpenGroup: () -> Unit, onPlay: (VideoEntity) -> Unit, onOpenSettings: () -> Unit) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val dashboardVideos by vm.videos.collectAsStateWithLifecycle()
    val dashboardContext = LocalContext.current
    val playbackPrefs = remember(dashboardContext) { dashboardContext.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE) }
    val recentlyWatched = remember(dashboardVideos) {
        dashboardVideos.mapNotNull { video ->
            val pos = playbackPrefs.getLong("resume_path_${video.relativePath}", playbackPrefs.getLong("resume_${video.id}", 0L))
            val duration = playbackPrefs.getLong("duration_path_${video.relativePath}", 0L)
            if (pos > 15_000L && (duration <= 0L || pos < duration - 10_000L)) video else null
        }.take(18)
    }
    var folderQuery by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var sourceFolder by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf("") }
    var targetName by remember { mutableStateOf("") }
    var editFolders by rememberSaveable { mutableStateOf(false) }

    val shownGroups = remember(groups, folderQuery) {
        groups.filter { it.groupKey.contains(folderQuery.trim(), ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("SMB Video Library", style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { editFolders = !editFolders }) {
                Icon(Icons.Filled.Edit, contentDescription = if (editFolders) "Finish organizing folders" else "Organize folders")
            }
            IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) folderQuery = "" }) {
                Icon(if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchExpanded) "Close folder search" else "Search folders")
            }
            IconButton(onClick = { vm.startScan(ScanMode.REFRESH) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh SMB library")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        Text(
            "Organize your library without modifying files on the SMB drive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (searchExpanded) {
            OutlinedTextField(value = folderQuery, onValueChange = { folderQuery = it },
                label = { Text("Search folders by name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
        if (recentlyWatched.isNotEmpty() && folderQuery.isBlank()) {
            Text("Continue Watching", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recentlyWatched, key = { it.id }) { video ->
                    Box(Modifier.width(196.dp)) {
                        VideoCard(video, onClick = { onPlay(video) }, vm = vm)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        Text("Folders", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 6.dp))
        Text("${shownGroups.size} folders · ${groups.size} total", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 8.dp))
        val (columns, _) = gridDimensions(vm.folderGridPreset)
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                gridItems(shownGroups, key = { it.groupKey }) { row ->
                    Column {
                        FolderCard(vm, row) {
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
private fun FolderCard(vm: LibraryViewModel, row: GroupRow, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Rebuilt whenever more thumbnails of this folder become ready.
    val cover by produceState<File?>(initialValue = null, row.groupKey, row.thumbsDone) {
        value = vm.coverFor(row.groupKey)
    }

    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
      Column(Modifier.fillMaxWidth().padding(6.dp)) {
        FileImage(
            cover,
            Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)),
        )
        Text(
            row.groupKey.replaceFirstChar { it.titlecase(Locale.ROOT) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "${row.count} video" + if (row.count == 1) "" else "s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
}

/** Selection lives only in Settings. Rows are an on-screen target; the list scrolls for more. */
@Composable
private fun GridPresetSetting(label: String, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        GRID_PRESETS.chunked(3).forEach { options ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                options.forEach { option ->
                    Row(Modifier.clickable { onSelect(option) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = selected == option, onClick = { onSelect(option) })
                        Text(option, style = MaterialTheme.typography.bodySmall)
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

// ---------------------------------------------------------------- Settings

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
    var volumeGestureEnabled by remember { mutableStateOf(gesturePrefs.getBoolean("volume_gesture_enabled", true)) }
    var brightnessGestureEnabled by remember { mutableStateOf(gesturePrefs.getBoolean("brightness_gesture_enabled", true)) }
    val syncPrefs = remember(context) { context.getSharedPreferences(com.deepak.videolibrary.scan.PeriodicSyncWorker.DIAGNOSTICS, android.content.Context.MODE_PRIVATE) }
    var syncRefresh by remember { mutableIntStateOf(0) }
    var artworkCacheBytes by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(syncRefresh) {
        artworkCacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.deepak.videolibrary.thumbs.ThumbStore.artworkBytes(context)
        }
    }
    val syncAttempt = remember(syncRefresh) { syncPrefs.getLong("last_attempt", 0L) }
    val syncSuccess = remember(syncRefresh) { syncPrefs.getLong("last_success", 0L) }
    val syncStatus = remember(syncRefresh) { syncPrefs.getString("last_status", "Not run yet") ?: "Not run yet" }
    fun syncDate(time: Long): String = if (time == 0L) "Never" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))


    fun current() = SmbSettings(
        host = host,
        share = share,
        rootPath = root,
        username = user,
        password = password,
        savePassword = savePassword,
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Personalize your library, playback controls and network connection.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Player gestures", style = MaterialTheme.typography.titleLarge)
                Text("Volume is controlled from the right side; brightness from the left.",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Volume swipe")
                        Text("Swipe vertically on the right", style = MaterialTheme.typography.labelSmall)
                    }
                    androidx.compose.material3.Switch(checked = volumeGestureEnabled, onCheckedChange = {
                        volumeGestureEnabled = it
                        gesturePrefs.edit().putBoolean("volume_gesture_enabled", it).apply()
                    })
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Brightness swipe")
                        Text("Swipe vertically on the left", style = MaterialTheme.typography.labelSmall)
                    }
                    androidx.compose.material3.Switch(checked = brightnessGestureEnabled, onCheckedChange = {
                        brightnessGestureEnabled = it
                        gesturePrefs.edit().putBoolean("brightness_gesture_enabled", it).apply()
                    })
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Library layout", style = MaterialTheme.typography.titleLarge)
        Text("Choose columns × target visible rows. Videos and Folders have independent layouts, saved on this device.", style = MaterialTheme.typography.bodySmall)
        GridPresetSetting("Folders grid", vm.folderGridPreset, vm::updateFolderGridPreset)
        GridPresetSetting("Videos grid", vm.videoGridPreset, vm::updateVideoGridPreset)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Automatic library sync", style = MaterialTheme.typography.titleLarge)
                Text("Best-effort every 12 hours when Android allows it, network is connected, battery is not low and storage is not low. Wi-Fi/LAN SMB access is not guaranteed by the network constraint.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Last attempt: ${syncDate(syncAttempt)}")
                Text("Last success: ${syncDate(syncSuccess)}")
                Text("Status: $syncStatus", style = MaterialTheme.typography.bodySmall)
                Text("Artwork cache: " + if (artworkCacheBytes < 0) "Calculating…" else Formatter.formatShortFileSize(context, artworkCacheBytes),
                    style = MaterialTheme.typography.bodySmall)
                Text("Artwork is cached privately on the device; playback uses ExoPlayer's memory buffer, not a persistent video download cache.",
                    style = MaterialTheme.typography.bodySmall)
                Text("For immediate changes, use Scan → Refresh. Save SMB credentials for unattended authenticated sync.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { syncRefresh++ }) { Text("Refresh sync status") }
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
            modifier = Modifier.fillMaxWidth(),
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
