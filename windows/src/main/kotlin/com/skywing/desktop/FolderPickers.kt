package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Choosing folders (each with everything inside it) and, with [withFiles], single videos, from the places in the
 * library: folders on this PC and network shares added in Files. Click a folder's name to open it; tick the box to
 * choose it. Something inside a ticked folder is already included. [subfolderChoice] adds the Include sub folders
 * switch used when adding drive folders to Custom folders. Nothing on disk changes.
 */
@Composable
internal fun DrivePicker(
    title: String,
    explanation: String,
    roots: List<String>,
    library: List<WindowsMediaEntry>,
    withFiles: Boolean,
    initialFolders: Collection<String>,
    initialFiles: Collection<String>,
    onDismiss: () -> Unit,
    subfolderChoice: Boolean = false,
    confirmLabel: String = "Done",
    onDone: (folders: List<String>, files: List<String>, includeSubfolders: Boolean) -> Unit,
) {
    val theme = LocalSkyTheme.current
    var current by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf(initialFolders.associateBy { it.lowercase() }) }
    var files by remember { mutableStateOf(initialFiles.associateBy { it.lowercase() }) }
    var includeSubfolders by remember { mutableStateOf(true) }
    val videos = remember(library) { library.map { File(it.path) } }
    val contents by produceState<LocalOriginalFolderContents?>(null, current, videos, roots) {
        value = null
        value = withContext(Dispatchers.Default) { localOriginalFolderContents(roots, current?.let(::File), videos) }
    }
    fun inTickedFolder(path: String) = folders.values.any { it.lowercase() != path.lowercase() && FolderLogic.isWithin(path, it) }
    val listState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.menu,
        titleContentColor = theme.ink,
        textContentColor = theme.ink,
        title = { Text(title) },
        text = {
            Column(Modifier.width(680.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(explanation, color = theme.muted, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkyIconButton(SkyIcon.Back, "Up one folder", {
                        val here = current ?: return@SkyIconButton
                        current = if (roots.any { it.equals(here, ignoreCase = true) }) null else File(here).parent
                    }, enabled = current != null)
                    Text(current ?: "Your folders and shares", color = theme.ink, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().height(380.dp).skyGlass(RoundedCornerShape(16.dp)).padding(6.dp)) {
                    val shown = contents
                    when {
                        shown == null -> EmptyNote("Loading…")
                        shown.folders.isEmpty() && (!withFiles || shown.videos.isEmpty()) ->
                            EmptyNote(if (current == null) "Add folders or a network share in Files first." else "Nothing more inside this folder.")
                        else -> LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState) {
                            items(shown.folders, key = { "d:" + it.absolutePath }) { dir ->
                                val path = dir.absolutePath
                                val inherited = inTickedFolder(path)
                                PickRow(
                                    icon = SkyIcon.Folder,
                                    name = if (current == null) path else dir.name,
                                    checked = inherited || path.lowercase() in folders,
                                    enabled = !inherited,
                                    onCheck = { tick -> folders = if (tick) folders + (path.lowercase() to path) else folders - path.lowercase() },
                                    onOpen = { current = path },
                                )
                            }
                            if (withFiles) items(shown.videos, key = { "f:" + it.absolutePath }) { video ->
                                val path = video.absolutePath
                                val inherited = inTickedFolder(path)
                                PickRow(
                                    icon = SkyIcon.Play,
                                    name = video.name,
                                    checked = inherited || path.lowercase() in files,
                                    enabled = !inherited,
                                    onCheck = { tick -> files = if (tick) files + (path.lowercase() to path) else files - path.lowercase() },
                                    onOpen = null,
                                )
                            }
                        }
                    }
                    ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${folders.size} ${if (folders.size == 1) "folder" else "folders"}" +
                            if (withFiles) " · ${files.size} ${if (files.size == 1) "video" else "videos"} chosen" else " chosen",
                        color = theme.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    if (subfolderChoice) {
                        Text("Include sub folders", color = theme.ink, style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = includeSubfolders,
                            onCheckedChange = { includeSubfolders = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = theme.accent, checkedThumbColor = theme.onAccent),
                        )
                    }
                }
            }
        },
        confirmButton = {
            SkyButton(confirmLabel, {
                onDismiss()
                onDone(folders.values.toList(), files.values.toList(), includeSubfolders)
            }, enabled = !subfolderChoice || folders.isNotEmpty())
        },
        dismissButton = { SkyButton("Cancel", onDismiss, primary = false) },
    )
}

@Composable
private fun PickRow(icon: SkyIcon, name: String, checked: Boolean, enabled: Boolean, onCheck: (Boolean) -> Unit, onOpen: (() -> Unit)?) {
    val theme = LocalSkyTheme.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheck,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = theme.accent, checkmarkColor = theme.onAccent, uncheckedColor = theme.muted),
        )
        Row(
            Modifier.weight(1f).skyClickable({ if (onOpen != null) onOpen() else if (enabled) onCheck(!checked) }).padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SkyIconView(icon, theme.accent, size = 20.dp)
            Text(name, color = theme.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onOpen != null) SkyIconView(SkyIcon.ChevronRight, theme.muted, size = 18.dp)
        }
    }
}
