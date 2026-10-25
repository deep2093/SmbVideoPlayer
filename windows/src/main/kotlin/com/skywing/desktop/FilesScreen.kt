package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Files: where the videos come from. Folders on this PC and Windows network shares. */
@Composable
internal fun FilesScreen(library: LibraryModel, onPlay: (File, List<File>) -> Unit) {
    var section by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("Files", "Add the places your videos are kept")
        SkyChipBar(listOf("This PC", "Network shares"), section, { section = it })
        if (section == 0) LocalSources(library) else NetworkSources(library, onPlay)
    }
}

@Composable
private fun StatusLine(text: String) {
    val theme = LocalSkyTheme.current
    Text(text, color = theme.muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SourceRow(icon: SkyIcon, title: String, detail: String? = null, trailing: @Composable () -> Unit = {}) {
    val theme = LocalSkyTheme.current
    Row(
        Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkyIconView(icon, theme.accent)
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = theme.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun LocalSources(library: LibraryModel) {
    val store = remember { LocalFolderStore() }
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf(runCatching { store.load() }.getOrDefault(emptyList())) }
    var message by remember { mutableStateOf("Choose folders on this PC. SkyWing only scans the folders you add.") }
    var scanning by remember { mutableStateOf(false) }

    fun scan(paths: List<String>) {
        if (scanning) return
        scanning = true
        message = "Scanning your folders…"
        scope.launch {
            try {
                val result = LocalVideoScanner.scan(paths)
                withContext(Dispatchers.IO) { library.store.replaceSource("PC", "PC", "This PC", result.files) }
                library.changed()
                message = buildString {
                    append(result.files.size).append(" videos found")
                    if (result.unavailableFolders > 0) append("; ").append(result.unavailableFolders).append(" folder(s) unavailable")
                    if (result.truncated) append("; showing the first 20,000")
                }
            } catch (error: Exception) {
                message = error.message ?: "Folder scan failed"
            } finally {
                scanning = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyButton("Add folder", {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Add a SkyWing video folder"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    try {
                        folders = store.add(chooser.selectedFile.absolutePath)
                        scan(folders)
                    } catch (error: Exception) {
                        message = error.message ?: "Could not add folder"
                    }
                }
            }, enabled = !scanning, icon = SkyIcon.Add)
            SkyButton("Scan again", { scan(folders) }, enabled = !scanning && folders.isNotEmpty(), primary = false, icon = SkyIcon.Refresh)
        }
        StatusLine(message)
        if (folders.isEmpty()) {
            EmptyNote("No folders added yet.")
        }
        folders.forEach { folder ->
            SourceRow(SkyIcon.Computer, File(folder).name.ifBlank { folder }, folder) {
                SkyIconButton(SkyIcon.Delete, "Remove folder", {
                    try {
                        folders = store.remove(folder)
                        if (folders.isEmpty()) {
                            scope.launch {
                                withContext(Dispatchers.IO) { library.store.replaceSource("PC", "PC", "This PC", emptyList()) }
                                library.changed()
                            }
                            message = "No folders selected."
                        } else scan(folders)
                    } catch (error: Exception) {
                        message = error.message ?: "Could not remove folder"
                    }
                }, enabled = !scanning)
            }
        }
    }
}

@Composable
private fun NetworkSources(library: LibraryModel, onPlay: (File, List<File>) -> Unit) {
    val browser = remember { WindowsShareBrowser() }
    val profileStore = remember { WindowsShareProfileStore() }
    val scope = rememberCoroutineScope()
    var profileName by remember { mutableStateOf("") }
    var primaryAddress by remember { mutableStateOf("") }
    var alternateAddress by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf(runCatching { profileStore.load() }.getOrDefault(emptyList())) }
    var root by remember { mutableStateOf<Path?>(null) }
    var directory by remember { mutableStateOf<Path?>(null) }
    var listing by remember { mutableStateOf(WindowsShareBrowser.Listing(emptyList(), false)) }
    var message by remember { mutableStateOf("Enter a network share. SkyWing uses your Windows sign-in.") }
    var loading by remember { mutableStateOf(false) }
    var indexedCount by remember { mutableStateOf<Int?>(null) }

    fun load(share: Path, folder: Path) {
        if (loading) return
        loading = true
        message = "Opening network folder…"
        scope.launch {
            try {
                val result = browser.list(share, folder)
                root = share
                directory = folder
                listing = result
                message = if (result.truncated) "Showing the first 500 items." else result.entries.size.toString() + " items"
            } catch (error: Exception) {
                message = error.message ?: "Cannot open network folder"
            } finally {
                loading = false
            }
        }
    }

    fun connect(addresses: List<String>) {
        val candidates = addresses.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) {
            message = "Enter a network path."
            return
        }
        if (loading) return
        loading = true
        message = "Opening network share…"
        scope.launch {
            val failures = mutableListOf<String>()
            var connected = false
            try {
                for ((index, candidate) in candidates.withIndex()) {
                    try {
                        val share = browser.shareRoot(candidate)
                        val result = browser.list(share, share)
                        root = share
                        directory = share
                        listing = result
                        primaryAddress = candidate
                        message = if (index > 0) {
                            "Connected using the other address."
                        } else if (result.truncated) {
                            "Connected. Showing the first 500 items."
                        } else {
                            "Connected. " + result.entries.size + " items."
                        }
                        connected = true
                        break
                    } catch (error: Exception) {
                        failures += (error.message ?: "Cannot open network folder")
                    }
                }
                if (!connected) message = failures.lastOrNull() ?: "Cannot open network share"
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SkyField(profileName, { profileName = it }, "Name (for example Home server)", Modifier.width(220.dp))
            SkyField(primaryAddress, { primaryAddress = it }, "\\\\server\\share", Modifier.weight(1f))
            SkyField(alternateAddress, { alternateAddress = it }, "Other address (optional)", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyButton("Open share", { connect(listOf(primaryAddress, alternateAddress)) }, enabled = !loading, icon = SkyIcon.Cloud)
            SkyButton("Save", {
                try {
                    profiles = profileStore.save(WindowsShareProfile(profileName.trim(), primaryAddress.trim(), alternateAddress.trim()))
                    message = "Share saved."
                } catch (error: Exception) {
                    message = error.message ?: "Could not save the share"
                }
            }, enabled = !loading, primary = false)
        }
        StatusLine(message)
        if (profiles.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                profiles.forEach { profile ->
                    SkyChip(profile.name, false, {
                        profileName = profile.name
                        primaryAddress = profile.primaryAddress
                        alternateAddress = profile.alternateAddress
                        connect(listOf(profile.primaryAddress, profile.alternateAddress))
                    }, icon = SkyIcon.Cloud)
                    SkyIconButton(SkyIcon.Close, "Remove " + profile.name, {
                        try {
                            profiles = profileStore.delete(profile.name)
                            message = "Share removed."
                        } catch (error: Exception) {
                            message = error.message ?: "Could not remove the share"
                        }
                    }, box = 30.dp, glyph = 16.dp, enabled = !loading)
                }
            }
        }
        val share = root
        val folder = directory
        if (share != null && folder != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SkyIconButton(SkyIcon.Back, "Up one folder", {
                    val parent = folder.parent
                    if (parent != null && parent.startsWith(share)) load(share, parent)
                }, enabled = !loading && folder != share)
                SkyIconButton(SkyIcon.Refresh, "Refresh", { load(share, folder) }, enabled = !loading)
                PageTitle(folder.toString(), null, Modifier.weight(1f))
                SkyButton(if (indexedCount == null) "Add to library" else "Update library", {
                    loading = true
                    message = "Adding this share to your library…"
                    scope.launch {
                        try {
                            val result = browser.scan(share, share)
                            val sourceName = profileName.ifBlank { share.toString() }
                            val sourceId = "SMB:" + sourceName.trim().lowercase()
                            withContext(Dispatchers.IO) { library.store.replaceSource(sourceId, "SMB", sourceName, result.files) }
                            indexedCount = result.files.size
                            library.changed()
                            message = if (result.truncated) "Added the first 20,000 videos from this share." else "Added " + result.files.size + " videos from this share."
                        } catch (error: Exception) {
                            message = error.message ?: "Could not scan this share"
                        } finally {
                            loading = false
                        }
                    }
                }, enabled = !loading, icon = SkyIcon.VideoLibrary)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(listing.entries, key = { it.file.absolutePath }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(14.dp))
                            .skyClickable({
                                if (loading) return@skyClickable
                                if (entry.directory) load(share, entry.file.toPath())
                                else onPlay(entry.file, listing.entries.filter { !it.directory }.map { it.file })
                            })
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val theme = LocalSkyTheme.current
                        SkyIconView(if (entry.directory) SkyIcon.Folder else SkyIcon.Play, theme.accent)
                        Text(entry.file.name, color = theme.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
