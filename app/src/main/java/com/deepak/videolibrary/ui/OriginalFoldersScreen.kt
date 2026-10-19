package com.deepak.videolibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.scan.ScanMode
import java.io.File

/** Actual indexed directory hierarchy; reuses thumbnails without moving or regrouping videos. */
@Composable
fun OriginalFoldersScreen(vm: LibraryViewModel, onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
    val videos by vm.allVideos.collectAsStateWithLifecycle()
    val root = vm.settings.rootPath.trim('/')
    val path = vm.originalPath.takeIf { isWithinFolder(it, root) } ?: root
    val query = vm.originalQuery
    val inFolder = remember(videos, path) { videos.filter { isWithinFolder(it.parentDir, path) } }
    val children = remember(inFolder, path, query) {
        originalChildren(inFolder, path).filter { folder ->
            query.isBlank() || matchesFilename(folder.path.substringAfterLast('/'), query) ||
                folder.videos.any { matchesFilename(it.name, query) || it.parentDir.removePrefix(path).split('/').any { part -> matchesFilename(part, query) } }
        }
    }
    val shownVideos = remember(inFolder, path, query, vm.videoSort, vm.sortDescending) {
        vm.videoSort.arrange(inFolder.filter {
            if (query.isBlank()) it.parentDir == path else matchesFilename(it.name, query) || it.parentDir.removePrefix(path).split('/').any { part -> matchesFilename(part, query) }
        }, vm.sortDescending)
    }
    val queue = remember(inFolder, query, vm.videoSort, vm.sortDescending) {
        vm.videoSort.arrange(inFolder.filter {
            query.isBlank() || matchesFilename(it.name, query) ||
                it.parentDir.removePrefix(path).split('/').any { folder -> matchesFilename(folder, query) }
        }.distinctBy { it.relativePath }, vm.sortDescending)
    }
    fun up() { vm.originalQuery = ""; vm.originalPath = path.substringBeforeLast('/', "").takeIf { isWithinFolder(it, root) } ?: root }
    BackHandler(enabled = query.isNotEmpty() || path != root) {
        if (query.isNotEmpty()) vm.originalQuery = "" else up()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Original folders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.startScan(ScanMode.REFRESH) }) { Text("Refresh index") }
        }
        Text("Your actual SMB folders · existing thumbnails", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = path != root, onClick = ::up) { Text("← Up") }
            Text(path.ifEmpty { "/ (share root)" }, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        OutlinedTextField(value = query, onValueChange = { vm.originalQuery = it }, singleLine = true,
            label = { Text("Search folder and video names") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { vm.originalQuery = "" }) { Text("Clear") } })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${queue.size} videos${if (query.isBlank()) " including subfolders" else " in results"}", modifier = Modifier.weight(1f))
            TextButton(enabled = queue.isNotEmpty(), onClick = { queue.firstOrNull()?.let { onPlay(it, queue) } }) { Text("Play all") }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(if (vm.folderListMode) 1 else LibraryLayout.columns(vm.folderGridPreset)),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
            if (inFolder.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("No indexed videos here. Run a library scan to include your original folders.")
            }
            if (inFolder.isNotEmpty() && children.isEmpty() && shownVideos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("No matching folders or videos.")
            }
            items(children, key = { "folder:${it.path}" }) { folder ->
                OriginalFolderCard(vm, folder) { vm.originalQuery = ""; vm.originalPath = folder.path }
            }
            items(shownVideos, key = { "video:${it.relativePath}" }) { video ->
                VideoCard(video, onClick = { onPlay(video, if (query.isBlank()) shownVideos else queue) }, vm = vm, listMode = vm.folderListMode)
            }
        }
    }
}

@Composable
private fun OriginalFolderCard(vm: LibraryViewModel, folder: OriginalFolder, onClick: () -> Unit) {
    var cover by remember(folder.path) { mutableStateOf<File?>(null) }
    LaunchedEffect(folder.path, folder.videos) { cover = vm.originalCover(folder.path, folder.videos) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                if (cover != null) FileImage(cover, Modifier.fillMaxSize())
                else Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(48.dp))
            }
            Column(Modifier.padding(10.dp)) {
                Text(folder.path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${folder.videos.size} videos", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
