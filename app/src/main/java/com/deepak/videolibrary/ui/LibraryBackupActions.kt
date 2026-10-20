package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

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
    // Sits inside the Library section of Settings, which already provides the card.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ui_library_backup_51ed40f), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ui_save_collections_assignments_hidden_items__48ef66a), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export.launch("video-library-backup.json") }) { Text(stringResource(R.string.ui_export_f3e4fad)) }
            TextButton(onClick = { restore.launch(arrayOf("application/json","text/plain")) }) { Text(stringResource(R.string.ui_restore_3cbe6d6)) }
        }
    }
}
