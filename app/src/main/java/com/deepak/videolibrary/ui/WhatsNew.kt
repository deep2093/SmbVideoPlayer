package com.deepak.videolibrary.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The "What's new" notes. [RELEASE] changes whenever the notes below change, so the pop-up shows once per set of notes
 * (not on every build), and can be opened again from Settings.
 */
internal object WhatsNew {
    const val RELEASE = "2026-10-28-covers"

    val notes: List<Pair<String, String>> = listOf(
        "Original folders are yours" to "Add the folders you want; subfolders show exactly as on the drive, with their full names.",
        "Full movie posters" to "Movie folders inside your Original folders get a poster from ThePornDB automatically (folder name first, then file names). Unclear ones go to review.",
        "Scene posters on every video" to "Videos are matched by file fingerprint or title. Clear matches are applied automatically.",
        "One review pop-up" to "Unclear scene matches, performer portraits and movie posters are collected in one pop-up when you open the app. Settings > Review all opens it any time.",
        "Performer portraits on Virtual folders" to "The name folders made inside your Virtual folders get portraits automatically (text before a comma or hyphen, otherwise the first two words). Custom folders stay manual: choose a poster or performer in Change cover.",
        "Simpler settings" to "One switch for online matching, and the folder naming switches are gone: the rule is always on.",
        "Search scenes in Change cover" to "The Scene poster tab has a search box filled with the file name, plus earlier suggestions.",
        "Refresh button and pull down" to "The Refresh button on every page scans your drive. Pulling down looks for missing scenes and posters and applies them.",
        "Grid sizes" to "Choose columns for portrait and landscape separately in Settings. Pinch to resize is gone.",
        "Virtual folder names" to "Names using an en dash or em dash no longer create a second folder with the same name.",
        "Faster and clearer downloads" to "Posters and photos download in parallel, and ThePornDB errors now say what went wrong.",
        "Gentler on your drive" to "The drive is only read when you refresh, scan, or the 12-hour sync runs.",
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("whats_new", Context.MODE_PRIVATE)

    fun seen(context: Context): Boolean = prefs(context).getString("seen", null) == RELEASE

    fun markSeen(context: Context) {
        prefs(context).edit().putString("seen", RELEASE).apply()
    }
}

@Composable
internal fun WhatsNewDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("What's new") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WhatsNew.notes.forEach { (title, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Got it") } },
    )
}
