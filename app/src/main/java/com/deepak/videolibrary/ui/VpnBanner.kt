package com.deepak.videolibrary.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepak.videolibrary.vpn.VpnState
import com.deepak.videolibrary.vpn.WireGuardManager
import kotlinx.coroutines.launch

/**
 * Thin status banner for the WireGuard VPN, shown at the top of the library:
 * - "Connecting to VPN..." with a progress line while a connection is being set up;
 * - a one-line problem with Retry and dismiss when Auto Connect is on and the attempt failed or needs
 *   Android's permission;
 * - nothing at all (zero height) in every other case.
 */
@Composable
fun VpnStatusBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vpn = remember(context) { WireGuardManager.get(context) }
    val state by vpn.state.collectAsState()
    val launchBusy by vpn.launchBusy.collectAsState()
    val scope = rememberCoroutineScope()
    var dismissedFor by remember { mutableStateOf<VpnState?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch { vpn.connect() }
        } else {
            vpn.consentDenied()
        }
    }

    val current = state
    val connecting = launchBusy || current is VpnState.Connecting
    val problem = !connecting && vpn.prefs.autoConnect && dismissedFor != current &&
        (current is VpnState.Error || current is VpnState.NeedsPermission)
    val message: String? = when {
        connecting -> "Connecting to VPN…"
        problem -> if (current is VpnState.Error) current.message else "Allow the VPN request to connect"
        else -> null
    }

    // Keeps the text on screen while the banner animates out (the state has already moved on by then).
    val last = remember { LastShown() }
    if (message != null) {
        last.text = message
        last.busy = connecting
        last.error = problem && current is VpnState.Error
    }

    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(150)),
    ) {
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(16.dp)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surface)
                    .themedOutline(shape),
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        last.text,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (last.error) colors.error else colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!last.busy) {
                        TextButton(onClick = {
                            vpn.cancelPendingDisconnect()
                            val intent = vpn.consentIntent()
                            if (intent != null) consent.launch(intent) else scope.launch { vpn.connect() }
                        }) { Text("Retry") }
                        IconButton(onClick = { dismissedFor = state }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (last.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.tertiary, trackColor = colors.outlineVariant)
                }
            }
        }
    }
}

/** Plain holder (not Compose state) for the last banner content, used only to draw the exit animation. */
private class LastShown {
    var text: String = ""
    var busy: Boolean = false
    var error: Boolean = false
}
