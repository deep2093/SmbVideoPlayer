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
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbErrors
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

    // Each layout has a separate, device-local tile-size preference. A tablet starts
    // with larger previews; users can fine-tune both grids without rescanning.
    private val layoutPrefs = app.getSharedPreferences("library_layout", Application.MODE_PRIVATE)
    private val isTablet = app.resources.configuration.smallestScreenWidthDp >= 600
    var videoTileDp by mutableIntStateOf(layoutPrefs.getInt("video_tile_dp", if (isTablet) 300 else 180))
        private set
    var folderTileDp by mutableIntStateOf(layoutPrefs.getInt("folder_tile_dp", if (isTablet) 320 else 190))
        private set

    fun setVideoTileSize(size: Int) {
        videoTileDp = size.coerceIn(160, 440)
        layoutPrefs.edit().putInt("video_tile_dp", videoTileDp).apply()
    }

    fun setFolderTileSize(size: Int) {
        folderTileDp = size.coerceIn(160, 440)
        layoutPrefs.edit().putInt("folder_tile_dp", folderTileDp).apply()
    }

    // ---- library ----

    val query = MutableStateFlow("")
    val groupFilter = MutableStateFlow<String?>(null)

    val videos: StateFlow<List<VideoEntity>> =
        combine(query, groupFilter) { q, g -> q.trim() to g }
            .flatMapLatest { (q, g) -> dao.observeVideos(g, q) }
            .state(emptyList())

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

    fun discoverHosts() {
        if (discoveryRunning) return
        discoveryRunning = true
        viewModelScope.launch {
            try { discoveredHosts = com.deepak.videolibrary.smb.LanDiscovery.hosts() }
            catch (_: Exception) { discoveredHosts = emptyList() }
            finally { discoveryRunning = false }
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
