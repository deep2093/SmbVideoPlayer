package com.skywing.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A folder or share that could not be scanned, and why. */
internal data class ScanProblem(val place: String, val message: String)

/**
 * Rescans every source of the library, like Refresh library and automatic sync on Android: the folders added under
 * This PC and every network share in the library. A source that cannot be reached, or that suddenly has no videos,
 * keeps its previous list, so a drive that is off never empties the library. Names and covers are never touched.
 */
internal class LibrarySync(private val library: LibraryModel) {
    private val prefs = Preferences.userNodeForPackage(LibrarySync::class.java).node("sync")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Rescan when the app starts (if the last scan is old) and every [INTERVAL_MS] while it is open. */
    var auto by mutableStateOf(prefs.getBoolean("auto", false))
        private set
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf(prefs.get("last_status", "Not run yet"))
        private set
    var lastSuccess by mutableStateOf(prefs.getLong("last_success", 0L))
        private set

    /** The source being scanned now. */
    var current by mutableStateOf("")
        private set
    var problems by mutableStateOf(emptyList<ScanProblem>())
        private set

    fun updateAuto(on: Boolean) {
        auto = on
        prefs.putBoolean("auto", on)
    }

    /** True when automatic sync is on and the last scan is older than the interval. */
    fun due(now: Long = System.currentTimeMillis()): Boolean = auto && !running && now - lastSuccess >= INTERVAL_MS

    fun refresh() {
        if (running) return
        running = true
        job = scope.launch {
            val found = ArrayList<ScanProblem>()
            try {
                scanThisPc(found)
                scanShares(found)
                finish(if (found.isEmpty()) "Up to date" else "Finished with scan errors", found, success = true)
            } catch (e: CancellationException) {
                finish("Stopped", found, success = false)
                throw e
            } catch (e: Exception) {
                found += ScanProblem("Library", e.message ?: "The scan failed")
                finish("Couldn't sync", found, success = false)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun finish(text: String, found: List<ScanProblem>, success: Boolean) {
        status = text
        problems = found
        current = ""
        running = false
        prefs.put("last_status", text)
        if (success) {
            lastSuccess = System.currentTimeMillis()
            prefs.putLong("last_success", lastSuccess)
        }
        library.changed()
    }

    private suspend fun scanThisPc(found: MutableList<ScanProblem>) {
        val folders = runCatching { LocalFolderStore().load() }.getOrDefault(emptyList())
        if (folders.isEmpty()) return
        current = "This PC"
        val result = LocalVideoScanner.scan(folders)
        val before = library.store.load().count { it.sourceType == "PC" }
        if (result.unavailableFolders > 0) found += ScanProblem("This PC", "${result.unavailableFolders} folder(s) could not be read")
        if (result.files.isEmpty() && before > 0 && result.unavailableFolders > 0) {
            found += ScanProblem("This PC", "No videos found; the previous list was kept in case the drive is off")
            return
        }
        library.store.replaceSource("PC", "PC", "This PC", result.files)
    }

    private suspend fun scanShares(found: MutableList<ScanProblem>) {
        val browser = WindowsShareBrowser()
        val sources = library.store.load().filter { it.sourceType == "SMB" }.groupBy { it.sourceId }
        for ((sourceId, entries) in sources) {
            val name = entries.first().sourceName
            val root = shareRootOf(entries.first().path) ?: continue
            current = name
            try {
                val host = ShareHosts.hostOf(root) ?: continue
                val answering = ShareHosts.resolve(host)
                if (answering == null) {
                    found += ScanProblem(name, "Not reachable; the previous list was kept")
                    continue
                }
                val share = browser.shareRoot(ShareHosts.withHost(root, answering))
                val result = browser.scan(share, share)
                if (result.files.isEmpty()) {
                    found += ScanProblem(name, "No videos found; the previous list was kept in case the share is empty by mistake")
                    continue
                }
                library.store.replaceSource(sourceId, "SMB", name, result.files, ShareHosts::canonical)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                found += ScanProblem(name, e.message ?: "Could not scan this share")
            }
        }
    }

    companion object {
        const val INTERVAL_MS = 12L * 60 * 60 * 1000

        /** "\\server\share" of a network path, or null for anything else. */
        fun shareRootOf(path: String): String? {
            if (!path.startsWith("\\\\")) return null
            val parts = path.removePrefix("\\\\").split('\\')
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            return "\\\\" + parts[0] + "\\" + parts[1]
        }

        /** "5 min ago", "3 h ago", "2 days ago" for a time in milliseconds; "never" for 0. */
        fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
            if (time <= 0) return "never"
            val minutes = (now - time).coerceAtLeast(0) / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 48 * 60 -> "${minutes / 60} h ago"
                else -> String.format(Locale.ROOT, "%d days ago", minutes / (24 * 60))
            }
        }
    }
}
