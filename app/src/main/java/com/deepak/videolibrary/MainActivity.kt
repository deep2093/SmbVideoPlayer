package com.deepak.videolibrary

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.deepak.videolibrary.scan.PeriodicSyncWorker
import com.deepak.videolibrary.thumbs.ImageMemoryCache
import com.deepak.videolibrary.ui.AppRoot
import com.deepak.videolibrary.ui.VideoLibraryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var frameDiagnostics: com.deepak.videolibrary.ui.FrameDiagnostics? = null
    override fun onDestroy() {
        frameDiagnostics?.close()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        frameDiagnostics = com.deepak.videolibrary.ui
            .FrameDiagnostics(this)
        ImageMemoryCache.register(applicationContext)
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
