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
import kotlinx.coroutines.launch

@Composable
internal fun SmbSharePanel(onVideo: (File) -> Unit) {
    val browser = remember { WindowsShareBrowser() }
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var root by remember { mutableStateOf<Path?>(null) }
    var directory by remember { mutableStateOf<Path?>(null) }
    var listing by remember { mutableStateOf(WindowsShareBrowser.Listing(emptyList(), false)) }
    var message by remember { mutableStateOf("Enter a network share path. SkyWing uses your Windows sign-in.") }
    var loading by remember { mutableStateOf(false) }

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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Open a Windows network share", color = Color.White)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Network path, for example \\\\server\\share") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = !loading, onClick = {
            try {
                val share = browser.shareRoot(address)
                load(share, share)
            } catch (error: IllegalArgumentException) {
                message = error.message ?: "Invalid network path"
            }
        }) { Text("Open share") }
        Text(message, color = Color(0xFFE5CB87))
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
            LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                items(listing.entries, key = { it.file.absolutePath }) { entry ->
                    Button(
                        enabled = !loading,
                        onClick = {
                            if (entry.directory) load(share, entry.file.toPath())
                            else onVideo(entry.file)
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
