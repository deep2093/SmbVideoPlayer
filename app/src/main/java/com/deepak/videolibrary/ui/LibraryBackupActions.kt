package com.deepak.videolibrary.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryBackupActions(vm: LibraryViewModel) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportLibrary) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importLibrary) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Library backup", style = MaterialTheme.typography.titleMedium)
            Text("Save collections, assignments, hidden items and playback history. Passwords and videos are excluded. Scan your videos before restoring.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { export.launch("video-library-backup.json") }) { Text("Export") }
                TextButton(onClick = { restore.launch(arrayOf("application/json","text/plain")) }) { Text("Restore") }
            }
        }
    }
}
