package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.skywing.desktop.playback.SmoothingMode
import com.skywing.desktop.playback.SmoothingStatus

/** A separate native dialog stays above the heavyweight libmpv Canvas in fullscreen. */
@Composable
internal fun SmoothingDialog(mode: SmoothingMode, status: SmoothingStatus,
    onMode: (SmoothingMode) -> Unit, onClose: () -> Unit) {
    DialogWindow(onCloseRequest = onClose, title = "Motion options",
        state = rememberDialogState(width = 560.dp, height = 680.dp)) {
        Column(Modifier.fillMaxSize().background(LocalSkyTheme.current.background)
            .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SmoothingOptions(mode, status, onMode)
            SkyButton("Done", onClose)
        }
    }
}

@Composable
internal fun SmoothingOptions(mode: SmoothingMode, status: SmoothingStatus, onMode: (SmoothingMode) -> Unit) {
    val theme = LocalSkyTheme.current
    Text("Motion & smoothing", color = theme.ink, style = MaterialTheme.typography.titleMedium)
    Text("Motion creates in-between frames. This preview uses Balanced quality and a target near 120 fps. HDR motion is still being tested.",
        color = theme.muted, style = MaterialTheme.typography.bodySmall)
    SmoothingMode.entries.forEach { option ->
        val unavailable = status.capabilities.unavailable(option)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SkyButton((if (mode == option) "✓ " else "") + option.label +
                (if (option == SmoothingMode.VRR) " · original motion" else ""),
                { onMode(option) }, enabled = unavailable == null, primary = mode == option)
            if (unavailable != null) Text(unavailable, color = theme.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(status.detail, color = theme.ink, style = MaterialTheme.typography.bodySmall)
    var copied by remember { mutableStateOf(false) }
    SkyButton(if (copied) "Copied" else "Copy playback report", {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(status.report), null) }
            .onSuccess { copied = true }
    }, primary = false)
    Text("Blend is a lighter option. G-SYNC still needs to be enabled in your graphics driver.",
        color = theme.muted, style = MaterialTheme.typography.bodySmall)
}
