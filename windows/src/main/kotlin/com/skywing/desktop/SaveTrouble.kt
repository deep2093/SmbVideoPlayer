package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A short note when a change can't be saved, instead of failing silently. A save the person made (a watched tick, a
 * View choice, the Resume spot) shows it every time; background saves (video lengths, "new" marks, the window's place)
 * show it once per session, so it doesn't nag. It is told from any thread.
 */
internal class SaveTrouble {
    var showing by mutableStateOf(false)
        private set

    private val backgroundShown = AtomicBoolean(false)

    fun failed(background: Boolean) {
        if (!background || backgroundShown.compareAndSet(false, true)) showing = true
    }

    fun close() {
        showing = false
    }

    companion object {
        /** The app's one note, shown on the library pages and never over the video. */
        val app = SaveTrouble()
    }
}

@Composable
internal fun SaveNotice(trouble: SaveTrouble, modifier: Modifier = Modifier) {
    if (!trouble.showing) return
    val theme = LocalSkyTheme.current
    Row(
        modifier.skyPanel(RoundedCornerShape(16.dp), theme.menu).padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text("Couldn't save your last change.", color = theme.ink, style = MaterialTheme.typography.bodyMedium)
            Text("If this keeps happening, check that your disk isn't full.", color = theme.muted, style = MaterialTheme.typography.bodySmall)
        }
        SkyButton("Close", trouble::close, primary = false)
    }
}
