package com.deepak.videolibrary

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.deepak.videolibrary.thumbs.ImageMemoryCache
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.deepak.videolibrary.scan.PeriodicSyncWorker
import com.deepak.videolibrary.ui.AppRoot
import com.deepak.videolibrary.ui.VideoLibraryTheme

class MainActivity : ComponentActivity() {
    var onLeavePlayback:(()->Unit)?=null
    override fun onUserLeaveHint(){super.onUserLeaveHint();onLeavePlayback?.invoke()}


    // Only needed so the "scanning" notification is visible; scanning works either way.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ImageMemoryCache.register(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notification_requested", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_requested",true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            VideoLibraryTheme {
                var ready by remember { mutableStateOf(false) }
                var failed by remember { mutableStateOf(false) }
                var attempt by remember { mutableIntStateOf(0) }
                LaunchedEffect(attempt) {
                    failed=false
                    try {
                        withContext(Dispatchers.IO) {
                            val services=ServiceLocator.get(applicationContext)
                            services.db.openHelper.writableDatabase
                            services.settings.load()
                            PeriodicSyncWorker.schedule(applicationContext)
                        }
                        ready=true
                    } catch(e:kotlinx.coroutines.CancellationException) { throw e }
                    catch(_:Exception) { failed=true }
                }
                if (ready) AppRoot() else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                    if(failed) Button(onClick={attempt++}) { Text("Could not open library. Retry") }
                    else CircularProgressIndicator()
                }
            }
        }
    }
}
