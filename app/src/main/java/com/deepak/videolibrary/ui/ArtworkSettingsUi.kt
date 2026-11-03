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

/** One flat section of an Artwork sub-page, shown only on its own page ([visible]). */
@Composable
private fun ArtworkGroup(visible: Boolean, title: String, content: @Composable () -> Unit) {
    if (visible) SettingsStaticCard(title) { content() }
}

/**
 * Settings > Artwork, kept short: one switch, then four groups that open in place (covers, internet portraits,
 * naming, tools). Everything is optional and off by default: until the switch is on the app shows exactly what it
 * always has, and nothing touches the internet.
 */
@Composable
internal fun ArtworkSettingsSection(vm: LibraryViewModel, part: String = "covers") {
    val settings by vm.artworkSettings.collectAsStateWithLifecycle()
    val sceneSettings by vm.metadataSettings.collectAsStateWithLifecycle()
    val review by vm.portraitReview.collectAsStateWithLifecycle()
    var consentDialog by remember { mutableStateOf(false) }
    var reviewOpen by remember { mutableStateOf(false) }
    val s = settings
    val portraitsOn = s.enabled && s.adultConsent

    // The old "Enable existing artwork overrides" switch is gone: cover rules are always on (the view model turns the
    // saved flag on), and anything that uses the internet still needs its own permission.
    ArtworkGroup(part == "covers", "Default covers") {
        ModePicker("Drive folders", ArtworkScope.SMB_FOLDER, s.smbFolderMode, true) { m -> vm.updateArtwork { it.withMode(ArtworkScope.SMB_FOLDER, m) } }
        ModePicker("Virtual and custom folders", ArtworkScope.VIRTUAL_FOLDER, s.virtualFolderMode, true) { m -> vm.updateArtwork { it.withMode(ArtworkScope.VIRTUAL_FOLDER, m) } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Collage frames: ${s.collageTiles}", Modifier.weight(1f))
            OutlinedButton(onClick = { vm.updateArtwork { it.copy(collageTiles = (it.collageTiles - 1).coerceIn(2, 6)) } }, enabled = s.collageTiles > 2) { Text("−") }
            OutlinedButton(onClick = { vm.updateArtwork { it.copy(collageTiles = (it.collageTiles + 1).coerceIn(2, 6)) } }, enabled = s.collageTiles < 6, modifier = Modifier.padding(start = 8.dp)) { Text("+") }
        }
        Hint("A cover you pick (hold an item, Change cover) always wins.")
    }

    ArtworkGroup(part == "artwork" && portraitsOn, "Portrait options") {
        SelectionPicker("Virtual folders", s.virtualFolderSelection, portraitsOn) { sel -> vm.updateArtwork { it.withSelection(ArtworkScope.VIRTUAL_FOLDER, sel) } }
        ToggleRow("Wi-Fi only", null, s.wifiOnly, portraitsOn) { on -> vm.updateArtwork { it.copy(wifiOnly = on) } }
        PortraitProviderAdvanced(vm, s, portraitsOn)
    }

    // The names sent to the search are set in Settings > Auto grouping.

    ArtworkGroup(part == "review", "Folder portraits") {
        OutlinedButton(onClick = { reviewOpen = true }, enabled = review.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (review.isEmpty()) "Nothing to review" else "Review portraits (${review.size})")
        }
        val note = remember(settings) { vm.artworkBackgroundNote() }
        if (note != null) Hint(note)
    }
    if (reviewOpen) ReviewPortraitsDialog(vm, onDismiss = { reviewOpen = false })

    if (consentDialog) {
        AlertDialog(
            onDismissRequest = { consentDialog = false },
            title = { Text("Allow internet portraits?") },
            text = {
                Text(
                    "When you search for a portrait, the app sends only a cleaned name to the metadata providers you've configured (StashDB or ThePornDB). No SMB paths, server addresses or passwords are sent. You can turn this off at any time.",
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

/** Advanced portrait network and self-hosted endpoint choices. Keys are only managed in Scene metadata. */
@Composable
private fun PortraitProviderAdvanced(vm: LibraryViewModel, s: ArtworkSettings, enabled: Boolean) {
    var advanced by vm.ui.flagState("artwork.providerAdvanced")
    TextButton(onClick = { advanced = !advanced }, enabled = enabled) {
        Text(if (advanced) "Hide advanced portrait network options" else "Advanced portrait network options")
    }
    if (!advanced || !enabled) return
    ToggleRow("Allow metered connections", "Off blocks portrait searches on metered networks.", s.allowMetered) { enabled ->
        vm.updateArtwork { old -> old.copy(allowMetered = enabled) }
    }
    val id = ProviderIds.STASH_DB
    var endpoint by remember(s.customEndpoints[id]) { mutableStateOf(s.customEndpoints[id].orEmpty()) }
    val endpointOk = endpoint.isBlank() || EndpointPolicy.isAllowed(endpoint)
    PillTextField(
        value = endpoint,
        onValueChange = { endpoint = it },
        label = "Custom StashDB HTTPS endpoint (optional)",
        modifier = Modifier.fillMaxWidth(),
    )
    if (!endpointOk) Hint("The endpoint must be a valid https:// address.")
    OutlinedButton(
        enabled = endpointOk && endpoint.trim() != s.customEndpoints[id].orEmpty(),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            vm.updateArtwork { current ->
                current.copy(customEndpoints =
                    if (endpoint.isBlank()) current.customEndpoints - id
                    else current.customEndpoints + (id to endpoint.trim().trimEnd('/'))
                )
            }
        },
    ) { Text(if (endpoint.isBlank()) "Use default StashDB endpoint" else "Save endpoint") }
}
