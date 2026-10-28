package com.deepak.videolibrary.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.deepak.videolibrary.vpn.DisconnectResult
import com.deepak.videolibrary.vpn.ImportResult
import com.deepak.videolibrary.vpn.VpnState
import com.deepak.videolibrary.vpn.WireGuardManager
import kotlinx.coroutines.launch

/** Settings > VPN: import a WireGuard .conf, connect/disconnect, Auto Connect and app-only routing. */
@Composable
fun VpnSettings() {
    val context = LocalContext.current
    val vpn = remember(context) { WireGuardManager.get(context) }
    val state by vpn.state.collectAsState()
    val playing by vpn.playbackActive.collectAsState()
    val scope = rememberCoroutineScope()

    var autoConnect by remember { mutableStateOf(vpn.prefs.autoConnect) }
    var appOnly by remember { mutableStateOf(vpn.prefs.appOnly) }
    var autoDisconnect by remember { mutableStateOf(vpn.prefs.autoDisconnect) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var askDisconnectAnyway by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = vpn.importConfig(uri)) {
                    is ImportResult.Success -> {
                        messageIsError = false
                        message = "Configuration imported (${result.peerCount} peer${if (result.peerCount == 1) "" else "s"}). " +
                            "The private key is stored encrypted on this device."
                    }
                    is ImportResult.Failure -> {
                        messageIsError = true
                        message = result.message
                    }
                }
            }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch { vpn.connect() }
        } else {
            vpn.consentDenied()
        }
    }

    fun connect() {
        message = null
        vpn.cancelPendingDisconnect()
        val consentIntent = vpn.consentIntent()
        if (consentIntent != null) consent.launch(consentIntent) else scope.launch { vpn.connect() }
    }

    fun disconnect() {
        message = null
        scope.launch {
            if (vpn.disconnect() == DisconnectResult.DEFERRED_PLAYBACK) askDisconnectAnyway = true
        }
    }

    val hasConfig = state != VpnState.NoConfig
    val busy = state == VpnState.Connecting
    val active = state is VpnState.Connected

    Card(
        shape = MaterialTheme.shapes.medium,
        border = settingsCardBorder(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WireGuard", style = MaterialTheme.typography.titleMedium)
            Text(
                "Reach your home SMB server from anywhere through your existing WireGuard setup. " +
                    "The server address comes from Settings > Connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val (statusText, isProblem) = describe(state)
            Text(
                "Status: $statusText",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (playing && active) {
                Text(
                    "Video is playing. The VPN stays up until playback ends.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Button(onClick = ::disconnect) { Text("Disconnect") }
                } else {
                    Button(onClick = ::connect, enabled = hasConfig && !busy) { Text("Connect") }
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Text(if (hasConfig) "Replace config" else "Import .conf")
                }
            }
            if (hasConfig) {
                TextButton(onClick = { askRemove = true }, enabled = !busy) { Text("Remove configuration") }
            }
        }
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        border = settingsCardBorder(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto Connect VPN")
                    Text(
                        "When the app opens and the server can't be reached directly, connect the VPN first. " +
                            "Not used when you are on the home network.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoConnect,
                    enabled = hasConfig,
                    onCheckedChange = {
                        autoConnect = it
                        vpn.prefs.autoConnect = it
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Disconnect VPN when I leave the app")
                    Text(
                        "Disconnects right away when you close the app, or 5 minutes after you leave it or lock the phone. Never during playback.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoDisconnect,
                    onCheckedChange = {
                        autoDisconnect = it
                        vpn.prefs.autoDisconnect = it
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use VPN for this app only")
                    Text(
                        "Other apps keep their normal connection. Applies the next time the VPN connects.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = appOnly,
                    onCheckedChange = {
                        appOnly = it
                        vpn.prefs.appOnly = it
                    },
                )
            }
            if (!appOnly) {
                Text(
                    "With this off, the tunnel follows the routes in your WireGuard file and may carry other apps' traffic.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (askDisconnectAnyway) {
        AlertDialog(
            onDismissRequest = { askDisconnectAnyway = false },
            title = { Text("Video is playing") },
            text = { Text("Disconnecting now may stop playback. Otherwise the VPN will disconnect when playback ends.") },
            confirmButton = {
                TextButton(onClick = {
                    askDisconnectAnyway = false
                    scope.launch { vpn.disconnect(force = true) }
                }) { Text("Disconnect now") }
            },
            dismissButton = {
                TextButton(onClick = { askDisconnectAnyway = false }) { Text("After playback") }
            },
        )
    }

    if (askRemove) {
        AlertDialog(
            onDismissRequest = { askRemove = false },
            title = { Text("Remove WireGuard configuration?") },
            text = { Text("This disconnects the VPN and deletes the stored configuration and its private key from this device. Your WireGuard server is not changed.") },
            confirmButton = {
                TextButton(onClick = {
                    askRemove = false
                    scope.launch {
                        if (vpn.removeConfig()) {
                            messageIsError = false
                            message = "Configuration removed."
                        } else {
                            messageIsError = true
                            message = "Stop playback before removing the configuration."
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { askRemove = false }) { Text("Cancel") } },
        )
    }
}

/** Status line and whether it should be drawn as a problem. */
private fun describe(state: VpnState): Pair<String, Boolean> = when (state) {
    VpnState.NoConfig -> "No configuration. Import your WireGuard .conf file." to false
    VpnState.Disconnected -> "Disconnected" to false
    VpnState.Connecting -> "Connecting..." to false
    is VpnState.Connected -> when (state.serverReachable) {
        true -> "Connected. The server answers through the tunnel." to false
        false -> "Connected, but the SMB server did not answer through the tunnel. Check that the server is on and that your WireGuard AllowedIPs include its address." to true
        null -> "Connected" to false
    }
    VpnState.NeedsPermission -> "Android needs your permission to create a VPN. Tap Connect and accept the request." to true
    is VpnState.Error -> state.message to true
}

