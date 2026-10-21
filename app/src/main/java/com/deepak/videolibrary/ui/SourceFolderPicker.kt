package com.deepak.videolibrary.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deepak.videolibrary.smb.SmbEntry
import com.deepak.videolibrary.smb.SmbErrors
import kotlinx.coroutines.CancellationException

/**
 * Drive browser with tick boxes. Ticking a folder includes everything inside it (its sub-folders show as included);
 * unticking takes it out again. With [withFiles], single video files can be ticked too. Nothing on the drive changes.
 *
 * [marked] are items that are already in use (shown as "Added"); unticking one of them removes it on confirm.
 */
@Composable
internal fun DrivePicker(
    vm: LibraryViewModel,
    title: String,
    explanation: String,
    confirmLabel: String,
    withFiles: Boolean,
    initialFolders: Set<String>,
    initialFiles: Set<String> = emptySet(),
    marked: Set<String> = emptySet(),
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (folders: Set<String>, files: Set<String>) -> Unit,
) {
    val root = vm.settings.rootPath.trim('/')
    var path by remember { mutableStateOf(root) }
    var folders by remember { mutableStateOf(initialFolders.map { it.trim('/') }.toSet()) }
    var files by remember { mutableStateOf(initialFiles.map { it.trim('/') }.toSet()) }
    var entries by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(path, attempt) {
        loading = true
        error = null
        entries = emptyList()
        try {
            entries = vm.driveEntries(path, withFiles).filter { it.isDirectory || isPlayable(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = SmbErrors.describe(e)
        } finally {
            loading = false
        }
    }
    fun child(name: String) = listOf(path, name).filter(String::isNotBlank).joinToString("/")
    // A ticked folder above this item already includes it.
    fun includedByParent(item: String) = folders.any { it != item && isWithinFolder(item, it) }
    fun toggleFolder(item: String) {
        if (item in folders) {
            folders = folders - item
        } else {
            folders = folders.filterNot { isWithinFolder(it, item) }.toSet() + item
            files = files.filterNot { isWithinFolder(it, item) }.toSet()
        }
    }
    fun toggleFile(item: String) {
        files = if (item in files) files - item else files + item
    }
    // Ticking everything shown in this folder at once (or unticking it all again).
    val shownItems = entries.map { child(it.name) to it.isDirectory }
    val insideTicked = shownItems.isNotEmpty() && shownItems.all { (item, _) -> includedByParent(item) }
    val allTicked = shownItems.isNotEmpty() && shownItems.all { (item, dir) -> includedByParent(item) || (if (dir) item in folders else item in files) }
    fun tickAllHere() {
        val dirs = shownItems.filter { it.second }.map { it.first }.toSet()
        val singles = shownItems.filterNot { it.second }.map { it.first }.toSet()
        if (allTicked) {
            folders = folders - dirs
            files = files - singles
        } else {
            folders = folders.filterNot { f -> dirs.any { d -> f != d && isWithinFolder(f, d) } }.toSet() + dirs
            files = files.filterNot { f -> dirs.any { d -> isWithinFolder(f, d) } }.toSet() + singles
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.88f),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BreadcrumbBar(
                    crumbs = breadcrumbs(path, root),
                    onHome = { path = root },
                    onSelect = { path = it },
                    rootLabel = if (root.isEmpty()) vm.settings.share.ifBlank { "Share root" } else root.substringAfterLast('/'),
                    enabled = !busy,
                )
                if (entries.isNotEmpty() && !insideTicked) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${entries.size} here", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = ::tickAllHere, enabled = !busy && !loading) {
                            // One button for both jobs; it flips with a short scale-and-fade when its job changes.
                            androidx.compose.animation.AnimatedContent(
                                targetState = allTicked,
                                transitionSpec = {
                                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                                        androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(220), initialScale = 0.7f))
                                        .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
                                },
                                label = "picker_select_all",
                            ) { all ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (all) Icons.Filled.Deselect else Icons.Filled.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text(if (all) "Unselect all" else "Select all")
                                }
                            }
                        }
                    }
                }
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }) { Text("Try again") }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(entries, key = { (if (it.isDirectory) "d:" else "f:") + it.name }) { entry ->
                        val item = child(entry.name)
                        val inherited = includedByParent(item)
                        val checked = inherited || (if (entry.isDirectory) item in folders else item in files)
                        val label = if (entry.isDirectory) "Choose folder ${entry.name}" else "Choose file ${entry.name}"
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .clickable(enabled = !busy) { if (entry.isDirectory) path = item else if (!inherited) toggleFile(item) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { if (entry.isDirectory) toggleFolder(item) else toggleFile(item) },
                                enabled = !busy && !inherited,
                                modifier = Modifier.semantics { contentDescription = label },
                            )
                            Icon(
                                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val note = when {
                                    inherited -> "Included"
                                    item in marked && checked -> "Added"
                                    item in marked -> "Will be removed"
                                    else -> null
                                }
                                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
                            }
                            if (entry.isDirectory) {
                                IconButton(onClick = { path = item }, enabled = !busy) { Icon(Icons.Filled.ChevronRight, contentDescription = "Open ${entry.name}") }
                            }
                        }
                    }
                    if (!loading && error == null && entries.isEmpty()) {
                        item { Text(if (withFiles) "No folders or videos here." else "No sub-folders here.", modifier = Modifier.padding(vertical = 12.dp)) }
                    }
                }
                vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append("${folders.size} ${if (folders.size == 1) "folder" else "folders"}")
                            if (withFiles) append(", ${files.size} ${if (files.size == 1) "file" else "files"}")
                        },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { onConfirm(folders, files) }, enabled = !busy) { Text(confirmLabel) }
                }
            }
        }
    }
}

/**
 * Choosing drive folders for Custom folders. Each ticked folder shows its sub-folders as separate cards; folders
 * that are already custom folders start ticked, and unticking one removes that card (the drive is not touched).
 */
@Composable
internal fun SourceFolderPicker(vm: LibraryViewModel, existing: List<com.deepak.videolibrary.data.MatchFolderEntity>, onClose: () -> Unit) {
    val added = remember(existing) { existing.mapNotNull { it.sourcePath?.trim('/') }.toSet() }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(vm.organizerError) { if (vm.organizerError != null) saving = false }
    DrivePicker(
        vm = vm,
        title = "Choose drive folders",
        explanation = "Tick a folder to add it with everything inside; its sub-folders each get their own card. Tick single videos to add them too: they get a card named after the folder they are in. Untick a folder to take it off Custom folders. Files on your drive are not changed.",
        confirmLabel = if (saving) "Saving…" else "Done",
        withFiles = true,
        initialFolders = added,
        marked = added,
        busy = saving,
        onDismiss = onClose,
    ) { folders, files ->
        vm.addFilesAsOwnFolders(files)
        val covered = { p: String -> folders.any { isWithinFolder(p, it) } }
        val remove = existing.filter { f -> f.sourcePath != null && !covered(f.sourcePath.trim('/')) }
        val chosen = folders - added
        if (chosen.isEmpty() && remove.isEmpty()) {
            onClose()
        } else {
            saving = true
            vm.applyDriveSelection(chosen, remove, onDone = onClose)
        }
    }
}
