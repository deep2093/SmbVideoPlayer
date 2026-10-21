package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Opt-in, app-local diagnostics. No SMB credentials, paths, titles or media URLs are recorded.
 * Export uses Android's share sheet and includes only explicitly recorded app events.
 */
object AppDiagnostics {
    private const val PREFS = "diagnostics"
    private const val LIMIT = 250
    private val events = ArrayDeque<String>()
    private val lock = Any()

    private var frameSummary = "No frame sample collected"
    fun frameSample(durations: List<Double>, budgetMs: Double) {
        if (durations.isEmpty()) return
        val sorted = durations.sorted()
        val p95 = sorted[((sorted.size - 1) * 0.95).toInt()]
        val late = durations.count { it > budgetMs }
        synchronized(lock) {
            frameSummary =
                "${durations.size} frames; p95 ${"%.1f".format(
                    java.util.Locale.ROOT,
                    p95,
                )} ms; $late over ${"%.1f".format(java.util.Locale.ROOT,budgetMs)} ms display budget"
        }
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", enabled)
            .apply()
        if (!enabled) clear()
    }

    fun clear() = synchronized(lock) {
        events.clear()
        frameSummary = "No frame sample collected"
    }

    fun record(context: Context, category: String, detail: String) {
        if (!enabled(context)) return
        val safeCategory = category.replace(Regex("[^a-zA-Z0-9_-]"), "").take(32)
        // Callers must supply only safe, non-identifying status strings.
        val safeDetail = detail.replace(Regex("[^a-zA-Z0-9 _.,:=-]"), "").take(160)
        synchronized(lock) {
            if (events.size >= LIMIT) events.removeFirst()
            events.addLast("${SystemClock.elapsedRealtime()}ms | $safeCategory | $safeDetail")
        }
    }

    fun report(context: Context): String {
        val memory = ActivityManager.MemoryInfo()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.getMemoryInfo(memory)
        val heap = Runtime.getRuntime()
        val display = if (Build.VERSION.SDK_INT >= 30) {
            context.display?.refreshRate ?: 0f
        } else {
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
        }
        val entries = synchronized(lock) { events.toList() }
        return buildString {
            appendLine("Veyra diagnostics")
            appendLine("Generated: ${DateFormat.getDateTimeInstance().format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
            appendLine("Display reported refresh rate: $display Hz (not measured app FPS)")
            appendLine("Java heap used: ${(heap.totalMemory() - heap.freeMemory()) / 1048576} MiB")
            appendLine("Java heap maximum: ${heap.maxMemory() / 1048576} MiB")
            appendLine("Native heap allocated: ${Debug.getNativeHeapAllocatedSize() / 1048576} MiB")
            appendLine("System available memory: ${memory.availMem / 1048576} MiB")
            appendLine("System low memory: ${memory.lowMemory}")
            appendLine("Rendered frames: ${synchronized(lock) { frameSummary }}")
            appendLine("Diagnostics enabled: ${enabled(context)}")
            appendLine("Events (${entries.size}):")
            entries.forEach { appendLine(it) }
        }
    }
}

/**
 * The diagnostics controls, shown directly inside the Diagnostics section of Settings. There is no
 * separate page or inner expander: the section that hosts it already expands and collapses.
 */
@Composable
fun DiagnosticsSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppDiagnostics.enabled(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    // Only composed while the section is open, so the live refresh stops when it collapses.
    LaunchedEffect(enabled) {
        while (enabled) {
            delay(2000)
            refresh++
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.ui_off_by_default_records_limited_playback_an_685dd71),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.ui_enable_diagnostics_bc3f246), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                AppDiagnostics.setEnabled(context, it)
                refresh++
            })
        }
        val report by produceState("Preparing report…", refresh, enabled) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AppDiagnostics.report(context) }
        }
        Text(report, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                AppDiagnostics.clear()
                refresh++
            }) { Text(stringResource(R.string.ui_clear_719ea39)) }
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Veyra diagnostics")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(send, "Share diagnostics"))
            }) { Text(stringResource(R.string.ui_export_report_0ddb744)) }
        }
    }
}
