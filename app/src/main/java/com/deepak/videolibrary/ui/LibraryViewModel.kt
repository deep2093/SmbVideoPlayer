package com.deepak.videolibrary.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.data.CustomFolderEntity
import com.deepak.videolibrary.data.GroupRow
import com.deepak.videolibrary.data.ScanErrorEntity
import com.deepak.videolibrary.data.ScanSessionEntity
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.scan.ScanMode
import com.deepak.videolibrary.scan.ScanWorker
import com.deepak.videolibrary.thumbs.ThumbStore
import com.deepak.videolibrary.thumbs.ThumbnailWorker
import java.io.File
import com.deepak.videolibrary.settings.SettingsStore
import com.deepak.videolibrary.settings.SmbSettings
import com.deepak.videolibrary.smb.SmbEntry
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbErrors
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val message: String) : TestState
    data class Failure(val message: String) : TestState
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val services = ServiceLocator.get(app)
    private val dao = services.db.dao()
    private val settingsStore = services.settings

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    // Grid preset preferences are independent for each library tab.
    private val layoutPrefs = app.getSharedPreferences("library_layout", Application.MODE_PRIVATE)
    private val isTablet = app.resources.configuration.smallestScreenWidthDp >= 600
    private val allowedGridPresets = LibraryLayout.presets.toSet()
    private fun restoredGrid(key: String, default: String): String =
        LibraryLayout.normalize(layoutPrefs.getString(key, default), default)

    var videoGridPreset by mutableStateOf(restoredGrid("video_grid_preset", if (isTablet) "4" else "2"))
        private set
    var folderGridPreset by mutableStateOf(restoredGrid("folder_grid_preset", if (isTablet) "3" else "2"))
        private set

    var videoListMode by mutableStateOf(layoutPrefs.getBoolean("video_list", false))
        private set
    var folderListMode by mutableStateOf(layoutPrefs.getBoolean("folder_list", false))
        private set
    var videoSort by mutableStateOf(VideoSort.entries.firstOrNull {
        it.name == layoutPrefs.getString("video_sort", "NAME")
    } ?: VideoSort.NAME)
        private set
    var sortDescending by mutableStateOf(layoutPrefs.getBoolean("sort_descending", false))
        private set

    var folderSort by mutableStateOf(layoutPrefs.getString("folder_sort", "NAME") ?: "NAME")
        private set
    var folderSortDescending by mutableStateOf(layoutPrefs.getBoolean("folder_sort_descending", false))
        private set

    fun selectFolderSort(sort: String) {
        folderSortDescending = if (folderSort == sort) !folderSortDescending else false
        folderSort = sort
        layoutPrefs.edit().putString("folder_sort", sort)
            .putBoolean("folder_sort_descending", folderSortDescending).apply()
    }

    fun toggleVideoLayout() {
        videoListMode = !videoListMode
        layoutPrefs.edit().putBoolean("video_list", videoListMode).apply()
    }

    fun toggleFolderLayout() {
        folderListMode = !folderListMode
        layoutPrefs.edit().putBoolean("folder_list", folderListMode).apply()
    }

    fun selectVideoSort(sort: VideoSort) {
        sortDescending = if (sort == videoSort) !sortDescending else false
        videoSort = sort
        layoutPrefs.edit().putString("video_sort", sort.name).putBoolean("sort_descending", sortDescending).apply()
    }

    fun updateVideoGridPreset(preset: String) {
        if (preset !in allowedGridPresets) return
        videoGridPreset = preset
        layoutPrefs.edit().putString("video_grid_preset", preset).apply()
    }

    fun updateFolderGridPreset(preset: String) {
        if (preset !in allowedGridPresets) return
        folderGridPreset = preset
        layoutPrefs.edit().putString("folder_grid_preset", preset).apply()
    }

    // ---- library ----

    val query = MutableStateFlow("")
    val groupFilter = MutableStateFlow<String?>(null)

    val videos: StateFlow<List<VideoEntity>> =
        combine(query, groupFilter) { q, g -> q.trim() to g }
            .flatMapLatest { (q, g) -> dao.observeVideos(g, q) }
            .state(emptyList())

    // Independent of the Videos tab's query/group selection.
    val allVideos: StateFlow<List<VideoEntity>> = dao.observeVideos(null, "").state(emptyList())
    var folderSearch by mutableStateOf("")
    var originalPath by mutableStateOf(settingsStore.load().rootPath.trim('/'))
    var originalQuery by mutableStateOf("")

    val groups: StateFlow<List<GroupRow>> = dao.observeGroups().state(emptyList())
    val videoCount: StateFlow<Int> = dao.observeVideoCount().state(0)

    var organizerError by mutableStateOf<String?>(null)
        private set
    var assignCandidates by mutableStateOf<List<VideoEntity>>(emptyList())
        private set

    fun createFolder(name: String) = organize {
        val clean = cleanFolderName(name) ?: return@organize
        dao.createFolder(CustomFolderEntity(clean))
    }

    fun renameFolder(from: String, to: String) = organize {
        val clean = cleanFolderName(to) ?: return@organize
        if (from.equals(clean, ignoreCase = true)) return@organize
        // Renaming an automatic group creates a user-maintained destination,
        // leaving filename-derived keys intact and preserving changes across rescans.
        dao.createFolder(CustomFolderEntity(clean))
        dao.renameAssignments(from, clean)
        dao.deleteFolderRow(from)
        // Move any still-auto-grouped videos too.
        dao.assignAutoGroup(from, clean)
    }

    fun mergeFolders(from: String, into: String) = organize {
        if (from == into) return@organize
        val target = cleanFolderName(into) ?: return@organize
        dao.createFolder(CustomFolderEntity(target))
        dao.renameAssignments(from, target)
        dao.assignAutoGroup(from, target)
        dao.deleteFolderRow(from)
    }

    fun assignVideo(id: Long, folder: String) = organize {
        val target = cleanFolderName(folder) ?: return@organize
        dao.createFolder(CustomFolderEntity(target))
        dao.assignVideo(id, target)
    }

    fun findVideosToAdd(folder: String, query: String) {
        viewModelScope.launch { assignCandidates = dao.findAssignableVideos(folder, query) }
    }

    private fun cleanFolderName(name: String): String? = name.trim().takeIf {
        it.isNotEmpty() && it.length <= 100 && !it.contains('/') && !it.contains('\\')
    }.also { if (it == null) organizerError = "Enter a folder name without slashes (max 100 characters)." }

    private fun organize(action: suspend () -> Unit) {
        organizerError = null
        viewModelScope.launch {
            try { action() }
            catch (e: Exception) { organizerError = e.message ?: "Unable to update folder" }
        }
    }

    // ---- thumbnails ----

    val thumbsDone: StateFlow<Int> = dao.observeThumbCount("DONE").state(0)
    val thumbsPending: StateFlow<Int> = dao.observeThumbCount("PENDING").state(0)
    val thumbsFailed: StateFlow<Int> = dao.observeThumbCount("FAILED").state(0)

    val thumbsActive: StateFlow<Boolean> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ThumbnailWorker.UNIQUE_NAME)
            .map { infos ->
                infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            }
            .state(false)

    suspend fun coverFor(group: String): File? = services.covers.build(group)

    suspend fun originalCover(path: String, videos: List<VideoEntity>): File? =
        services.covers.buildOriginal(path, videos.filter { it.thumbStatus == "DONE" }.map { it.id })

    fun startThumbnails() {
        if (settingsStore.loadProfile() == null) {
            notice = "Enter the server address and share name in Settings first."
            return
        }
        ThumbnailWorker.enqueue(getApplication())
    }

    fun retryFailedThumbnails() {
        viewModelScope.launch {
            dao.resetFailedThumbs()
            startThumbnails()
        }
    }

    /** Deletes cached thumbnails and covers only. Nothing on the SMB drive is touched. */
    fun clearThumbnailCache() {
        viewModelScope.launch {
            ThumbnailWorker.cancel(getApplication())
            withContext(Dispatchers.IO) { ThumbStore.clearAll(getApplication()) }
            dao.resetAllThumbs()
        }
    }

    init {
        // Pick up any thumbnails still waiting from an earlier session.
        viewModelScope.launch {
            if (settingsStore.loadProfile() != null && dao.countThumbs("PENDING") > 0) {
                ThumbnailWorker.enqueue(getApplication())
            }
        }
    }

    // ---- scanning ----

    val session: StateFlow<ScanSessionEntity?> = dao.observeLatestSession().state(null)
    val pendingDirs: StateFlow<Int> = dao.observePendingCount().state(0)

    val errors: StateFlow<List<ScanErrorEntity>> =
        session.flatMapLatest { s -> if (s == null) flowOf(emptyList()) else dao.observeErrors(s.id) }
            .state(emptyList())

    val scanActive: StateFlow<Boolean> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ScanWorker.UNIQUE_NAME)
            .map { infos ->
                infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            }
            .state(false)

    var notice by mutableStateOf<String?>(null)
        private set

    fun startScan(mode: ScanMode) {
        if (settingsStore.loadProfile() == null) {
            notice = "Enter the server address and share name in Settings first."
            return
        }
        notice = null
        ScanWorker.enqueue(getApplication(), mode)
    }

    fun stopScan() {
        ScanWorker.cancel(getApplication())
        ThumbnailWorker.cancel(getApplication())
    }

    fun dismissNotice() {
        notice = null
    }

    var discoveredHosts by mutableStateOf<List<String>>(emptyList())
        private set
    var discoveryRunning by mutableStateOf(false)
        private set

    var discoveryMessage by mutableStateOf("Connect to Wi-Fi, then search for SMB servers.")
        private set

    fun discoverHosts() {
        if (discoveryRunning) return
        discoveryRunning = true
        viewModelScope.launch {
            try {
                val result = com.deepak.videolibrary.smb.LanDiscovery.find(getApplication(), settings.host)
                discoveredHosts = result.hosts
                discoveryMessage = result.description
            } catch (e: Exception) {
                discoveredHosts = emptyList()
                discoveryMessage = "Discovery failed: ${e.message ?: e.javaClass.simpleName}"
            }
            finally { discoveryRunning = false }
        }
    }

    // ---- Read/write SMB explorer (paths are relative to share root) ----
    var explorerPath by mutableStateOf("")
        private set
    var explorerEntries by mutableStateOf<List<SmbEntry>>(emptyList())
        private set
    var explorerBusy by mutableStateOf(false)
        private set
    var explorerMessage by mutableStateOf<String?>(null)
        private set
    var explorerClipboard by mutableStateOf<Triple<String, Boolean, Boolean>?>(null)
        private set // path, isDirectory, cut

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
        explorerSearchJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(400) // Do not traverse the SMB tree for every keystroke.
                val profile = settingsStore.loadProfile() ?: error("Configure SMB in Settings first")
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
                            val entries = try { conn.list(dir) }
                                catch (e: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    if (dir == path) throw e
                                    failedFolders++
                                    continue
                                }
                            for (entry in entries) {
                                val full = listOf(dir, entry.name).filter { it.isNotBlank() }.joinToString("/")
                                if (matchesFilename(entry.name, query)) found.add(ExplorerHit(full, entry))
                                if (entry.isDirectory) pending.add(full)
                            }
                            // Publish on the UI thread, with cancellation preventing stale results.
                            val snapshot = found.sortedWith(compareByDescending<ExplorerHit> { it.entry.isDirectory }
                                .thenBy { it.path.lowercase(java.util.Locale.ROOT) })
                            withContext(Dispatchers.Main) { explorerHits = snapshot }
                        }
                        withContext(Dispatchers.Main) {
                            if (failedFolders > 0) explorerSearchMessage = "$failedFolders folders could not be read. Results may be incomplete."
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { explorerSearchMessage = SmbErrors.describe(e) }
            finally { if (generation == explorerSearchGeneration) explorerSearching = false }
        }
    }

    fun browse(path: String = explorerPath) {
        val normalized = path.trim('/')
        explorerBusy = true
        explorerMessage = null
        viewModelScope.launch {
            try {
                val profile = settingsStore.loadProfile() ?: error("Configure SMB in Settings first")
                val result = withContext(Dispatchers.IO) {
                    SmbConnection(profile).use { conn -> conn.open(); conn.list(normalized) }
                }
                explorerPath = normalized
                explorerEntries = result.sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            } catch (e: Exception) { explorerMessage = SmbErrors.describe(e) }
            finally { explorerBusy = false }
        }
    }

    /** Read-only display string. No credentials are included. */
    fun originalVideoLocation(relativePath: String): String {
        val profile = settingsStore.loadProfile() ?: return relativePath
        val parts = listOf(profile.host, profile.share, profile.rootPath, relativePath)
            .filter { it.isNotBlank() }
        return "\\\\" + parts.joinToString("\\") { it.trim('/', '\\').replace('/', '\\') }
    }

    fun stageExplorer(path: String, directory: Boolean, cut: Boolean) {
        explorerClipboard = Triple(path, directory, cut)
        explorerMessage = if (cut) "Ready to move: $path" else "Ready to copy: $path"
    }

    fun pasteExplorer() {
        val staged = explorerClipboard ?: return
        val filename = staged.first.substringAfterLast('/')
        val target = listOf(explorerPath, filename).filter { it.isNotBlank() }.joinToString("/")
        if (target == staged.first) { explorerMessage = "Choose another destination folder"; return }
        explorerOperate {
            if (staged.third) it.renamePath(staged.first, target, staged.second)
            else it.copyPath(staged.first, target, staged.second)
        }
        explorerClipboard = null
    }

    fun renameOriginal(path: String, newName: String, directory: Boolean = false) {
        val clean = newName.trim()
        if (clean.isEmpty() || clean == "." || clean == ".." || '/' in clean || '\\' in clean) {
            explorerMessage = "Invalid file name"; return
        }
        val parent = path.substringBeforeLast('/', "")
        val dest = listOf(parent, clean).filter { it.isNotBlank() }.joinToString("/")
        explorerOperate { it.renamePath(path, dest, directory) }
    }

    fun deleteOriginal(path: String, directory: Boolean) = explorerOperate { it.deletePath(path, directory) }

    private fun explorerOperate(action: (SmbConnection) -> Unit) {
        if (explorerBusy) return
        explorerBusy = true
        explorerMessage = null
        viewModelScope.launch {
            try {
                val profile = settingsStore.loadProfile() ?: error("Configure SMB in Settings first")
                withContext(Dispatchers.IO) {
                    SmbConnection(profile).use { conn -> conn.open(); action(conn) }
                }
                explorerMessage = "Done. Refreshing the library index…"
                ScanWorker.enqueue(getApplication(), ScanMode.REFRESH)
            } catch (e: Exception) { explorerMessage = SmbErrors.describe(e) }
            finally {
                explorerBusy = false; browse(explorerPath)
                if (explorerQuery.isNotBlank()) searchExplorer(explorerQuery, explorerPath)
            }
        }
    }

    // ---- settings ----

    var settings by mutableStateOf(settingsStore.load())
        private set

    var testState by mutableStateOf<TestState>(TestState.Idle)
        private set

    fun saveSettings(s: SmbSettings) {
        settingsStore.save(s)
        settings = settingsStore.load().let { saved ->
            // Keep what was typed in the form even if the password is not persisted.
            saved.copy(password = s.password)
        }
    }

    fun testConnection(s: SmbSettings) {
        if (s.host.isBlank() || s.share.isBlank()) {
            testState = TestState.Failure("Enter the server address and share name first.")
            return
        }
        testState = TestState.Running
        viewModelScope.launch {
            testState = try {
                val count = withContext(Dispatchers.IO) {
                    SmbConnection(settingsStore.toProfile(s)).use { conn ->
                        conn.open()
                        conn.list(SettingsStore.normalizeRoot(s.rootPath)).size
                    }
                }
                TestState.Success("Connected. Found $count items in the starting folder.")
            } catch (e: Exception) {
                TestState.Failure(SmbErrors.describe(e))
            }
        }
    }
}
