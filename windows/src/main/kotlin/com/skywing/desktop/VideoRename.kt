package com.skywing.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skywing.desktop.artwork.ArtworkRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Renames the real video and makes its saved library choices follow the new path. */
internal class VideoRenameService(
    private val library: LibraryModel,
    private val organizer: Organizer,
    private val artwork: ArtworkRepository,
    private val history: WindowsPlaybackHistoryStore,
) {
    suspend fun rename(file: File, requestedBase: String): File = withContext(Dispatchers.IO) {
        val base = requestedBase.trim()
        require(base.isNotEmpty() && base.length <= 200 && base.none { it in "<>:\"/\\|?*" || it.code < 32 }) {
            "Use a name without Windows file-name symbols."
        }
        require(!base.endsWith('.') && !base.endsWith(' ')) { "The name cannot end with a dot or space." }
        require(base.substringBefore('.').uppercase() !in RESERVED_NAMES) { "That name is reserved by Windows." }
        require(file.isFile) { "The video is no longer available." }
        val extension = file.extension
        val destination = File(file.parentFile, if (extension.isEmpty()) base else "$base.$extension")
        require(!destination.exists()) { "A file with that name already exists." }
        val oldPath = ShareHosts.canonical(file.absolutePath)
        val newPath = ShareHosts.canonical(destination.absolutePath)
        Files.move(file.toPath(), destination.toPath())
        // The real rename succeeded; follow it in the index and saved app choices.
        library.store.renamePath(oldPath, newPath, destination)
        organizer.renameVideoPath(oldPath, newPath)
        artwork.renameVideoPath(oldPath, newPath)
        history.renamePath(oldPath, newPath)
        library.changed()
        destination
    }

    private companion object {
        val RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).flatMap { listOf("COM$it", "LPT$it") }
    }
}

@Composable
internal fun RenameVideoDialog(file: File, rename: suspend (File, String) -> File, onDismiss: () -> Unit) {
    val theme = LocalSkyTheme.current
    val scope = rememberCoroutineScope()
    var name by remember(file) { mutableStateOf(file.nameWithoutExtension) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var busy by remember(file) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = theme.menu,
        title = { Text("Rename video") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This changes the actual file name. Its video extension stays the same.", style = MaterialTheme.typography.bodySmall)
                SkyField(name, { name = it; error = null }, "File name", Modifier.fillMaxWidth())
                error?.let { Text(it, color = theme.accent, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            SkyButton("Rename", {
                busy = true
                scope.launch {
                    try { rename(file, name); onDismiss() }
                    catch (e: Exception) { error = e.message ?: "Could not rename this video." }
                    finally { busy = false }
                }
            }, enabled = !busy && name.isNotBlank() && name.trim() != file.nameWithoutExtension)
        },
        dismissButton = { SkyButton("Cancel", onDismiss, enabled = !busy, primary = false) },
    )
}
