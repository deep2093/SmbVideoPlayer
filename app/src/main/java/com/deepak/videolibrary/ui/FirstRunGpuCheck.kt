package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import com.deepak.videolibrary.player.GpuLabRunner
import com.deepak.videolibrary.player.NativeSvpStream
import kotlinx.coroutines.launch

/**
 * One-time smoothing set-up shown after the opening animation: measures which smoothing qualities this device's GPU
 * can sustain at 60 fps and saves the answer so the check never has to run again.
 */
@Composable
internal fun FirstRunGpuCheck() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var needed by remember { mutableStateOf(NativeSvpStream.available && GpuQualityStore.read(context) == null) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    if (!needed) return
    AlertDialog(
        onDismissRequest = { if (!running) needed = false },
        title = { Text("Set up smooth motion") },
        text = {
            Column {
                when {
                    result != null -> Text(
                        "Done. This device can smooth up to ${com.deepak.videolibrary.player.NativeSvpTuning.LEVEL_NAMES[result!!.substringBefore('|').toIntOrNull()?.coerceIn(0, 3) ?: 2]}. " +
                            "You can change the quality in the player menu.",
                    )
                    running -> Text(status.ifEmpty { "Starting…" }, style = MaterialTheme.typography.bodyMedium)
                    else -> Text(
                        "SkyWing will test your phone's graphics chip once (about 1 minute) to find the best smoothing quality it " +
                            "can keep at 60 fps. The result is saved, so this is only needed once. Keep the app open.",
                    )
                }
            }
        },
        confirmButton = {
            when {
                result != null -> TextButton(onClick = { needed = false }) { Text("OK") }
                running -> {}
                else -> TextButton(onClick = {
                    running = true
                    scope.launch {
                        val text = GpuLabRunner.runLevels(context) { status = it }
                        GpuQualityStore.save(context, text)
                        GpuQualityStore.clearFailure(context)
                        result = text
                        running = false
                    }
                }) { Text("Start test") }
            }
        },
        dismissButton = {
            if (!running && result == null) TextButton(onClick = { needed = false }) { Text("Later") }
        },
    )
}
