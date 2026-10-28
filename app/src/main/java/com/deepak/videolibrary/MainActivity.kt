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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.deepak.videolibrary.scan.PeriodicSyncWorker
import com.deepak.videolibrary.vpn.AutoConnectOutcome
import com.deepak.videolibrary.vpn.WireGuardManager
import com.deepak.videolibrary.thumbs.ImageMemoryCache
import com.deepak.videolibrary.ui.AppRoot
import com.deepak.videolibrary.ui.VideoLibraryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var frameDiagnostics: com.deepak.videolibrary.ui.FrameDiagnostics? = null
    override fun onStart() {
        super.onStart()
        vpn.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        // Home, another app or a locked phone: the VPN is turned off 5 minutes later (never during playback).
        // A rotation or other configuration change is not leaving the app.
        if (!isChangingConfigurations) vpn.onAppBackgrounded()
    }

    override fun onDestroy() {
        frameDiagnostics?.close()
        // Stops listening for network changes.
        vpn.stopMonitoring()
        // Closing the app turns the VPN off right away (after playback, if a video is still playing).
        if (isFinishing) vpn.onAppClosed()
        super.onDestroy()
    }
    var playbackInPip by mutableStateOf(false)
        private set

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        playbackInPip = inPip
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
                lifecycleScope.launch { vpn.autoConnect(respectBackoff = false, showBusy = true) }
            } else {
                vpn.consentDenied()
            }
        }

    /**
     * Auto Connect VPN at launch, in the background: the library opens immediately and the
     * "Connecting to VPN" banner shows progress. Does nothing when the feature is off or no
     * configuration is imported. Runs once per launch; later changes are handled by the network monitor.
     */
    private suspend fun connectVpnIfNeeded() {
        try {
            if (!vpn.prefs.autoConnect || !vpn.store.hasConfig()) return
            if (vpnLaunchHandled) return
            vpnLaunchHandled = true
            val outcome = vpn.autoConnect(respectBackoff = false, showBusy = true)
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

    // The system splash (the app icon) stays up until the library is open, at most a couple of seconds.
    @Volatile private var libraryOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val splashStarted = android.os.SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition { !libraryOpened && android.os.SystemClock.uptimeMillis() - splashStarted < SPLASH_MAX_MS }
        // The icon shrinks and fades into the opening animation, which starts on the same colour.
        splash.setOnExitAnimationListener { provider ->
            val icon = runCatching { provider.iconView }.getOrNull()
            val finish = {
                provider.remove()
                com.deepak.videolibrary.ui.LaunchIntroState.splashGone = true
            }
            if (icon == null) {
                finish()
            } else {
                icon.animate()
                    .scaleX(0.55f).scaleY(0.55f).alpha(0f)
                    .setDuration(260)
                    .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
                    .withEndAction { finish() }
                    .start()
            }
        }
        // The app uses dark surfaces even when Android itself is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        frameDiagnostics = com.deepak.videolibrary.ui
            .FrameDiagnostics(this)
        ImageMemoryCache.register(applicationContext)
        vpn.startMonitoring()
        lifecycleScope.launch { connectVpnIfNeeded() }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notification_requested", false)
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            VideoLibraryTheme {
              com.deepak.videolibrary.ui.LaunchIntro { reveal ->
              Box(with(com.deepak.videolibrary.ui.LaunchIntroState) { Modifier.fillMaxSize().introReveal(reveal) }) {
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
                            // No background schedule: cancel the old one, then refresh only if the last scan is over 12 hours old.
                            PeriodicSyncWorker.ensureScheduled(applicationContext)
                            com.deepak.videolibrary.scan.AutoRefresh.runIfDue(applicationContext, services)
                        }
                        ready = true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        libraryOpened = true
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
    }

    private companion object {
        const val SPLASH_MAX_MS = 2_500L
    }
}
