package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/**
 * Settings > Artwork. Everything here is optional and off by default: until the master switch is on the app shows
 * exactly what it always has, and nothing touches the internet.
 */
@Composable
internal fun ArtworkSettingsSection(vm: LibraryViewModel) {
    val settings by vm.artworkSettings.collectAsStateWithLifecycle()
    val keyRevision by vm.providerKeyRevision.collectAsStateWithLifecycle()
    var consentDialog by remember { mutableStateOf(false) }
    val s = settings

    ToggleRow("Enable artwork management", "Off keeps the app exactly as it was. Turn on to choose covers for videos and folders.", s.enabled) { on ->
        vm.updateArtwork { it.copy(enabled = on) }
    }

    Text("Default covers", style = MaterialTheme.typography.titleMedium)
    ModePicker("Videos", ArtworkScope.VIDEO, s.videoMode, s.enabled) { m -> vm.updateArtwork { it.withMode(ArtworkScope.VIDEO, m) } }
    ModePicker("SMB folders", ArtworkScope.SMB_FOLDER, s.smbFolderMode, s.enabled) { m -> vm.updateArtwork { it.withMode(ArtworkScope.SMB_FOLDER, m) } }
    ModePicker("Virtual folders", ArtworkScope.VIRTUAL_FOLDER, s.virtualFolderMode, s.enabled) { m -> vm.updateArtwork { it.withMode(ArtworkScope.VIRTUAL_FOLDER, m) } }
    Text(
        "A cover you choose for one item always wins over these defaults. Long press or open the menu of a video or folder, then Change cover.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("Collage", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Frames in a folder collage: ${s.collageTiles}", Modifier.weight(1f))
        OutlinedButton(onClick = { vm.updateArtwork { it.copy(collageTiles = (it.collageTiles - 1).coerceIn(2, 6)) } }, enabled = s.enabled && s.collageTiles > 2) { Text("−") }
        OutlinedButton(onClick = { vm.updateArtwork { it.copy(collageTiles = (it.collageTiles + 1).coerceIn(2, 6)) } }, enabled = s.enabled && s.collageTiles < 6, modifier = Modifier.padding(start = 8.dp)) { Text("+") }
    }

    Text("Internet performer pictures", style = MaterialTheme.typography.titleMedium)
    Text(
        "Optional. Names are sent to the services you enable below; nothing is sent while these are off. " +
            "Pictures are used under each service's own terms, with your own account or key.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ToggleRow("Allow internet performer pictures", "Required for the options below and for the Internet performer tab.", s.adultConsent, s.enabled) { on ->
        if (on) consentDialog = true else vm.updateArtwork { it.copy(adultConsent = false) }
    }
    ToggleRow("Use on folders", "Folders can show a performer picture.", s.portraitsOnFolders, s.enabled && s.adultConsent) { on -> vm.updateArtwork { it.copy(portraitsOnFolders = on) } }
    ToggleRow("Use on videos", "Videos inherit the picture of their folder.", s.portraitsOnVideos, s.enabled && s.adultConsent) { on -> vm.updateArtwork { it.copy(portraitsOnVideos = on) } }
    ToggleRow(
        "Fetch automatically after a scan",
        "Only for kinds of item whose default cover is Internet performer portrait. Unclear matches are left for you to choose.",
        s.autoFetchOnScan,
        s.enabled && s.adultConsent && (s.portraitsOnFolders || s.portraitsOnVideos),
    ) { on -> vm.updateArtwork { it.copy(autoFetchOnScan = on) } }

    Text("How names are searched", style = MaterialTheme.typography.titleMedium)
    ToggleRow(
        "Video file names: text before \",\" or \"-\"",
        "\"tom cruise- movie\" and \"tom cruise, brad pitt- movie\" both search for \"tom cruise\".",
        s.videoUseFirstName,
        s.enabled,
    ) { on -> vm.updateArtwork { it.copy(videoUseFirstName = on) } }
    ToggleRow("Folders: whole folder name", "The full folder name is searched as it is.", s.folderUseWholeName, s.enabled) { on ->
        vm.updateArtwork { it.copy(folderUseWholeName = on) }
    }

    Text("Services", style = MaterialTheme.typography.titleMedium)
    s.providerOrder.forEachIndexed { index, id ->
        ProviderCard(vm, s, id, index, s.providerOrder.size, keyRevision)
    }
    Text(
        "porndb.me is not available: its public API was shut down on 16 Oct 2026 and no replacement API exists, so it is not supported. " +
            "TMDB is for mainstream films and TV only and is not used for adult content.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("Network", style = MaterialTheme.typography.titleMedium)
    ToggleRow("Wi-Fi only", "No downloads on mobile data.", s.wifiOnly, s.enabled) { on -> vm.updateArtwork { it.copy(wifiOnly = on) } }
    ToggleRow("Allow metered connections", "Off blocks downloads on any metered network.", s.allowMetered, s.enabled) { on -> vm.updateArtwork { it.copy(allowMetered = on) } }

    Text("Tools", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = vm::fetchPortraitsNow, enabled = s.enabled && s.adultConsent && (s.portraitsOnFolders || s.portraitsOnVideos), modifier = Modifier.fillMaxWidth()) {
        Text("Fetch performer portraits now")
    }
    OutlinedButton(onClick = vm::clearFetchedPortraits, enabled = s.enabled, modifier = Modifier.fillMaxWidth()) { Text("Clear automatically fetched portraits") }
    Text(
        "Pictures you chose yourself are kept. Source videos on your server are never changed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (consentDialog) {
        AlertDialog(
            onDismissRequest = { consentDialog = false },
            title = { Text("Allow internet performer pictures?") },
            text = {
                Text(
                    "When you look up or fetch a picture, only the name you search for (a cleaned folder or file name) is sent to the services you enabled, " +
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
private fun ProviderCard(vm: LibraryViewModel, s: ArtworkSettings, id: String, index: Int, count: Int, keyRevision: Int) {
    val name = if (id == ProviderIds.THE_PORN_DB) "ThePornDB" else "StashDB"
    val keyLabel = if (id == ProviderIds.THE_PORN_DB) "API token" else "API key"
    var key by remember(id) { mutableStateOf("") }
    var endpoint by remember(id, s.customEndpoints[id]) { mutableStateOf(s.customEndpoints[id].orEmpty()) }
    val hasKey = remember(keyRevision, id) { vm.providerHasKey(id) }
    val enabled = id !in s.disabledProviders
    val endpointOk = endpoint.isBlank() || EndpointPolicy.isAllowed(endpoint)

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$name  (priority ${index + 1})", style = MaterialTheme.typography.bodyLarge)
                Text(if (hasKey) "Key saved" else "No key yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(enabled = s.enabled && index > 0, onClick = { vm.updateArtwork { a -> a.copy(providerOrder = a.providerOrder.swap(index, index - 1)) } }) {
                Icon(Icons.Filled.ArrowUpward, "Move $name up")
            }
            IconButton(enabled = s.enabled && index < count - 1, onClick = { vm.updateArtwork { a -> a.copy(providerOrder = a.providerOrder.swap(index, index + 1)) } }) {
                Icon(Icons.Filled.ArrowDownward, "Move $name down")
            }
            Switch(
                checked = enabled,
                enabled = s.enabled,
                onCheckedChange = { on -> vm.updateArtwork { a -> a.copy(disabledProviders = if (on) a.disabledProviders - id else a.disabledProviders + id) } },
            )
        }
        PillTextField(
            value = key,
            onValueChange = { key = it },
            label = if (hasKey) "$keyLabel (saved; type to replace)" else keyLabel,
            visualTransformation = PasswordVisualTransformation(),
            enabled = s.enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = s.enabled && key.isNotBlank(), onClick = {
                if (vm.saveProviderKey(id, key)) key = ""
            }) { Text("Save key") }
            OutlinedButton(enabled = s.enabled && hasKey, onClick = { vm.clearProviderKey(id) }) { Text("Remove key") }
        }
        PillTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = "Custom API address (optional, https://)",
            enabled = s.enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!endpointOk) {
            Text("The address must start with https:// and name a server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = s.enabled && endpointOk && endpoint.trim() != s.customEndpoints[id].orEmpty(), onClick = {
                vm.updateArtwork { a -> a.copy(customEndpoints = if (endpoint.isBlank()) a.customEndpoints - id else a.customEndpoints + (id to endpoint.trim().trimEnd('/'))) }
            }) { Text(if (endpoint.isBlank()) "Use default address" else "Save address") }
        }
        Text(
            if (id == ProviderIds.THE_PORN_DB) "Default: https://api.theporndb.net (performer search with a Bearer token)." else "Default: https://stashdb.org/graphql (GraphQL, ApiKey header). A self-hosted stash-box address also works.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun List<String>.swap(a: Int, b: Int): List<String> = toMutableList().also {
    val t = it[a]
    it[a] = it[b]
    it[b] = t
}
