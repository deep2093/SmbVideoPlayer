package com.deepak.videolibrary.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) clear()
    }

    fun clear() = synchronized(lock) { events.clear() }

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
        val display = if (Build.VERSION.SDK_INT >= 30) context.display?.refreshRate ?: 0f
            else (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
        val entries = synchronized(lock) { events.toList() }
        return buildString {
            appendLine("SMB Video Library diagnostics")
            appendLine("Generated: ${DateFormat.getDateTimeInstance().format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
            appendLine("Display reported refresh rate: ${display} Hz (not measured app FPS)")
            appendLine("Java heap used: ${(heap.totalMemory() - heap.freeMemory()) / 1048576} MiB")
            appendLine("Java heap maximum: ${heap.maxMemory() / 1048576} MiB")
            appendLine("Native heap allocated: ${Debug.getNativeHeapAllocatedSize() / 1048576} MiB")
            appendLine("System available memory: ${memory.availMem / 1048576} MiB")
            appendLine("System low memory: ${memory.lowMemory}")
            appendLine("Diagnostics enabled: ${enabled(context)}")
            appendLine("Events (${entries.size}):")
            entries.forEach { appendLine(it) }
        }
    }
}

@Composable
fun DiagnosticsSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppDiagnostics.enabled(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(expanded, enabled) {
        while (expanded && enabled) { delay(2000); refresh++ }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                onClickLabel = if (expanded) "Hide diagnostics" else "Show diagnostics") { expanded = !expanded }
                .padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                Text("Off by default. Records limited playback and navigation status in memory, without video names, paths or SMB credentials.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Enable diagnostics")
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        AppDiagnostics.setEnabled(context, it)
                        refresh++
                    })
                }
                key(refresh) {
                    Text(AppDiagnostics.report(context), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { AppDiagnostics.clear(); refresh++ }) { Text("Clear") }
                    Button(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "SMB Video Library diagnostics")
                            putExtra(Intent.EXTRA_TEXT, AppDiagnostics.report(context))
                        }
                        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                    }) { Text("Export report") }
                }
            }
        }
    }
}
