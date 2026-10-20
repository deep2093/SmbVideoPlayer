package com.deepak.videolibrary

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.deepak.videolibrary.scan.PeriodicSyncWorker
import com.deepak.videolibrary.vpn.AutoConnectOutcome
import com.deepak.videolibrary.vpn.WireGuardManager
import com.deepak.videolibrary.thumbs.ImageMemoryCache
import com.deepak.videolibrary.ui.AppRoot
import com.deepak.videolibrary.ui.VideoLibraryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Longest the launch screen waits for the VPN before showing the library anyway. */
private const val VPN_LAUNCH_TIMEOUT_MS = 25_000L

class MainActivity : ComponentActivity() {
    private var frameDiagnostics: com.deepak.videolibrary.ui.FrameDiagnostics? = null
    override fun onDestroy() {
        frameDiagnostics?.close()
        // Stops listening for network changes. The tunnel itself is deliberately left as it is.
        vpn.stopMonitoring()
        super.onDestroy()
    }
    var onLeavePlayback: (() -> Unit)? = null
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        onLeavePlayback?.invoke()
    }

    // Only needed so the "scanning" notification is visible; scanning works either way.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val vpn by lazy { WireGuardManager.get(applicationContext) }
    private var vpnConsentAsked = false
    private var vpnLaunchHandled = false

    // Android's one-time "Connection request" for VPNs, only shown when Auto Connect needs it.
    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                lifecycleScope.launch { vpn.autoConnect(respectBackoff = false) }
            } else {
                vpn.consentDenied()
            }
        }

    /**
     * Auto Connect VPN at launch. Returns quickly (no work at all) when the feature is off or no
     * configuration is imported, so users without a VPN see no startup delay.
     */
    private suspend fun connectVpnIfNeeded() {
        try {
            if (!vpn.prefs.autoConnect || !vpn.store.hasConfig()) return
            // The startup effect re-runs when the library changes; only gate the first open of this launch.
            // Later changes are handled by the network monitor.
            if (vpnLaunchHandled) return
            vpnLaunchHandled = true
            val outcome = withTimeoutOrNull(VPN_LAUNCH_TIMEOUT_MS) { vpn.autoConnect(respectBackoff = false) }
            if (outcome == AutoConnectOutcome.NEEDS_PERMISSION && !vpnConsentAsked) {
                vpnConsentAsked = true
                vpn.consentIntent()?.let { vpnConsent.launch(it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A VPN problem must never stop the library from opening. The Settings > VPN page shows the state.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app uses dark surfaces even when Android itself is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        frameDiagnostics = com.deepak.videolibrary.ui
            .FrameDiagnostics(this)
        ImageMemoryCache.register(applicationContext)
        vpn.startMonitoring()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notification_requested", false)
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            VideoLibraryTheme {
                val libraryRevision by com.deepak.videolibrary.settings.SettingsStore.revision.collectAsState()
                var ready by remember(libraryRevision) { mutableStateOf(false) }
                var failed by remember { mutableStateOf(false) }
                var attempt by remember { mutableIntStateOf(0) }
                LaunchedEffect(attempt, libraryRevision) {
                    failed = false
                    try {
                        withContext(Dispatchers.IO) {
                            val services = ServiceLocator.get(applicationContext)
                            services.db.openHelper.writableDatabase
                            services.settings.load()
                            PeriodicSyncWorker.schedule(applicationContext)
                        }
                        // Bring the VPN up first when the server is not directly reachable, so the first
                        // SMB request already has a route. No-op unless Auto Connect VPN is enabled.
                        connectVpnIfNeeded()
                        ready = true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed = true
                    }
                }
                if (ready) {
                    val id = com.deepak.videolibrary.settings.LibraryIdentity.of(ServiceLocator.get(applicationContext).settings.load())
                    val owner = remember(id) { object : androidx.lifecycle.ViewModelStoreOwner {
                        override val viewModelStore = androidx.lifecycle.ViewModelStore()
                    } }
                    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
                    val factory = remember(id) { androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(application) }
                    key(id) { AppRoot(vm = androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = owner, factory = factory)) }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (failed) {
                            Button(onClick = { attempt++ }) { Text(stringResource(R.string.ui_could_not_open_library_retry_66ed55c)) }
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
