package com.deepak.videolibrary.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deepak.videolibrary.ServiceLocator
import kotlinx.coroutines.flow.combine

/** Why a lookup should not start without asking: mobile data and/or a low battery. */
internal object LookupConditions {
    const val LOW_BATTERY_PERCENT = 20

    fun describe(onMobileData: Boolean, batteryPercent: Int, charging: Boolean): String? {
        val low = !charging && batteryPercent in 0..LOW_BATTERY_PERCENT
        return when {
            onMobileData && low -> "mobile data and a low battery ($batteryPercent%)"
            onMobileData -> "mobile data"
            low -> "a low battery ($batteryPercent%)"
            else -> null
        }
    }

    fun current(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val metered = cm?.isActiveNetworkMetered == true
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return describe(metered, percent, charging)
    }
}

/**
 * Scene and poster lookups no longer wait for Wi-Fi or a charged battery. When one is about to start (or is running)
 * while the phone is on mobile data or low on battery, this asks once per app session whether to carry on or cancel it.
 */
@Composable
internal fun LookupConditionPrompt(canShow: Boolean) {
    val context = LocalContext.current
    val service = remember(context) { ServiceLocator.get(context) }
    val manager = remember(context) { WorkManager.getInstance(context) }
    val id = service.libraryId
    val names = remember(id) {
        listOf(
            com.deepak.videolibrary.metadata.SceneMetadataWorker.workName(id),
            com.deepak.videolibrary.metadata.ScenePosterWorker.workName(id),
            com.deepak.videolibrary.artwork.MoviePosterWorker.workName(id),
        )
    }
    val active by remember(manager, names) {
        combine(names.map { manager.getWorkInfosForUniqueWorkFlow(it) }) { lists ->
            lists.any { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        }
    }.collectAsStateWithLifecycle(initialValue = false)
    var answered by rememberSaveable { mutableStateOf(false) }
    val reason = if (canShow && active && !answered) LookupConditions.current(context) else null
    if (reason != null) {
        AlertDialog(
            onDismissRequest = { answered = true },
            title = { Text("Lookups are starting") },
            text = {
                Text(
                    "Scene and poster lookups are about to run (or already are) on $reason. " +
                        "They use little data, but you can cancel them now. Nothing is lost: they start again at the next refresh or when you fetch from a folder.",
                )
            },
            confirmButton = { TextButton(onClick = { answered = true }) { Text("Continue") } },
            dismissButton = {
                TextButton(onClick = {
                    names.forEach { manager.cancelUniqueWork(it) }
                    answered = true
                }) { Text("Cancel lookups") }
            },
        )
    }
}
