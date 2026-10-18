package com.deepak.videolibrary.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private val TAB_LABELS = listOf("Videos", "Folders", "Scan", "Settings")
private val TAB_GLYPHS = listOf("▶", "▦", "⟳", "⚙")


@Composable
fun AppRoot(vm: LibraryViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var folderDetails by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf<VideoEntity?>(null) }
    BackHandler(enabled = playing != null || folderDetails || tab != 0) {
        when {
            playing != null -> playing = null
            folderDetails -> { folderDetails = false; vm.groupFilter.value = null; tab = 1 }
            else -> { tab = 0; vm.groupFilter.value = null }
        }
    }
    if (playing != null) {
        PlayerScreen(video = playing!!, settings = vm.settings, onClose = { playing = null })
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> VideosScreen(vm, onPlay = { playing = it }, onBackToFolders = if (folderDetails) ({
                    folderDetails = false; vm.groupFilter.value = null; tab = 1
                }) else null)
                1 -> FoldersScreen(vm, onOpenGroup = { folderDetails = true; tab = 0 })
                2 -> ScanScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

// ---------------------------------------------------------------- Videos

@Composable
fun VideosScreen(vm: LibraryViewModel, onPlay: (VideoEntity) -> Unit, onBackToFolders: (() -> Unit)? = null) {
    val videos by vm.videos.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val group by vm.groupFilter.collectAsStateWithLifecycle()
    val selectedGroup = group
    val total by vm.videoCount.collectAsStateWithLifecycle()
    var addDialog by remember { mutableStateOf(false) }
    var videoSearch by remember { mutableStateOf("") }
    val context = LocalContext.current
    val resumePrefs = remember { context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE) }
    val continueWatching = remember(videos) {
        videos.filter { resumePrefs.getLong("resume_${it.id}", 0) > 15_000 }.take(16)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            label = { Text("Search videos") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        TileSizeSetting(label = "Video thumbnail size", size = vm.videoTileDp, onChange = vm::setVideoTileSize)
        if (group != null) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { if (onBackToFolders != null) onBackToFolders() else vm.groupFilter.value = null }) {
                    Text("‹  $group")
                }
                TextButton(onClick = { videoSearch = ""; vm.findVideosToAdd(selectedGroup, ""); addDialog = true }) { Text("+ Add videos") }
            }
        }
        if (group == null && query.isEmpty() && continueWatching.isNotEmpty()) {
            Text("Continue watching", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(continueWatching, key = { it.id }) { video ->
                    androidx.compose.foundation.layout.Box(Modifier.width(210.dp)) {
                        VideoCard(video, onClick = { onPlay(video) })
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(vm.videoTileDp.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(videos, key = { it.id }) { video -> VideoCard(video, onClick = { onPlay(video) }) }
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

@Composable
private fun VideoCard(video: VideoEntity, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb: File? = if (video.thumbStatus == "DONE") ThumbStore.thumbFile(context, video.id) else null

    Card(onClick = onClick, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
      Column(Modifier.padding(8.dp)) {
        FileImage(
            thumb,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
        )
        Text(
            video.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            Formatter.formatShortFileSize(context, video.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
}

// ---------------------------------------------------------------- Virtual folders

@Composable
fun FoldersScreen(vm: LibraryViewModel, onOpenGroup: () -> Unit) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    var folderQuery by rememberSaveable { mutableStateOf("") }
    var createDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var sourceFolder by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf("") }
    var targetName by remember { mutableStateOf("") }

    val shownGroups = remember(groups, folderQuery) {
        groups.filter { it.groupKey.contains(folderQuery.trim(), ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Virtual folders",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Organize your library without modifying files on the SMB drive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Button(onClick = { newName = ""; createDialog = true }) { Text("+ New virtual folder") }
        vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(
            value = folderQuery,
            onValueChange = { folderQuery = it },
            label = { Text("Search folders by name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TileSizeSetting(label = "Folder thumbnail size", size = vm.folderTileDp, onChange = vm::setFolderTileSize)
        Text("${shownGroups.size} of ${groups.size} virtual folders", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(vm.folderTileDp.dp),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { sourceFolder = row.groupKey; operation = "Rename"; targetName = row.groupKey }) { Text("Rename") }
                        TextButton(onClick = { sourceFolder = row.groupKey; operation = "Merge"; targetName = "" }) { Text("Merge") }
                    }
                }
            }
        }
    }
    if (createDialog) {
        AlertDialog(onDismissRequest = { createDialog = false },
            title = { Text("Create virtual folder") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Folder name") }) },
            confirmButton = { TextButton(onClick = { vm.createFolder(newName); createDialog = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Cancel") } })
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
private fun FolderCard(vm: LibraryViewModel, row: GroupRow, onClick: () -> Unit) {
    // Rebuilt whenever more thumbnails of this folder become ready.
    val cover by produceState<File?>(initialValue = null, row.groupKey, row.thumbsDone) {
        value = vm.coverFor(row.groupKey)
    }

    Card(onClick = onClick, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
      Column(Modifier.padding(8.dp)) {
        FileImage(
            cover,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
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

/** Drag to choose larger thumbnails (fewer columns) or smaller thumbnails (more columns). */
@Composable
private fun TileSizeSetting(label: String, size: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("${size} dp", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = size.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = 160f..440f, modifier = Modifier.fillMaxWidth())
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
        Text("Find SMB servers on Wi-Fi", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = vm::discoverHosts, enabled = !vm.discoveryRunning) {
            Text(if (vm.discoveryRunning) "Searching your network…" else "Discover network drives")
        }
        if (vm.discoveredHosts.isEmpty() && !vm.discoveryRunning) {
            Text("Discovery finds devices offering SMB (port 445). You still need to enter the share name and login. Some routers do not respond to discovery.",
                style = MaterialTheme.typography.bodySmall)
        }
        vm.discoveredHosts.forEach { address ->
            TextButton(onClick = { host = address }) { Text("SMB device: $address  → Use") }
        }
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
