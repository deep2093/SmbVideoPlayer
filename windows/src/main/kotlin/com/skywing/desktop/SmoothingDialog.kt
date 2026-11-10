package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    Text("Frame blending reduces timing judder. It does not generate new motion like SVP or RIFE. " +
        "Auto follows the active display; FPS choices require a matching display refresh mode.",
        color = theme.muted, style = MaterialTheme.typography.bodySmall)
    Text("For G-SYNC / FreeSync, select original-motion timing below and enable VRR in your graphics driver. " +
        "SkyWing cannot verify or enable driver VRR. Use fixed refresh for frame blending.",
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
    Text("Source: ${status.capabilities.resolution}. No 1080p processing limit: 1440p and 4K use the normal video renderer. " +
        "Sustained dropped frames turn blending off for this video; toggle off/on to retry. " +
        "HDR blending awaits validation. SVP/RIFE motion generation is not included.",
        color = theme.muted, style = MaterialTheme.typography.bodySmall)
}
