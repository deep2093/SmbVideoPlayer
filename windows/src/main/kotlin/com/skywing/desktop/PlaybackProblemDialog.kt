package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

/** Why a video could not be played. */
internal data class PlaybackProblem(val title: String, val detail: String)

/**
 * Tells the user that a video could not be played. A separate window, like Motion options, so it shows above the
 * native video surface in a window or full screen. Closing it leaves the player and the library as they are.
 */
@Composable
internal fun PlaybackProblemDialog(problem: PlaybackProblem, onClose: () -> Unit) {
    val theme = LocalSkyTheme.current
    DialogWindow(onCloseRequest = onClose, title = problem.title,
        state = rememberDialogState(width = 460.dp, height = 220.dp), resizable = false,
        onKeyEvent = { event ->
            val dismiss = event.type == KeyEventType.KeyUp && (event.key == Key.Escape || event.key == Key.Enter)
            if (dismiss) onClose()
            dismiss
        }) {
        Column(Modifier.fillMaxSize().background(theme.background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(problem.title, color = theme.ink, style = MaterialTheme.typography.titleMedium)
            Text(problem.detail, color = theme.ink)
            SkyButton("OK", onClose)
        }
    }
}
