package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SmbSharePanel(
    indexStore: WindowsMediaIndex,
    onIndexUpdated: () -> Unit,
    onPlayQueue: (File, List<File>) -> Unit,
) {
    val browser = remember { WindowsShareBrowser() }
    val profileStore = remember { WindowsShareProfileStore() }
    val scope = rememberCoroutineScope()
    var profileName by remember { mutableStateOf("") }
    var primaryAddress by remember { mutableStateOf("") }
    var alternateAddress by remember { mutableStateOf("") }
    var profiles by remember {
        mutableStateOf(runCatching { profileStore.load() }.getOrDefault(emptyList()))
    }
    var root by remember { mutableStateOf<Path?>(null) }
    var directory by remember { mutableStateOf<Path?>(null) }
    var listing by remember { mutableStateOf(WindowsShareBrowser.Listing(emptyList(), false)) }
    var message by remember { mutableStateOf("Enter a network share path. SkyWing uses your Windows sign-in.") }
    var loading by remember { mutableStateOf(false) }
    var indexedCount by remember { mutableStateOf<Int?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Color(0xFF80CBC4),
        focusedContainerColor = Color(0xFF1B1E26),
        unfocusedContainerColor = Color(0xFF1B1E26),
        focusedBorderColor = Color(0xFF80CBC4),
        unfocusedBorderColor = Color(0xFF7C8291),
        focusedLabelColor = Color(0xFF80CBC4),
        unfocusedLabelColor = Color(0xFFC6C8D1),
    )

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
            message = "Enter a primary network path."
            return
        }
        if (loading) return
        loading = true
        message = "Opening network share…"
        scope.launch {
            val failures = mutableListOf<String>()
            try {
                candidates.forEachIndexed { index, candidate ->
                    try {
                        val share = browser.shareRoot(candidate)
                        val result = browser.list(share, share)
                        root = share
                        directory = share
                        listing = result
                        primaryAddress = candidate
                        message = if (index > 0) {
                            "Connected using the alternate address."
                        } else if (result.truncated) {
                            "Connected. Showing the first 500 items."
                        } else {
                            "Connected. " + result.entries.size + " items."
                        }
                        return@launch
                    } catch (error: Exception) {
                        failures += (error.message ?: "Cannot open network folder")
                    }
                }
                message = failures.lastOrNull() ?: "Cannot open network share"
            } finally {
                loading = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Open a Windows network share", color = Color.White)
        OutlinedTextField(
            value = profileName,
            onValueChange = { profileName = it },
            label = { Text("Profile name") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = primaryAddress,
            onValueChange = { primaryAddress = it },
            label = { Text("Network path, for example \\\\server\\share") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = alternateAddress,
            onValueChange = { alternateAddress = it },
            label = { Text("Alternate path (optional)") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !loading, onClick = {
                connect(listOf(primaryAddress, alternateAddress))
            }) { Text("Open share") }
            Button(enabled = !loading, onClick = {
                try {
                    profiles = profileStore.save(
                        WindowsShareProfile(profileName.trim(), primaryAddress.trim(), alternateAddress.trim())
                    )
                    message = "Share profile saved."
                } catch (error: Exception) {
                    message = error.message ?: "Could not save share profile"
                }
            }) { Text("Save profile") }
        }
        Text(message, color = Color(0xFFE5CB87))

        if (profiles.isNotEmpty()) {
            Text("Saved shares", color = Color.White)
            profiles.forEach { profile ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !loading, onClick = {
                        profileName = profile.name
                        primaryAddress = profile.primaryAddress
                        alternateAddress = profile.alternateAddress
                        connect(listOf(profile.primaryAddress, profile.alternateAddress))
                    }) { Text("Open " + profile.name) }
                    Button(enabled = !loading, onClick = {
                        try {
                            profiles = profileStore.delete(profile.name)
                            message = "Share profile removed."
                        } catch (error: Exception) {
                            message = error.message ?: "Could not remove share profile"
                        }
                    }) { Text("Remove") }
                }
            }
        }

        val share = root
        val folder = directory
        if (share != null && folder != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !loading && folder != share, onClick = {
                    val parent = folder.parent
                    if (parent != null && parent.startsWith(share)) load(share, parent)
                }) { Text("Up") }
                Button(enabled = !loading, onClick = { load(share, folder) }) { Text("Refresh") }
            }
            Text(folder.toString(), color = Color.White)
            Button(enabled = !loading, onClick = {
                loading = true
                message = "Scanning this share into the library…"
                scope.launch {
                    try {
                        val result = browser.scan(share, share)
                        val sourceName = profileName.ifBlank { share.toString() }
                        val sourceId = "SMB:" + sourceName.trim().lowercase()
                        withContext(Dispatchers.IO) { indexStore.replaceSource(sourceId, "SMB", sourceName, result.files) }
                        indexedCount = result.files.size
                        onIndexUpdated()
                        message = if (result.truncated) "Indexed the first 20,000 videos from this share." else "Indexed " + result.files.size + " videos from this share."
                    } catch (error: Exception) {
                        message = error.message ?: "Could not scan this share"
                    } finally {
                        loading = false
                    }
                }
            }) { Text(if (indexedCount == null) "Scan share into library" else "Refresh share index") }
            LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                items(listing.entries, key = { it.file.absolutePath }) { entry ->
                    Button(
                        enabled = !loading,
                        onClick = {
                            if (entry.directory) load(share, entry.file.toPath())
                            else onPlayQueue(entry.file, listing.entries.filter { !it.directory }.map { it.file })
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text((if (entry.directory) "Folder  " else "Video  ") + entry.file.name)
                    }
                }
            }
        }
    }
}
