package com.deepak.videolibrary

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

    // Only needed so the "scanning" notification is visible; scanning works either way.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PeriodicSyncWorker.schedule(applicationContext)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            VideoLibraryTheme {
                AppRoot()
            }
        }
    }
}
