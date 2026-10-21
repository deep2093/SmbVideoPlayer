package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.artwork.ArtworkMode
import com.deepak.videolibrary.artwork.ArtworkScope
import com.deepak.videolibrary.artwork.ArtworkSettings
import com.deepak.videolibrary.artwork.ProviderIds
import com.deepak.videolibrary.artwork.online.EndpointPolicy

/** One row with a label, an optional explanation and a switch. */
@Composable
private fun ToggleRow(title: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Automatic / Manual / Custom for one kind of folder, with the meaning of the current choice underneath. */
@Composable
private fun SelectionPicker(label: String, current: com.deepak.videolibrary.artwork.PortraitSelection, enabled: Boolean, onPick: (com.deepak.videolibrary.artwork.PortraitSelection) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${current.label}", maxLines = 1)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            com.deepak.videolibrary.artwork.PortraitSelection.entries.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label + if (option == current) " ✓" else "")
                            Text(option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
    Text(current.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A button that opens a menu of modes for one kind of item. */
@Composable
private fun ModePicker(label: String, scope: ArtworkScope, current: ArtworkMode, enabled: Boolean, onPick: (ArtworkMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${current.label}", maxLines = 1)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            scope.defaultModes.forEach { mode ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(mode.label + if (mode == current) " ✓" else "") },
                    onClick = {
                        open = false
                        onPick(mode)
                    },
                )
            }
        }
    }
}

/** A collapsible group inside Settings > Artwork: a title, a one-line summary, and its options when opened. */
@Composable
private fun ArtworkGroup(vm: LibraryViewModel, id: String, title: String, summary: String, enabled: Boolean, content: @Composable () -> Unit) {
    var open by vm.ui.flagState("artwork.$id")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(enabled = enabled, onClickLabel = if (open) "Collapse $title" else "Expand $title") { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Icon(
                if (open && enabled) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open && enabled) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
        HorizontalDivider()
    }
}

/**
 * Settings > Artwork, kept short: one switch, then four groups that open in place (covers, internet portraits,
 * naming, tools). Everything is optional and off by default: until the switch is on the app shows exactly what it
 * always has, and nothing touches the internet.
 */
@Composable
internal fun ArtworkSettingsSection(vm: LibraryViewModel) {
    val settings by vm.artworkSettings.collectAsStateWithLifecycle()
    val keyRevision by vm.providerKeyRevision.collectAsStateWithLifecycle()
    val review by vm.portraitReview.collectAsStateWithLifecycle()
    var consentDialog by remember { mutableStateOf(false) }
    var reviewOpen by remember { mutableStateOf(false) }
    val s = settings
    val portraitsOn = s.enabled && s.adultConsent

    ToggleRow("Use artwork", "Choose covers for videos and folders. Off keeps the automatic thumbnails.", s.enabled) { on ->
        vm.updateArtwork { it.copy(enabled = on) }
    }

    ArtworkGroup(
        vm, "covers", "Default covers",
        "Videos: ${s.videoMode.label} · Drive folders: ${s.smbFolderMode.label} · Virtual: ${s.virtualFolderMode.label}",
        s.enabled,
    ) {
        ModePicker("Videos", ArtworkScope.VIDEO, s.videoMode, true) { m -> vm.updateArtwork { it.withMode(ArtworkScope.VIDEO, m) } }
        ModePicker("Drive folders", ArtworkScope.SMB_FOLDER, s.smbFolderMode, true) { m -> vm.updateArtwork { it.withMode(ArtworkScope.SMB_FOLDER, m) } }
        ModePicker("Virtual and custom folders", ArtworkScope.VIRTUAL_FOLDER, s.virtualFolderMode, true) { m -> vm.updateArtwork { it.withMode(ArtworkScope.VIRTUAL_FOLDER, m) } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Collage frames: ${s.collageTiles}", Modifier.weight(1f))
            OutlinedButton(onClick = { vm.updateArtwork { it.copy(collageTiles = (it.collageTiles - 1).coerceIn(2, 6)) } }, enabled = s.collageTiles > 2) { Text("−") }
            OutlinedButton(onClick = { vm.updateArtwork { it.copy(collageTiles = (it.collageTiles + 1).coerceIn(2, 6)) } }, enabled = s.collageTiles < 6, modifier = Modifier.padding(start = 8.dp)) { Text("+") }
        }
        Hint("A cover you pick for one item (hold it, then Change cover) always wins over these defaults.")
    }

    ArtworkGroup(
        vm, "portraits", "Internet portraits",
        if (!s.adultConsent) "Off · nothing is sent to the internet"
        else listOfNotNull(
            "On",
            "folders".takeIf { s.portraitsOnFolders },
            "videos".takeIf { s.portraitsOnVideos },
            "after scans".takeIf { s.autoFetchOnScan },
            if (vm.providerHasKey(ProviderIds.STASH_DB)) "StashDB ready" else "StashDB key missing",
        ).joinToString(" · "),
        s.enabled,
    ) {
        ToggleRow("Allow internet portraits", "Only the cleaned name is sent, to StashDB with your own key.", s.adultConsent) { on ->
            if (on) consentDialog = true else vm.updateArtwork { it.copy(adultConsent = false) }
        }
        ToggleRow("Use on folders", null, s.portraitsOnFolders, portraitsOn) { on -> vm.updateArtwork { it.copy(portraitsOnFolders = on) } }
        ToggleRow("Use on videos", "Videos show their folder's portrait.", s.portraitsOnVideos, portraitsOn) { on -> vm.updateArtwork { it.copy(portraitsOnVideos = on) } }
        ToggleRow(
            "Look up after each scan",
            "Only where the default cover is Internet portrait.",
            s.autoFetchOnScan,
            portraitsOn && (s.portraitsOnFolders || s.portraitsOnVideos),
        ) { on -> vm.updateArtwork { it.copy(autoFetchOnScan = on) } }
        SelectionPicker("Drive folders", s.smbFolderSelection, portraitsOn) { sel -> vm.updateArtwork { it.withSelection(ArtworkScope.SMB_FOLDER, sel) } }
        SelectionPicker("Virtual and custom folders", s.virtualFolderSelection, portraitsOn) { sel -> vm.updateArtwork { it.withSelection(ArtworkScope.VIRTUAL_FOLDER, sel) } }
        ToggleRow("Wi-Fi only", null, s.wifiOnly, portraitsOn) { on -> vm.updateArtwork { it.copy(wifiOnly = on) } }
        StashDbKey(vm, s, keyRevision, portraitsOn)
    }

    ArtworkGroup(
        vm, "names", "Names to search",
        "Videos: ${if (s.videoUseFirstName) "text before \",\" or \"-\"" else "whole name"} · Folders: ${if (s.folderUseWholeName) "whole name" else "cleaned name"}",
        s.enabled,
    ) {
        ToggleRow(
            "Videos: text before \",\" or \"-\"",
            "\"tom cruise, brad pitt- movie\" searches for \"tom cruise\".",
            s.videoUseFirstName,
        ) { on -> vm.updateArtwork { it.copy(videoUseFirstName = on) } }
        ToggleRow("Folders: whole folder name", null, s.folderUseWholeName) { on -> vm.updateArtwork { it.copy(folderUseWholeName = on) } }
    }

    ArtworkGroup(
        vm, "tools", "Tools",
        if (review.isEmpty()) "No suggestions waiting" else "${review.size} suggestions waiting for you",
        s.enabled,
    ) {
        OutlinedButton(onClick = { reviewOpen = true }, enabled = review.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (review.isEmpty()) "Review suggestions (none waiting)" else "Review suggestions (${review.size})")
        }
        OutlinedButton(onClick = vm::fetchPortraitsNow, enabled = portraitsOn && (s.portraitsOnFolders || s.portraitsOnVideos), modifier = Modifier.fillMaxWidth()) {
            Text("Look up portraits now")
        }
        val note = remember(settings) { vm.artworkBackgroundNote() }
        if (note != null) Hint(note)
        OutlinedButton(onClick = vm::clearFetchedPortraits, modifier = Modifier.fillMaxWidth()) { Text("Clear looked-up portraits") }
        Hint("Pictures you chose yourself are kept. Videos on your server are never changed.")
    }
    if (reviewOpen) ReviewPortraitsDialog(vm, onDismiss = { reviewOpen = false })

    if (consentDialog) {
        AlertDialog(
            onDismissRequest = { consentDialog = false },
            title = { Text("Allow internet portraits?") },
            text = {
                Text(
                    "When you look up a picture, only the name you search for (a cleaned folder or file name) is sent to StashDB, " +
                        "using your own API key. No paths, server names or addresses are sent. You can turn this off at any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    consentDialog = false
                    vm.updateArtwork { it.copy(adultConsent = true) }
                }) { Text("I understand, allow") }
            },
            dismissButton = { TextButton(onClick = { consentDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** StashDB's key (stored encrypted), with the optional own address behind "Advanced". */
@Composable
private fun StashDbKey(vm: LibraryViewModel, s: ArtworkSettings, keyRevision: Int, enabled: Boolean) {
    val id = ProviderIds.STASH_DB
    var key by remember { mutableStateOf("") }
    var advanced by vm.ui.flagState("artwork.advanced")
    var endpoint by remember(s.customEndpoints[id]) { mutableStateOf(s.customEndpoints[id].orEmpty()) }
    val hasKey = remember(keyRevision) { vm.providerHasKey(id) }
    val endpointOk = endpoint.isBlank() || EndpointPolicy.isAllowed(endpoint)
    Text("StashDB", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillTextField(
            value = key,
            onValueChange = { key = it },
            label = if (hasKey) "API key saved · type to replace" else "API key",
            visualTransformation = PasswordVisualTransformation(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = enabled && key.isNotBlank(), onClick = { if (vm.saveProviderKey(id, key)) key = "" }) { Text("Save") }
        if (hasKey) TextButton(enabled = enabled, onClick = { vm.clearProviderKey(id) }) { Text("Remove") }
    }
    TextButton(onClick = { advanced = !advanced }, enabled = enabled) { Text(if (advanced) "Hide advanced" else "Advanced") }
    if (advanced && enabled) {
        ToggleRow("Allow metered connections", "Off blocks look-ups on metered networks.", s.allowMetered) { on -> vm.updateArtwork { it.copy(allowMetered = on) } }
        PillTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = "Own stash-box address (https://, optional)",
            modifier = Modifier.fillMaxWidth(),
        )
        if (!endpointOk) {
            Text("The address must start with https:// and name a server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(enabled = endpointOk && endpoint.trim() != s.customEndpoints[id].orEmpty(), onClick = {
            vm.updateArtwork { a -> a.copy(customEndpoints = if (endpoint.isBlank()) a.customEndpoints - id else a.customEndpoints + (id to endpoint.trim().trimEnd('/'))) }
        }) { Text(if (endpoint.isBlank()) "Use stashdb.org" else "Save address") }
        Hint("Default: https://stashdb.org/graphql. A self-hosted stash-box address also works.")
    }
}
