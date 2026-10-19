package com.deepak.videolibrary.ui

import androidx.compose.runtime.*
import com.deepak.videolibrary.smb.*
import kotlinx.coroutines.*

/** Owns live SMB browsing/search, independently of library organization and playback. */
internal class ExplorerController(private val scope: CoroutineScope, private val profileProvider: () -> SmbProfile?) {
    var explorerPath by mutableStateOf("")
        private set
    var explorerEntries by mutableStateOf<List<SmbEntry>>(emptyList())
        private set
    var explorerBusy by mutableStateOf(false)
        private set
    var explorerMessage by mutableStateOf<String?>(null)
        internal set

    var explorerQuery by mutableStateOf("")
    var explorerHits by mutableStateOf<List<ExplorerHit>>(emptyList())
        private set
    var explorerSearching by mutableStateOf(false)
        private set
    var explorerSearchMessage by mutableStateOf<String?>(null)
        private set
    private var explorerSearchJob: Job? = null
    private var explorerSearchGeneration = 0
    fun stopExplorerSearch() {
        explorerSearchGeneration++
        explorerSearchJob?.cancel()
        explorerSearching = false
    }

    fun searchExplorer(query: String, path: String) {
        val generation = ++explorerSearchGeneration
        explorerSearchJob?.cancel()
        explorerHits = emptyList()
        explorerSearchMessage = null
        explorerSearching = query.isNotBlank()
        if (query.isBlank()) return
        explorerSearchJob = scope.launch {
            try {
                kotlinx.coroutines.delay(400) // Do not traverse the SMB tree for every keystroke.
                val profile = profileProvider() ?: error("Configure SMB in Settings first")
                withContext(Dispatchers.IO) {
                    SmbConnection(profile).use { conn ->
                        conn.open()
                        val pending = ArrayDeque<String>()
                        val visited = HashSet<String>()
                        val found = ArrayList<ExplorerHit>()
                        var failedFolders = 0
                        pending.add(path)
                        while (pending.isNotEmpty()) {
                            currentCoroutineContext().ensureActive()
                            val dir = pending.removeFirst()
                            if (!visited.add(dir)) continue
                            if (visited.size > 10000 || dir.count { it == '/' } > 128) { failedFolders++; continue }
                            val entries = try {
                                conn.list(dir)
                            } catch (e: Exception) {
                                currentCoroutineContext().ensureActive()
                                if (dir == path) throw e
                                failedFolders++
                                continue
                            }
                            for (entry in entries) {
                                val full = listOf(dir, entry.name).filter { it.isNotBlank() }.joinToString("/")
                                if (matchesFilename(entry.name, query)) found.add(ExplorerHit(full, entry))
                                if (entry.isDirectory && !entry.isReparsePoint) pending.add(full)
                            }
                            // Publish on the UI thread, with cancellation preventing stale results.
                            val snapshot = found.sortedWith(
                                compareByDescending<ExplorerHit> { it.entry.isDirectory }
                                    .thenBy { it.path.lowercase(java.util.Locale.ROOT) },
                            )
                            withContext(Dispatchers.Main) { explorerHits = snapshot }
                        }
                        withContext(Dispatchers.Main) {
                            if (failedFolders >
                                0
                            ) {
                                explorerSearchMessage = "$failedFolders folders could not be read. Results may be incomplete."
                            }
                        }
                    }
                }
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (
                e: Exception,
            ) {
                explorerSearchMessage = SmbErrors.describe(e)
            } finally {
                if (generation == explorerSearchGeneration) explorerSearching = false
            }
        }
    }

    var explorerFocusPath by mutableStateOf<String?>(null)
        private set
    private var browseJob: Job? = null
    private var browseGeneration = 0
    fun browse(path: String = explorerPath, focus: String? = null) {
        val generation = ++browseGeneration
        browseJob?.cancel()
        explorerFocusPath = focus
        val normalized = path.trim('/')
        explorerBusy = true
        explorerMessage = null
        browseJob = scope.launch {
            try {
                val profile = profileProvider() ?: error("Configure SMB in Settings first")
                val result = withContext(Dispatchers.IO) {
                    SmbConnection(profile).use { conn ->
                        conn.open()
                        conn.list(normalized)
                    }
                }
                explorerPath = normalized
                explorerEntries = result.sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            } catch(e: CancellationException) { throw e } catch (e: Exception) {
                explorerMessage = SmbErrors.describe(e)
            } finally {
                if (generation == browseGeneration) explorerBusy = false
            }
        }
    }

}
