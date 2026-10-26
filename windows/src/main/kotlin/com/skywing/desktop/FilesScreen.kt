package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
internal fun FilesScreen(library: LibraryModel, onPlay: (File, List<File>) -> Unit, onRename: (File) -> Unit,
    registerBack: (((() -> Boolean)?) -> Unit) = {}) {
    var section by remember { mutableStateOf(0) }
    if (section == 0) SideEffect { registerBack(null) }
    DisposableEffect(Unit) { onDispose { registerBack(null) } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("Files", "Add the places your videos are kept")
        SkyChipBar(listOf("This PC", "Network shares"), section, { section = it })
        if (section == 0) LocalSources(library) else NetworkSources(library, onPlay, onRename, registerBack)
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

    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(end = 14.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    ColumnScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkSources(library: LibraryModel, onPlay: (File, List<File>) -> Unit, onRename: (File) -> Unit,
    registerBack: (((() -> Boolean)?) -> Unit)) {
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
    // The share as the library keeps it (main address), even when it was opened through another address.
    var mainRoot by remember { mutableStateOf<String?>(null) }

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

    SideEffect {
        registerBack {
            val share = root
            val folder = directory
            if (loading || share == null || folder == null) false
            else {
                if (folder == share) {
                    root = null
                    directory = null
                } else {
                    load(share, folder.parent?.takeIf { it.startsWith(share) } ?: share)
                }
                true
            }
        }
    }

    LaunchedEffect(library.revision) {
        val share = root
        val folder = directory
        if (share != null && folder != null && !loading) load(share, folder)
    }

    /**
     * Opens [main] (\\server\share). When its server does not answer on the SMB port, the [others] addresses are
     * tried (for example 192.168.71.249 when 192.168.71.250 is down) and the one that answered is remembered. The
     * library keeps the main address either way, like Other addresses on Android.
     */
    fun connect(main: String, others: String) {
        val clean = main.trim().trimEnd('\\', '/')
        if (clean.isEmpty()) {
            message = "Enter a network path."
            return
        }
        if (loading) return
        loading = true
        message = "Opening network share…"
        scope.launch {
            try {
                browser.shareRoot(clean)
                val host = requireNotNull(ShareHosts.hostOf(clean)) { "Enter a network path such as \\\\server\\share" }
                ShareHosts.register(clean, others)
                val answering = withContext(Dispatchers.IO) { ShareHosts.resolve(host) } ?: host
                val share = browser.shareRoot(ShareHosts.withHost(clean, answering))
                val result = browser.list(share, share)
                root = share
                directory = share
                mainRoot = clean
                listing = result
                message = when {
                    !answering.equals(host, ignoreCase = true) -> "Connected using the other address $answering."
                    result.truncated -> "Connected. Showing the first 500 items."
                    else -> "Connected. " + result.entries.size + " items."
                }
                library.changed()
            } catch (error: Exception) {
                message = (error.message ?: "Cannot open network share") +
                    if (ShareHosts.parse(others).isEmpty()) "" else " (the other addresses did not answer either)"
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SkyField(profileName, { profileName = it }, "Name (for example Home server)", Modifier.width(220.dp))
            SkyField(primaryAddress, { primaryAddress = it }, "\\\\server\\share", Modifier.weight(1f))
            SkyField(alternateAddress, { alternateAddress = it }, "Other addresses, e.g. 192.168.71.249", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyButton("Open share", { connect(primaryAddress, alternateAddress) }, enabled = !loading, icon = SkyIcon.Cloud)
            SkyButton("Save", {
                try {
                    profiles = profileStore.save(WindowsShareProfile(profileName.trim(), primaryAddress.trim(), alternateAddress.trim()))
                    ShareHosts.register(primaryAddress, alternateAddress)
                    message = "Share saved."
                } catch (error: Exception) {
                    message = error.message ?: "Could not save the share"
                }
            }, enabled = !loading, primary = false)
        }
        StatusLine(message)
        if (profiles.isNotEmpty()) {
            // Wraps onto more lines instead of running off the side.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                profiles.forEach { profile ->
                    SkyChip(profile.name, false, {
                        profileName = profile.name
                        primaryAddress = profile.primaryAddress
                        alternateAddress = profile.alternateAddress
                        connect(profile.primaryAddress, profile.alternateAddress)
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
                            val sourceName = profileName.ifBlank { mainRoot ?: share.toString() }
                            val sourceId = "SMB:" + sourceName.trim().lowercase()
                            // Saved under the main address, so the library stays the same whichever address answered.
                            withContext(Dispatchers.IO) { library.store.replaceSource(sourceId, "SMB", sourceName, result.files, ShareHosts::canonical) }
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
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 14.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
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
                        if (!entry.directory) SkyIconButton(SkyIcon.Edit, "Rename video", { onRename(entry.file) }, enabled = !loading)
                    }
                }
            }
            ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}
