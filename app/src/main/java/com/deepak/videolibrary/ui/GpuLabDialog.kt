package com.deepak.videolibrary.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.deepak.videolibrary.player.GpuLabRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs the GPU lab (see GpuLabRunner) and saves/shares its report. */
@Composable
internal fun GpuLabDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var outcomes by remember { mutableStateOf<List<GpuLabRunner.Outcome>>(emptyList()) }
    var saved by remember { mutableStateOf<Uri?>(null) }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("GPU lab") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Runs the advanced smoothing engine on the GPU in a separate process, ${GpuLabRunner.variants.size} times " +
                        "with a different fix each time. Takes about 2–6 minutes; keep the app open. If the GPU hangs, " +
                        "that run is stopped automatically and the report records exactly where.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
                if (outcomes.isNotEmpty()) Spacer(Modifier.height(8.dp))
                outcomes.forEach { Text("${it.variant}: ${it.status}", style = MaterialTheme.typography.bodySmall) }
                if (!running && outcomes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (saved != null) "Report saved in Downloads (SkyWing-gpu-lab-….txt). Share it or attach that file in the chat."
                        else "Could not save the report file.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (!running && outcomes.isEmpty()) {
                TextButton(onClick = {
                    running = true
                    scope.launch {
                        val result = GpuLabRunner.run(context) { status = it }
                        outcomes = result
                        status = "Finished"
                        saved = withContext(Dispatchers.IO) { GpuLabRunner.saveReport(context, result) }
                        running = false
                    }
                }) { Text("Start") }
            } else if (!running && saved != null) {
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, saved)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(share, "Share GPU lab report"))
                }) { Text("Share report") }
            }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismiss) { Text("Close") }
        },
    )
}
