package com.deepak.videolibrary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepak.videolibrary.smb.SmbEntry
import com.deepak.videolibrary.smb.SmbErrors
import kotlinx.coroutines.CancellationException

/** Live drive browser: selection remains independent of the folder being browsed. */
@Composable
internal fun SourceFolderPicker(vm: LibraryViewModel, onClose: () -> Unit) {
    val root = vm.settings.rootPath.trim('/')
    var path by rememberSaveable { mutableStateOf(root) }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var recursive by rememberSaveable { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    fun toggle(value: String) { selected = ArrayList(if (value in selected) selected - value else selected + value) }
    LaunchedEffect(path, attempt) {
        loading = true; error = null; entries = emptyList()
        try { entries = vm.folderChoices(path) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = SmbErrors.describe(e) }
        finally { loading = false }
    }
    LaunchedEffect(vm.organizerError) { if (vm.organizerError != null) saving = false }
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text("Choose drive folders") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${selected.size} selected", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = path != root && !saving, onClick = { path = path.substringBeforeLast('/', "").takeIf { isWithinFolder(it, root) } ?: root }) { Text("Up") }
                    Text(path.ifBlank { vm.settings.share }, modifier = Modifier.weight(1f), maxLines = 2)
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { toggle(path) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(path in selected, onCheckedChange = { toggle(path) }, enabled = !saving)
                    Text("Select this folder")
                }
                Row {
                    TextButton(enabled = !loading && !saving, onClick = {
                        selected = ArrayList((selected + entries.map { listOf(path, it.name).filter(String::isNotBlank).joinToString("/") }).distinct())
                    }) { Text("Select all here") }
                    TextButton(enabled = !saving, onClick = { selected = arrayListOf() }) { Text("Clear") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { attempt++ }) { Text("Retry") } }
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)) {
                    items(entries, key = { it.name }) { entry ->
                        val child = listOf(path, entry.name).filter(String::isNotBlank).joinToString("/")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(child in selected, onCheckedChange = { toggle(child) }, enabled = !saving)
                            TextButton(enabled = !saving, onClick = { path = child }, modifier = Modifier.weight(1f)) {
                                Text(entry.name, modifier = Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, "Browse ${entry.name}")
                            }
                        }
                    }
                    if (!loading && error == null && entries.isEmpty()) item { Text("No subfolders here.") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(recursive, { recursive = it }, enabled = !saving)
                    Text("Include videos in subfolders")
                }
                Text("Folders stay on your drive. New videos appear after a scan.", style = MaterialTheme.typography.bodySmall)
                vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty() && !saving, onClick = {
            saving = true
            vm.addSourceFolders(selected.toSet(), recursive, onSaved = onClose)
        }) { Text(if (saving) "Adding…" else "Add folders") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onClose) { Text("Cancel") } },
    )
}
