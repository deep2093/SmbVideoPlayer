package com.deepak.videolibrary.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.data.*
import com.deepak.videolibrary.data.CustomFolderEntity
import com.deepak.videolibrary.data.ScanErrorEntity
import com.deepak.videolibrary.data.ScanSessionEntity
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.files.TransferWorker
import com.deepak.videolibrary.scan.ScanMode
import com.deepak.videolibrary.scan.ScanWorker
import com.deepak.videolibrary.settings.SettingsStore
import com.deepak.videolibrary.settings.SmbSettings
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbEntry
import com.deepak.videolibrary.smb.SmbErrors
import com.deepak.videolibrary.thumbs.ThumbStore
import com.deepak.videolibrary.thumbs.ThumbnailWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val message: String) : TestState
    data class Failure(val message: String) : TestState
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel @JvmOverloads constructor(app: Application, private val services: ServiceLocator = ServiceLocator.get(app)) : AndroidViewModel(app) {
    val artworkContext get() = services.artworkContext
    private val dao = services.db.dao()
    private val settingsStore = services.settings
    private val stateDao = services.db.libraryState()
    var settings by mutableStateOf(settingsStore.load())
        private set
    val namespace = MutableStateFlow(connectionKey(settings))
    private val storageReady = MutableStateFlow(false)
    // Which Folders tabs show, in order (0 Custom, 1 Virtual, 2 Original). At least one always shows.
    private val sectionPrefs = app.getSharedPreferences("folder_sections", android.content.Context.MODE_PRIVATE)
    var folderSections by mutableStateOf(FolderSections.parse(sectionPrefs.getString("order", null)))
        private set
    fun updateFolderSections(sections: List<Int>) {
        if (sections.isEmpty()) return
        val current = folderSections.getOrNull(foldersTab)
        folderSections = sections
        sectionPrefs.edit().putString("order", sections.joinToString(",")).apply()
        // Stay on the same tab if it is still shown.
        foldersTab = sections.indexOf(current).takeIf { it >= 0 } ?: 0
    }

    /** The open Folders page (an index into [folderSections]); starts on Virtual folders when it is shown. */
    var foldersTab by mutableIntStateOf(folderSections.indexOf(1).takeIf { it >= 0 } ?: 0)

    /** Page state kept while the app is open (open Settings sections, scroll positions). */
    val ui = UiSession()
    val matchFolderFilter = MutableStateFlow<MatchFolderEntity?>(null)
    val matchFolderQuery = MutableStateFlow("")
    private val folderQueryFlow = MutableStateFlow("")
    private val originalQueryFlow = MutableStateFlow("")
    private val originalLocation = MutableStateFlow(settings.rootPath.trim('/'))
    private fun connectionKey(s: SmbSettings) =
        s.host.trim().lowercase(java.util.Locale.ROOT) + "/" + s.share.trim().lowercase(java.util.Locale.ROOT)

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    // Grid preset preferences are independent for each library tab.
    private val layoutPrefs = app.getSharedPreferences("library_layout", Application.MODE_PRIVATE)
    var navigationPosition by mutableStateOf(layoutPrefs.getString("navigation_position", "SIDEBAR") ?: "SIDEBAR")
        private set
    fun updateNavigationPosition(value: String) {
        if (value !in
            setOf("SIDEBAR", "FLOATING")
        ) {
            return
        }
        navigationPosition = value
        layoutPrefs.edit().putString("navigation_position", value).apply()
    }
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
    var videoSort by mutableStateOf(
        VideoSort.entries.firstOrNull {
            it.name == layoutPrefs.getString("video_sort", "NAME")
        } ?: VideoSort.NAME,
    )
        private set
    var sortDescending by mutableStateOf(layoutPrefs.getBoolean("sort_descending", false))
        private set

    var folderSort by mutableStateOf(layoutPrefs.getString("folder_sort", "NAME") ?: "NAME")
        private set
    var folderSortDescending by mutableStateOf(layoutPrefs.getBoolean("folder_sort_descending", false))
        private set

    private val sortChoice = MutableStateFlow(videoSort.name to sortDescending)
    private val folderSortChoice = MutableStateFlow(folderSort to folderSortDescending)
    fun selectFolderSort(sort: String) {
        folderSortDescending = if (folderSort == sort) !folderSortDescending else false
        folderSort = sort
        folderSortChoice.value = folderSort to folderSortDescending
        layoutPrefs
            .edit()
            .putString("folder_sort", sort)
            .putBoolean("folder_sort_descending", folderSortDescending)
            .apply()
    }

    fun toggleVideoLayout() {
        videoListMode = !videoListMode
        layoutPrefs.edit().putBoolean("video_list", videoListMode).apply()
    }

    // ---- File Explorer layout ----
    /** NAME, DATE, SIZE or TYPE. Folders are always listed before files. */
    var explorerSort by mutableStateOf(layoutPrefs.getString("explorer_sort", "NAME") ?: "NAME")
        private set
    var explorerSortDescending by mutableStateOf(layoutPrefs.getBoolean("explorer_sort_descending", false))
        private set
    var explorerGridMode by mutableStateOf(layoutPrefs.getBoolean("explorer_grid", false))
        private set

    /** Choosing the current sort again flips its direction, like the other library sorts. */
    fun selectExplorerSort(sort: String) {
        explorerSortDescending = if (explorerSort == sort) !explorerSortDescending else false
        explorerSort = sort
        layoutPrefs.edit()
            .putString("explorer_sort", sort)
            .putBoolean("explorer_sort_descending", explorerSortDescending)
            .apply()
    }

    // ---- Card shape per page ----
    private val pageShapes = PageShapeStore(app)

    /** True when the cards on [page] are taller (3:4) instead of landscape. Separate for each server. */
    fun isTallPage(page: String): Boolean = pageShapes.isTall(namespace.value + "|" + page)

    fun setTallPage(page: String, tall: Boolean) = pageShapes.setTall(namespace.value + "|" + page, tall)

    fun toggleExplorerLayout() {
        explorerGridMode = !explorerGridMode
        layoutPrefs.edit().putBoolean("explorer_grid", explorerGridMode).apply()
    }

    /** Indexed library rows (with thumbnails) for the given share-relative paths. */
    suspend fun indexedVideos(paths: List<String>): Map<String, VideoEntity> = withContext(Dispatchers.IO) {
        paths.distinct().chunked(500).flatMap { dao.videosByPaths(it) }.associateBy { it.relativePath }
    }

    fun toggleFolderLayout() {
        folderListMode = !folderListMode
        layoutPrefs.edit().putBoolean("folder_list", folderListMode).apply()
    }

    fun selectVideoSort(sort: VideoSort) {
        sortDescending = if (sort == videoSort) !sortDescending else false
        videoSort = sort
        sortChoice.value = videoSort.name to sortDescending
        layoutPrefs
            .edit()
            .putString("video_sort", sort.name)
            .putBoolean("sort_descending", sortDescending)
            .apply()
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

    /** Pinch out for larger cards / list, pinch in for denser grids. */
    fun pinchLibraryLayout(folders: Boolean, step: Int) {
        if (step == 0) return
        val presets = LibraryLayout.presets.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        if (presets.isEmpty()) return
        val inList = if (folders) folderListMode else videoListMode
        val current = if (folders) folderGridPreset else videoGridPreset
        val at = if (inList) 0 else presets.indexOf(current).coerceAtLeast(0) + 1
        val next = (at + step.coerceIn(-1, 1)).coerceIn(0, presets.size)
        if (next == at) return
        if (next == 0) {
            if (!inList) { if (folders) toggleFolderLayout() else toggleVideoLayout() }
        } else if (folders) {
            updateFolderGridPreset(presets[next - 1])
            if (folderListMode) toggleFolderLayout()
        } else {
            updateVideoGridPreset(presets[next - 1])
            if (videoListMode) toggleVideoLayout()
        }
    }

    // ---- library ----

    val query = MutableStateFlow("")
    val groupFilter = MutableStateFlow<String?>(null)

    private var folderSearchValue by mutableStateOf("")
    var folderSearch: String
        get() = folderSearchValue
        set(value) {
            folderSearchValue = value
            folderQueryFlow.value = value
        }

    /** Saved grid scroll (index, offset) per original-folder path; survives leaving the screen. */
    val originalScroll = HashMap<String, Pair<Int, Int>>()
    private var originalPathValue by mutableStateOf(settings.rootPath.trim('/'))
    var originalPath: String
        get() = originalPathValue
        set(value) {
            originalPathValue = value
            originalLocation.value = value
        }
    private var originalQueryValue by mutableStateOf("")
    var originalQuery: String
        get() = originalQueryValue
        set(value) {
            originalQueryValue = value
            originalQueryFlow.value = value
        }

    private val videoScope = combine(query.debounce(200), groupFilter, sortChoice, namespace, matchFolderFilter) { q, g, sort, key, rule ->
        LibraryScope(key, q, g, sort = sort.first, descending = sort.second, match = rule?.takeIf { it.sourcePath == null }?.term, sourcePath = rule?.sourcePath, includeSubfolders = rule?.includeSubfolders ?: true)
    }
    private val folderScope = combine(folderQueryFlow.debounce(200), sortChoice, namespace) { q, sort, key ->
        LibraryScope(key, q, groupMatches = true, sort = sort.first, descending = sort.second)
    }
    private val originalScope = combine(originalLocation, originalQueryFlow.debounce(200), sortChoice, namespace) { dir, q, sort, key ->
        LibraryScope(key, q, directory = dir, subtree = q.isNotBlank(), pathMatches = true, sort = sort.first, descending = sort.second)
    }
    private fun pages(scopes: Flow<LibraryScope>) = scopes
        .flatMapLatest { scope ->
            Pager(PagingConfig(pageSize = 60, initialLoadSize = 90, enablePlaceholders = false), pagingSourceFactory = {
                stateDao.pages(LibraryQueries.videos(scope))
            }).flow
        }.cachedIn(viewModelScope)
    val videoPages = pages(videoScope)
    val folderPages = pages(folderScope)
    val videoResultsCount = videoScope.flatMapLatest { stateDao.count(LibraryQueries.count(it)) }.state(0)
    val folderResultsCount = folderScope.flatMapLatest { stateDao.count(LibraryQueries.count(it)) }.state(0)
    val groups = combine(folderScope, folderSortChoice) { scope, sort -> LibraryQueries.groups(scope, sort.first, sort.second) }
        .flatMapLatest { stateDao.folders(it) }
        .state(emptyList())
    val matchingFolders = combine(
        namespace,
        matchFolderQuery.debounce(200),
    ) { key, q -> LibraryQueries.matchingFolders(key, q) }
        .flatMapLatest {
            stateDao.matchingFolders(it)
        }.state(emptyList())

    private val folderCollections = FolderCollectionRepository(services.db) { settingsStore.toProfile(settings) }
    suspend fun folderChoices(path: String) = folderCollections.children(path)
    suspend fun descendantFolders(path: String) = folderCollections.descendants(path)
    fun addSourceFolders(paths: Set<String>, recursive: Boolean, onSaved: () -> Unit) {
        organizerError = null
        viewModelScope.launch {
            try {
                folderCollections.add(paths,recursive,namespace.value)
                onSaved(); ScanWorker.enqueue(getApplication(), ScanMode.REFRESH, afterCurrent = true, libraryId = services.libraryId)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { organizerError = "Could not add folders. Check the connection and try again." }
        }
    }
    fun createMatchingFolder(name: String) = organize {
        val clean =
            cleanFolderName(name) ?: return@organize
        stateDao.createMatch(MatchFolderEntity(connectionKey = namespace.value, name = clean, term = clean))
        restoreSavedMatchCovers()
    }
    fun renameMatchingFolder(folder: MatchFolderEntity, name: String) = organize {
        val clean =
            cleanFolderName(name) ?: return@organize
        stateDao.renameMatch(folder.id, clean)
        coverRevision++
    }
    fun deleteMatchingFolder(folder: MatchFolderEntity) = organize {
        stateDao.deleteMatch(folder.id)
        keepCover(com.deepak.videolibrary.artwork.ArtworkKeys.matchFolder(folder.id), savedCoverKey("match", folder.name))
    }
    fun hideMatchingFolder(
        folder: MatchFolderEntity,
    ) {
        viewModelScope.launch { stateDao.hide(HiddenItem(namespace.value, "MATCH", folder.id.toString(), folder.name)) }
    }
    fun stageMatchingFolder(folder: MatchFolderEntity, cut: Boolean) {
        viewModelScope.launch {
            val rows =
                withContext(
                    Dispatchers.IO,
                ) { stateDao.videoList(LibraryQueries.videos(LibraryScope(namespace.value, match = folder.takeIf { it.sourcePath == null }?.term, sourcePath = folder.sourcePath, includeSubfolders = folder.includeSubfolders))) }
            clipboardBatch = rows.map { Triple(it.relativePath, false, cut) }
            explorerClipboard = clipboardBatch.firstOrNull()
            notice = "${rows.size} matching videos ready to ${if (cut) "move" else "copy"}."
        }
    }
    fun mergeMatchingFolder(folder: MatchFolderEntity, targetName: String) = organize {
        val target = cleanFolderName(targetName) ?: return@organize
        val rows = stateDao.videoList(LibraryQueries.videos(LibraryScope(namespace.value, match = folder.takeIf { it.sourcePath == null }?.term, sourcePath = folder.sourcePath, includeSubfolders = folder.includeSubfolders)))
        services.db.withTransaction {
            dao.createFolder(CustomFolderEntity(target))
            rows.map { it.relativePath }.chunked(500).forEach { dao.assignPaths(it, target) }
        }
    }
    suspend fun matchingCover(folder: MatchFolderEntity, key: String): File? = artwork.cover("match:${folder.id}",key,
        LibraryScope(namespace.value,match=folder.takeIf { it.sourcePath == null }?.term,sourcePath=folder.sourcePath,includeSubfolders=folder.includeSubfolders))
    val originalListing = originalScope.flatMapLatest { scope ->
        val directories = if(scope.directory == settings.rootPath.trim('/')) LibraryQueries.allDirectories(scope) else LibraryQueries.directories(scope)
        combine(stateDao.directories(directories), stateDao.count(LibraryQueries.count(scope)), stateDao.count(LibraryQueries.count(scope.copy(subtree=true)))) { rows, direct, total ->
            OriginalListing(scope.directory.orEmpty(),scope.query,
                rows.sortedWith(compareBy<DirectorySummary> { originalFolderLabel(it.path).lowercase(java.util.Locale.ROOT) }.thenBy { it.path }),direct,total,true)
        }
    }.state(OriginalListing())
    fun originalPagesFor(path: String, text: String): Flow<androidx.paging.PagingData<VideoEntity>> {
        val scope=LibraryScope(namespace.value,text,directory=path,subtree=text.isNotBlank(),pathMatches=true,sort=videoSort.name,descending=sortDescending)
        return Pager(PagingConfig(pageSize=60,initialLoadSize=90,enablePlaceholders=true,jumpThreshold=180),pagingSourceFactory={stateDao.pages(LibraryQueries.videos(scope))}).flow
    }
    val continuing = namespace.flatMapLatest { stateDao.continuing(LibraryQueries.continuing(it)) }.state(emptyList())
    val hiddenItems = namespace.flatMapLatest { stateDao.hiddenItems(it) }.state(emptyList())
    val videoCount = dao.observeVideoCount().state(0)

    fun progressFlow(path: String) = stateDao.observeProgress(namespace.value, path)
    suspend fun resumeFor(path: String): PlaybackStateEntity? {
        storageReady.first { it }
        return stateDao.progress(namespace.value, path)
    }
    fun saveProgress(path: String, position: Long, duration: Long) {
        val key = namespace.value
        viewModelScope.launch(Dispatchers.IO) {
            storageReady.first { it }
            stateDao.saveProgress(
                key,
                path,
                com.deepak.videolibrary.player.PlaybackPolicy
                    .resumePosition(position, duration),
                duration,
            )
        }
    }
    fun hideContinue(path: String, hidden: Boolean = true) {
        val key = namespace.value
        viewModelScope.launch(Dispatchers.IO) {
            storageReady.first { it }
            stateDao.hideContinue(key, path, hidden)
        }
    }
    fun playFromStart(path: String, onPlay: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                storageReady.first { it }
                stateDao.saveProgress(
                    namespace.value,
                    path,
                    0,
                    stateDao.progress(namespace.value, path)?.durationMs ?: 0,
                )
            }
            onPlay()
        }
    }
    fun clearResume(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            storageReady.first { it }
            stateDao.saveProgress(namespace.value, path, 0, stateDao.progress(namespace.value, path)?.durationMs ?: 0)
        }
    }
    fun hidePath(path: String, directory: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            stateDao.hide(HiddenItem(namespace.value, if (directory) "DIRECTORY" else "FILE", path, path.substringAfterLast('/')))
            coverRevision++
        }
    }
    fun hideGroup(
        group: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            stateDao.hide(HiddenItem(namespace.value, "GROUP", group, group))
            coverRevision++
        }
    }
    fun unhide(item: HiddenItem) {
        viewModelScope.launch(Dispatchers.IO) {
            stateDao.unhide(item)
            coverRevision++
        }
    }
    fun unhideAll() {
        viewModelScope.launch(Dispatchers.IO) {
            stateDao.unhideAll(namespace.value)
            coverRevision++
        }
    }
    private fun selectedScope(mode: String): LibraryScope = when {
        mode.startsWith("own:") -> ownFolders.value.firstOrNull { it.id == mode.removePrefix("own:") }?.let(::ownFolderScope)
            ?: LibraryScope(namespace.value, members = FolderMembers(emptyList(), emptyList()))
        else -> selectedScopeFor(mode)
    }
    private fun selectedScopeFor(mode: String): LibraryScope = when (mode) {
        "folders" -> LibraryScope(namespace.value, folderSearch, groupMatches = true, sort = videoSort.name, descending = sortDescending)
        "originals" -> LibraryScope(
            namespace.value,
            originalQuery,
            directory = originalPath,
            subtree = true,
            pathMatches = true,
            sort = videoSort.name,
            descending = sortDescending,
        )
        else -> LibraryScope(
            namespace.value,
            query.value,
            groupFilter.value,
            sort = videoSort.name,
            descending = sortDescending,
            match = matchFolderFilter.value?.takeIf { it.sourcePath == null }?.term,
            sourcePath = matchFolderFilter.value?.sourcePath,
            includeSubfolders = matchFolderFilter.value?.includeSubfolders ?: true,
        )
    }
    private val playbackQueue = com.deepak.videolibrary.player.LibraryPlaybackQueue(stateDao)
    var playbackHasMore by mutableStateOf(false)
        private set
    private var queueLoading = false
    private var queueJob: Job? = null
    private var queueSerial = 0
    fun resetPlaybackQueue() {
        queueSerial++
        queueJob?.cancel(); queueJob=null; queueLoading=false
        playbackQueue.reset(); playbackHasMore=false
    }
    fun playLibrary(mode: String, start: VideoEntity? = null, onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
        if(queueLoading)return
        val scope=selectedScope(mode)
        val serial=++queueSerial
        queueLoading=true
        queueJob=viewModelScope.launch {
            try {
                val queue=playbackQueue.begin(scope,start)
                playbackHasMore=playbackQueue.hasMore
                if(queue.isNotEmpty())onPlay(queue.first(),queue) else notice="No visible videos match this search."
            } catch(e:CancellationException){throw e}
            catch(_:Exception){notice="Could not load the playback queue. Try again."}
            finally {if(serial==queueSerial)queueLoading=false}
        }
    }
    fun nextPlaybackBatch(onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
        if(queueLoading || !playbackHasMore)return
        val serial=queueSerial
        queueLoading=true
        queueJob=viewModelScope.launch {
            try {
                val queue=playbackQueue.next()
                playbackHasMore=playbackQueue.hasMore
                if(queue.isNotEmpty())onPlay(queue.first(),queue)
            } catch(e:CancellationException){throw e}
            catch(_:Exception){notice="Could not load the next videos. Try Next again."}
            finally {if(serial==queueSerial)queueLoading=false}
        }
    }
    var pasteDestination by mutableStateOf<String?>(null)
    var explorerNavigationRequest by mutableStateOf<String?>(null)
        private set
    fun openFailureFolder(path: String) {
        explorerNavigationRequest = path
    }
    fun consumeExplorerNavigation() {
        explorerNavigationRequest = null
    }
    val failedThumbnails = namespace.flatMapLatest { stateDao.thumbnailFailures(LibraryQueries.thumbnailFailures(it)) }.state(emptyList())

    // Failures the user cleared from the list. Kept per library in preferences (no database change);
    // the videos themselves are untouched and a retried file can fail and show up again.
    private val failureDismissPrefs = app.getSharedPreferences("thumb-failures-dismissed-${services.libraryId}", android.content.Context.MODE_PRIVATE)
    private val dismissedFailures = MutableStateFlow(failureDismissPrefs.getStringSet("paths", emptySet()).orEmpty().toSet())
    val visibleFailedThumbnails = combine(failedThumbnails, dismissedFailures) { failures, dismissed ->
        failures.filter { it.relativePath !in dismissed }
    }.state(emptyList())
    private fun updateDismissedFailures(change: (Set<String>) -> Set<String>) {
        val updated = change(dismissedFailures.value)
        dismissedFailures.value = updated
        failureDismissPrefs.edit().putStringSet("paths", updated).apply()
    }
    /** Hides the given failed thumbnails from the Scan list. Pass every visible path to clear all. */
    fun dismissThumbnailFailures(paths: Collection<String>) = updateDismissedFailures { it + paths }
    fun retryThumbnail(id: Long) {
        failedThumbnails.value.firstOrNull { it.id == id }?.let { video -> updateDismissedFailures { it - video.relativePath } }
        viewModelScope.launch {
            dao.setThumbStatus(id, "PENDING")
            startThumbnails()
        }
    }
    var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        private set
    var selectionScope by mutableStateOf("videos")
        private set
    /** Selected entries that are folders (Files can select folders as well as files). */
    var selectedDirectories by mutableStateOf<Set<String>>(emptySet())
        private set
    fun toggleSelected(path: String, mode: String = "videos", directory: Boolean = false) {
        // Starting to select in another list drops the old selection, so lists never mix.
        if (selectionScope != mode) {
            selectedDirectories = emptySet()
            selectedPaths = emptySet()
        }
        selectionScope = mode
        val removing = path in selectedPaths
        selectedPaths =
            if (removing)selectedPaths - path else selectedPaths + path
        if (directory || removing) selectedDirectories = if (removing) selectedDirectories - path else selectedDirectories + path
    }
    fun clearSelection() {
        selectedPaths = emptySet()
        selectedDirectories = emptySet()
    }
    /** Downloads the selection: folders with everything inside them, files one by one. */
    fun downloadSelected() {
        val dirs = selectedDirectories
        dirs.forEach(::downloadDirectory)
        (selectedPaths - dirs).takeIf { it.isNotEmpty() }?.let(::downloadFiles)
        clearSelection()
    }
    /** Deletes the selected files from the server (queued like other file operations), then ends selection. */
    fun deleteSelected() {
        val paths = selectedPaths.toList()
        val dirs = selectedDirectories
        paths.forEach { TransferWorker.enqueue(getApplication(), "DELETE", it, "", it in dirs, namespace.value, services.libraryId) }
        clearSelection()
        notice = "Deleting ${paths.size} ${if (paths.size == 1) "video" else "videos"}."
    }
    /** What Select all takes in Files: every listed folder and video (other files and hidden items are left out). */
    fun explorerSelectable(): List<Pair<String, SmbEntry>> {
        val all = if (explorerQuery.isNotBlank()) {
            explorerHits.map { it.path to it.entry }
        } else {
            explorerEntries.map { listOf(explorerPath, it.name).filter(String::isNotBlank).joinToString("/") to it }
        }
        val hiddenPaths = hiddenItems.value.filter { it.kind == "DIRECTORY" || it.kind == "FILE" }.map { it.target.trim('/') }.toSet()
        return all.filter { (path, entry) ->
            (entry.isDirectory || isPlayable(entry)) &&
                !com.deepak.videolibrary.files.ExplorerHiding.shouldHide(path, entry.name, hideSystemFolders, hiddenPaths)
        }
    }
    fun selectAll(mode: String) {
        viewModelScope.launch {
            if (selectionScope != mode) selectedDirectories = emptySet()
            selectionScope = mode
            if (mode == CONTINUE_SCOPE) {
                selectedPaths = continuing.value.map { it.relativePath }.toSet()
                return@launch
            }
            if (mode == "explorer") {
                val listed = explorerSelectable()
                selectedPaths = listed.map { it.first }.toSet()
                selectedDirectories = listed.filter { it.second.isDirectory }.map { it.first }.toSet()
                return@launch
            }
            selectedDirectories = emptySet()
            val scope = selectedScope(mode)
            selectedPaths = withContext(Dispatchers.IO) { stateDao.videoList(LibraryQueries.videos(scope)).map { it.relativePath }.toSet() }
        }
    }
    fun stageSelected(cut: Boolean) {
        clipboardBatch = selectedPaths.map { Triple(it, it in selectedDirectories, cut) }
        explorerClipboard = clipboardBatch.firstOrNull()
        clearSelection()
    }
    fun mergeSelected(folder: String) {
        val target = cleanFolderName(folder) ?: return
        val paths = (selectedPaths - selectedDirectories).toList()
        viewModelScope.launch {
            services.db.withTransaction {
                dao.createFolder(CustomFolderEntity(target))
                paths.chunked(500).forEach { dao.assignPaths(it, target) }
            }
            clearSelection()
            notice = "${paths.size} videos assigned to virtual folder '$target'."
        }
    }
    var clipboardBatch by mutableStateOf<List<Triple<String, Boolean, Boolean>>>(emptyList())
        private set
    fun stageGroup(group: String, cut: Boolean) {
        viewModelScope.launch {
            val rows =
                withContext(Dispatchers.IO) { stateDao.videoList(LibraryQueries.videos(LibraryScope(namespace.value, group = group))) }
            clipboardBatch = rows.map { Triple(it.relativePath, false, cut) }
            explorerClipboard = clipboardBatch.firstOrNull()
            notice = "${rows.size} videos ${if (cut) "cut" else "copied"}. Choose Paste to select a destination."
        }
    }
    fun deleteGroupFiles(group: String) {
        viewModelScope.launch(Dispatchers.IO) {
            stateDao.videoList(LibraryQueries.videos(LibraryScope(namespace.value, group = group))).forEach {
                TransferWorker.enqueue(getApplication(), "DELETE", it.relativePath, "", false, namespace.value, services.libraryId)
            }
        }
    }
    fun clearClipboard() {
        explorerClipboard = null
        clipboardBatch = emptyList()
    }
    val transfers = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(TransferWorker.name(services.libraryId)).state(emptyList())
    fun pasteInto(directory: String) {
        val entries = clipboardBatch.ifEmpty { listOfNotNull(explorerClipboard) }
        if (entries.isEmpty()) return
        val names = entries.map { it.first.substringAfterLast('/') }
        if (names.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size != names.size) {
            notice = "Several selected files have the same filename. Paste them individually to avoid overwriting."
            return
        }
        if (entries.any { listOf(directory, it.first.substringAfterLast('/')).filter(String::isNotBlank).joinToString("/") == it.first }) {
            notice = "Choose another destination folder."
            return
        }
        entries.forEach { clip ->
            val dest = listOf(directory, clip.first.substringAfterLast('/')).filter(String::isNotBlank).joinToString("/")
            TransferWorker.enqueue(getApplication(), if (clip.third) "MOVE" else "COPY", clip.first, dest, clip.second, namespace.value, services.libraryId)
        }
        notice = "${entries.size} file operation(s) queued. You can keep using the app."
        clearClipboard()
    }
    fun cancelTransfer(id: java.util.UUID) {
        WorkManager.getInstance(getApplication()).cancelWorkById(id)
    }

    var organizerError by mutableStateOf<String?>(null)
        private set
    var assignCandidates by mutableStateOf<List<VideoEntity>>(emptyList())
        private set

    fun createFolder(name: String) = organize {
        val clean = cleanFolderName(name) ?: return@organize
        dao.createFolder(CustomFolderEntity(clean))
    }

    private suspend fun moveVisibleGroupAssignments(from: String, target: String) {
        val paths = stateDao.videoList(LibraryQueries.videos(LibraryScope(namespace.value, group = from))).map { it.relativePath }
        services.db.withTransaction {
            dao.createFolder(CustomFolderEntity(target))
            paths.chunked(500).forEach { dao.assignPaths(it, target) }
            dao.deleteFolderRow(from)
        }
        coverRevision++
    }
    fun renameFolder(from: String, to: String) = organize {
        val clean = cleanFolderName(to) ?: return@organize
        if (from.equals(clean, ignoreCase = true)) return@organize
        moveVisibleGroupAssignments(from, clean)
        // The folder's chosen cover follows the new name.
        services.artwork.ensureLoaded()
        services.artwork.move(com.deepak.videolibrary.artwork.ArtworkKeys.virtualFolder(from), com.deepak.videolibrary.artwork.ArtworkKeys.virtualFolder(clean))
    }
    fun mergeFolders(from: String, into: String) = organize {
        if (from == into) return@organize
        val target = cleanFolderName(into) ?: return@organize
        moveVisibleGroupAssignments(from, target)
        // The merged-away folder no longer exists; the destination keeps its own cover.
        services.artwork.ensureLoaded()
        services.artwork.clear(listOf(com.deepak.videolibrary.artwork.ArtworkKeys.virtualFolder(from)))
    }

    fun assignVideo(id: Long, folder: String) = organize {
        val target = cleanFolderName(folder) ?: return@organize
        dao.createFolder(CustomFolderEntity(target))
        dao.assignVideo(id, target)
    }

    fun findVideosToAdd(folder: String, query: String) {
        viewModelScope.launch { assignCandidates = dao.findAssignableVideos(folder, query) }
    }

    private fun cleanFolderName(name: String): String? = name
        .trim()
        .takeIf {
            it.isNotEmpty() && it.length <= 100 && !it.contains('/') && !it.contains('\\')
        }.also { if (it == null) organizerError = "Enter a folder name without slashes (max 100 characters)." }

    private fun organize(action: suspend () -> Unit) {
        organizerError = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: Exception) {
                organizerError = e.message ?: "Unable to update folder"
            }
        }
    }

    // ---- Covers of folders you delete are kept under the folder's name and given back if you make it again ----
    private fun savedCoverKey(kind: String, name: String) = "saved:$kind:" + name.trim().lowercase(java.util.Locale.ROOT)

    /** Moves the cover of [key] (and of keys written as "[key]:…", a Virtual folder's subfolders) to [saved]. */
    private suspend fun keepCover(key: String, saved: String) {
        services.artwork.ensureLoaded()
        services.artwork.moveExact(services.artwork.keysStartingWith(key).map { it to saved + it.removePrefix(key) })
    }

    /** Gives back covers kept by [keepCover]. */
    private suspend fun restoreCover(saved: String, key: String) {
        services.artwork.ensureLoaded()
        val pairs = services.artwork.keysStartingWith(saved).map { it to key + it.removePrefix(saved) }
        if (pairs.isNotEmpty()) {
            services.artwork.moveExact(pairs)
            coverRevision++
        }
    }

    /** Custom folders added again (by name) get back the covers they had. */
    private suspend fun restoreSavedMatchCovers() {
        services.artwork.ensureLoaded()
        val folders = withContext(Dispatchers.IO) { stateDao.savedCollections(namespace.value) }
        folders.forEach { folder ->
            val key = com.deepak.videolibrary.artwork.ArtworkKeys.matchFolder(folder.id)
            if (!services.artwork.hasStoredData(key)) restoreCover(savedCoverKey("match", folder.name), key)
        }
    }

    // ---- Own custom folders (named by the user, filled with chosen drive folders and files) ----
    private val ownStore = OwnFolderStore(app)
    private val ownRevision = MutableStateFlow(0)
    val ownFolders: StateFlow<List<OwnFolder>> = combine(namespace, ownRevision) { key, _ ->
        withContext(Dispatchers.IO) { ownStore.load(key) }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }.state(emptyList())

    /** The own folder whose page is open in Custom folders, or null for the list. */
    var openOwnFolderId by mutableStateOf<String?>(null)

    private fun editOwn(change: (List<OwnFolder>) -> List<OwnFolder>) {
        val key = namespace.value
        ownStore.save(key, change(ownStore.load(key)))
        ownRevision.value++
    }

    /**
     * Single videos ticked in "Add drive folders" stay with the folder they are in: videos from Folder 1 make (or
     * join) a "Folder 1" card. Nothing is grouped across folders, so no video shows up twice. A card already made
     * this way for the same drive folder is reused.
     */
    fun addFilesAsOwnFolders(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val byFolder = paths.map { it.trim('/') }.filter { it.isNotEmpty() }.distinct().groupBy { it.substringBeforeLast('/', "") }
        editOwn { list ->
            var result = list
            byFolder.forEach { (parent, files) ->
                // Reuse a card that holds only single videos from this same drive folder.
                val existing = result.firstOrNull { own ->
                    own.folders.isEmpty() && own.files.isNotEmpty() && own.files.all { it.substringBeforeLast('/', "") == parent }
                }
                result = if (existing != null) {
                    result.map { if (it.id == existing.id) it.copy(files = (it.files + files).distinct()) else it }
                } else {
                    val label = if (parent.isEmpty()) settings.share.ifBlank { "Share root" } else originalDisplayName(parent)
                    val clean = cleanFolderName(label) ?: return@forEach
                    result + OwnFolder(java.util.UUID.randomUUID().toString(), clean, files = files)
                }
            }
            result
        }
        notice = "${paths.size} ${if (paths.size == 1) "video" else "videos"} added to Custom folders."
    }

    /** Creates an empty own folder (inside [parentId] when given) and returns its id, or null when the name is not usable. */
    fun createOwnFolder(name: String, parentId: String? = null): String? {
        val clean = cleanFolderName(name) ?: return null
        val id = java.util.UUID.randomUUID().toString()
        editOwn { it + OwnFolder(id, clean, parentId = parentId) }
        // A folder made again with an earlier name gets back the cover it had.
        viewModelScope.launch { restoreCover(savedCoverKey("own", clean), ownFolderKey(id)) }
        return id
    }

    fun renameOwnFolder(id: String, name: String) {
        val clean = cleanFolderName(name) ?: return
        editOwn { list -> list.map { if (it.id == id) it.copy(name = clean) else it } }
    }

    fun deleteOwnFolder(id: String) {
        val all = ownStore.load(namespace.value)
        val name = all.firstOrNull { it.id == id }?.name
        // Folders inside it go with it.
        all.filter { it.parentId == id }.forEach { deleteOwnFolder(it.id) }
        editOwn { list -> list.filterNot { it.id == id } }
        if (openOwnFolderId == id) openOwnFolderId = all.firstOrNull { it.id == id }?.parentId
        // The cover is kept, so a folder made again with this name gets it back.
        if (name != null) viewModelScope.launch { keepCover(ownFolderKey(id), savedCoverKey("own", name)) }
    }

    /** Replaces what an own folder shows. Folders include everything inside them. */
    fun setOwnFolderMembers(id: String, folders: Collection<String>, files: Collection<String>) {
        val cleanFolders = folders.map { it.trim('/') }.distinct().sorted().take(MAX_OWN_FOLDERS)
        // A file inside a chosen folder is already shown.
        val cleanFiles = files.map { it.trim('/') }.filter { it.isNotEmpty() }.distinct()
            .filterNot { file -> cleanFolders.any { dir -> dir.isEmpty() || file.startsWith("$dir/") } }
            .sorted().take(MAX_OWN_FILES)
        if (folders.size > MAX_OWN_FOLDERS || files.size > MAX_OWN_FILES) {
            notice = "An own folder can hold up to $MAX_OWN_FOLDERS folders and $MAX_OWN_FILES single files; the rest were left out."
        }
        editOwn { list -> list.map { if (it.id == id) it.copy(folders = cleanFolders, files = cleanFiles) else it } }
        coverRevision++
    }

    fun ownFolderKey(id: String) = "own:$id"

    // Whether an own folder shows its single videos grouped into virtual folders (on by default) or one by one.
    private val ownGroupingPrefs = app.getSharedPreferences("own_folder_grouping", android.content.Context.MODE_PRIVATE)
    private val ownGroupingChanged = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    fun ownFolderGroupsFiles(id: String): Boolean = ownGroupingChanged[id] ?: ownGroupingPrefs.getBoolean(id, true)
    fun setOwnFolderGroupsFiles(id: String, grouped: Boolean) {
        ownGroupingChanged[id] = grouped
        ownGroupingPrefs.edit().putBoolean(id, grouped).apply()
    }

    fun ownFolderScope(folder: OwnFolder) = LibraryScope(namespace.value, members = folder.members(), sort = videoSort.name, descending = sortDescending)

    fun ownFolderCount(folder: OwnFolder): Flow<Int> = stateDao.count(LibraryQueries.count(ownFolderScope(folder)))

    suspend fun ownFolderCover(folder: OwnFolder, key: String): File? = artwork.cover(ownFolderKey(folder.id), key, ownFolderScope(folder))

    /** Count of indexed videos in a drive folder (with sub-folders). */
    fun driveFolderCount(path: String): Flow<Int> = stateDao.count(LibraryQueries.count(LibraryScope(namespace.value, directory = path, subtree = true)))

    /** Indexed videos for the single files of an own folder. */
    suspend fun ownFolderFiles(folder: OwnFolder): List<VideoEntity> = indexedVideos(folder.files).values.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }

    /** Opens a drive folder of an own folder as a list of its videos (with sub-folders). */
    fun openDriveFolder(path: String) {
        groupFilter.value = null
        query.value = ""
        matchFolderFilter.value = MatchFolderEntity(
            id = 0,
            connectionKey = namespace.value,
            name = originalDisplayName(path),
            term = "",
            sourcePath = path,
            includeSubfolders = true,
        )
    }

    /** Drive contents for the pickers: folders, and files when [withFiles] (hidden and system names left out). */
    suspend fun driveEntries(path: String, withFiles: Boolean): List<SmbEntry> = folderCollections.entries(path, withFiles)
        .filterNot { com.deepak.videolibrary.files.ExplorerHiding.shouldHide(listOf(path, it.name).filter(String::isNotBlank).joinToString("/"), it.name, hideSystemFolders, emptySet()) }

    /**
     * Custom folders from the drive picker. Each chosen folder shows its sub-folders as separate cards (each with
     * everything inside it); a folder without sub-folders is one card, and videos lying directly in a chosen folder
     * get a card of their own. [remove] are custom folders the user unticked; nothing on the drive changes.
     */
    fun applyDriveSelection(chosen: Set<String>, remove: List<MatchFolderEntity>, onDone: () -> Unit) {
        organizerError = null
        viewModelScope.launch {
            try {
                val roots = chosen.map { it.trim('/') }.filter { path -> chosen.none { other -> other != path && isWithinFolder(path, other.trim('/')) } }
                val recursive = LinkedHashSet<String>()
                val direct = LinkedHashSet<String>()
                for (root in roots) {
                    val children = folderCollections.children(root)
                        .filterNot { com.deepak.videolibrary.files.ExplorerHiding.isSystemName(it.name) }
                        .map { listOf(root, it.name).filter(String::isNotBlank).joinToString("/") }
                    if (children.isEmpty()) {
                        recursive += root
                    } else {
                        recursive += children
                        val ownVideos = withContext(Dispatchers.IO) {
                            stateDao.count(LibraryQueries.count(LibraryScope(namespace.value, sourcePath = root, includeSubfolders = false))).first()
                        }
                        if (ownVideos > 0) direct += root
                    }
                }
                remove.forEach { folder ->
                    stateDao.deleteMatch(folder.id)
                    keepCover(com.deepak.videolibrary.artwork.ArtworkKeys.matchFolder(folder.id), savedCoverKey("match", folder.name))
                }
                if (recursive.isNotEmpty()) folderCollections.add(recursive, true, namespace.value)
                if (direct.isNotEmpty()) folderCollections.add(direct, false, namespace.value)
                restoreSavedMatchCovers()
                onDone()
                // Only scan when a chosen folder has nothing indexed yet. A refresh rewrites the library while the
                // new folder is being browsed, which made its page jump about for a few seconds.
                val unindexed = withContext(Dispatchers.IO) {
                    roots.any { root -> stateDao.count(LibraryQueries.count(LibraryScope(namespace.value, sourcePath = root, includeSubfolders = true))).first() == 0 }
                }
                if (unindexed) {
                    ScanWorker.enqueue(getApplication(), ScanMode.REFRESH, afterCurrent = true, libraryId = services.libraryId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                organizerError = "Could not update custom folders. Check the connection and try again."
            }
        }
    }

    // ---- Auto grouping: how file names become folders, separately for Virtual and Custom folders ----
    var virtualGrouping by mutableStateOf(com.deepak.videolibrary.scan.GroupingPrefs.load(app, com.deepak.videolibrary.scan.GroupingPrefs.VIRTUAL))
        private set
    var customGrouping by mutableStateOf(com.deepak.videolibrary.scan.GroupingPrefs.load(app, com.deepak.videolibrary.scan.GroupingPrefs.CUSTOM))
        private set

    fun updateVirtualGrouping(rules: com.deepak.videolibrary.scan.GroupingSwitches) {
        virtualGrouping = rules
        com.deepak.videolibrary.scan.GroupingPrefs.save(getApplication(), com.deepak.videolibrary.scan.GroupingPrefs.VIRTUAL, rules)
        coverRevision++
    }

    fun updateCustomGrouping(rules: com.deepak.videolibrary.scan.GroupingSwitches) {
        customGrouping = rules
        com.deepak.videolibrary.scan.GroupingPrefs.save(getApplication(), com.deepak.videolibrary.scan.GroupingPrefs.CUSTOM, rules)
        coverRevision++
    }

    // ---- Virtual folders: folders you make and name, filled with chosen drive folders; the subfolders are generated ----
    private val virtualStore get() = services.virtualSets
    private val virtualRevision = MutableStateFlow(0)
    val virtualSets: StateFlow<List<VirtualSet>> = combine(namespace, virtualRevision) { key, _ ->
        withContext(Dispatchers.IO) { virtualStore.load(key) }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }.state(emptyList())

    /** The Virtual folder whose page is open, or null for the list. */
    var openVirtualSetId by mutableStateOf<String?>(null)

    /** Where each Virtual folder page was (open subfolder, scroll), so coming back from the player returns there. */
    val virtualOpenGroup = HashMap<String, String>()
    val virtualScroll = HashMap<String, Pair<Int, Int>>()

    private fun editVirtual(change: (List<VirtualSet>) -> List<VirtualSet>) {
        val key = namespace.value
        virtualStore.save(key, change(virtualStore.load(key)))
        virtualRevision.value++
    }

    /** Creates an empty Virtual folder (inside [parentId] when given) and returns its id, or null when the name is not usable. */
    fun createVirtualSet(name: String, parentId: String? = null): String? {
        val clean = cleanFolderName(name) ?: return null
        val id = java.util.UUID.randomUUID().toString()
        editVirtual { it + VirtualSet(id, clean, parentId = parentId) }
        // A folder made again with an earlier name gets back its cover and its subfolders' covers.
        viewModelScope.launch { restoreCover(savedCoverKey("vset", clean), virtualSetKey(id)) }
        return id
    }

    fun renameVirtualSet(id: String, name: String) {
        val clean = cleanFolderName(name) ?: return
        editVirtual { list -> list.map { if (it.id == id) it.copy(name = clean) else it } }
    }

    /** Removes the folder from the app only. Nothing on the drive is touched. */
    fun deleteVirtualSet(id: String) {
        val all = virtualStore.load(namespace.value)
        val name = all.firstOrNull { it.id == id }?.name
        // Folders inside it go with it (their covers are kept the same way).
        all.filter { it.parentId == id }.forEach { deleteVirtualSet(it.id) }
        editVirtual { list -> list.filterNot { it.id == id } }
        if (openVirtualSetId == id) openVirtualSetId = all.firstOrNull { it.id == id }?.parentId
        // Covers are kept, so a folder made again with this name gets them back.
        if (name != null) viewModelScope.launch {
            keepCover(virtualSetKey(id), savedCoverKey("vset", name))
            coverRevision++
        }
    }

    /**
     * Replaces the drive folders and single videos a Virtual folder reads. Anything not indexed yet is scanned
     * straight away; the subfolders are generated from what the scan finds.
     */
    fun setVirtualSetMembers(id: String, folders: Collection<String>, files: Collection<String>) {
        val clean = folders.map { it.trim('/') }.distinct().sorted()
            .let { list -> list.filter { path -> list.none { other -> other != path && (other.isEmpty() || path.startsWith("$other/")) } } }
            .take(VirtualSetStore.MAX_FOLDERS)
        // A video inside a chosen folder is already included.
        val cleanFiles = files.map { it.trim('/') }.filter { it.isNotEmpty() }.distinct()
            .filterNot { file -> clean.any { dir -> dir.isEmpty() || file.startsWith("$dir/") } }
            .sorted().take(VirtualSetStore.MAX_FILES)
        if (folders.size > VirtualSetStore.MAX_FOLDERS || files.size > VirtualSetStore.MAX_FILES) {
            notice = "A Virtual folder can read up to ${VirtualSetStore.MAX_FOLDERS} drive folders and ${VirtualSetStore.MAX_FILES} single videos; the rest were left out."
        }
        editVirtual { list -> list.map { if (it.id == id) it.copy(folders = clean, files = cleanFiles) else it } }
        coverRevision++
        scanVirtualMembersIfNeeded(clean, cleanFiles)
    }

    /** A manual refresh of the folders this Virtual folder reads (it scans the whole library like Refresh library). */
    fun rescanVirtual() {
        startScan(ScanMode.REFRESH)
        notice = "Scanning your Virtual folders…"
    }

    private fun scanVirtualMembersIfNeeded(folders: List<String>, files: List<String>) {
        if (folders.isEmpty() && files.isEmpty()) return
        viewModelScope.launch {
            val unindexed = withContext(Dispatchers.IO) {
                folders.any { root ->
                    stateDao.count(LibraryQueries.count(LibraryScope(namespace.value, sourcePath = root, includeSubfolders = true))).first() == 0
                } || (files.isNotEmpty() && indexedVideos(files).size < files.size)
            }
            if (unindexed) ScanWorker.enqueue(getApplication(), ScanMode.REFRESH, afterCurrent = true, libraryId = services.libraryId)
        }
    }

    fun renameVirtualSubfolder(setId: String, identity: String, name: String) {
        val clean = cleanFolderName(name)?.let(com.deepak.videolibrary.scan.VirtualNames::display) ?: return
        val names = com.deepak.videolibrary.scan.VirtualNames
        editVirtual { list ->
            list.map { set ->
                if (set.id != setId) {
                    set
                } else {
                    // A folder already renamed keeps its original name as the key, so rename every entry that points at it.
                    val had = set.renames.any { names.identity(it.value) == identity }
                    val next = if (had) set.renames.mapValues { (_, v) -> if (names.identity(v) == identity) clean else v } else set.renames + (identity to clean)
                    set.copy(renames = next.filterNot { (k, v) -> k == names.identity(v) })
                }
            }
        }
        // The cover chosen for the folder follows its new name.
        viewModelScope.launch {
            services.artwork.ensureLoaded()
            services.artwork.move(virtualSubKey(setId, identity), virtualSubKey(setId, names.identity(clean)))
            coverRevision++
        }
    }

    /** Takes a generated subfolder out of this Virtual folder (the videos stay in the library and in other subfolders). */
    fun hideVirtualSubfolders(setId: String, identities: Collection<String>) {
        editVirtual { list -> list.map { if (it.id == setId) it.copy(hidden = it.hidden + identities) else it } }
        coverRevision++
    }

    /** Brings back every subfolder taken out of this Virtual folder. */
    fun restoreVirtualSubfolders(setId: String) {
        editVirtual { list -> list.map { if (it.id == setId) it.copy(hidden = emptySet()) else it } }
        coverRevision++
    }

    fun virtualSetKey(id: String) = com.deepak.videolibrary.artwork.ArtworkKeys.virtualSet(id)
    fun virtualSubKey(setId: String, identity: String) = com.deepak.videolibrary.artwork.ArtworkKeys.virtualSub(setId, identity)

    private fun virtualSetScope(set: VirtualSet) =
        LibraryScope(namespace.value, members = set.members(), sort = "NAME")

    /** Every indexed video inside the drive folders of [set], kept up to date as scans find more. */
    fun virtualSetVideos(set: VirtualSet): Flow<List<VideoEntity>> =
        if (set.isEmpty) flowOf(emptyList()) else stateDao.videosFlow(LibraryQueries.videos(virtualSetScope(set)))

    // The last page shown for each Virtual folder, so reopening it is instant (refreshed in the background).
    private val virtualContentCache = HashMap<String, Pair<VirtualSet, VirtualContent>>()
    fun cachedVirtualContent(set: VirtualSet): VirtualContent =
        virtualContentCache[set.id]?.takeIf { it.first == set }?.second ?: VirtualContent(emptyList(), emptyList(), loaded = false)
    fun rememberVirtualContent(set: VirtualSet, content: VirtualContent) { virtualContentCache[set.id] = set to content }

    fun virtualSetCount(set: VirtualSet): Flow<Int> =
        if (set.isEmpty) flowOf(0) else stateDao.count(LibraryQueries.count(virtualSetScope(set)))

    suspend fun virtualSetCover(set: VirtualSet, key: String): File? =
        artwork.cover(virtualSetKey(set.id), key, virtualSetScope(set))

    suspend fun virtualSubCover(setId: String, identity: String, videos: List<VideoEntity>, key: String): File? =
        artwork.cover(
            virtualSubKey(setId, identity), key,
            LibraryScope(namespace.value, members = FolderMembers(emptyList(), videos.map { it.relativePath }.take(COVER_SAMPLE)), sort = "NAME"),
        )

    /** Cuts or copies the videos of a generated subfolder; Paste then asks for a destination on the drive. */
    fun stageVideos(videos: List<VideoEntity>, cut: Boolean) {
        clipboardBatch = videos.map { Triple(it.relativePath, false, cut) }
        explorerClipboard = clipboardBatch.firstOrNull()
        notice = "${videos.size} videos ${if (cut) "cut" else "copied"}. Choose Paste to select a destination."
    }

    /** Saves every video of a Virtual folder to this device, in a folder of its name. */
    fun downloadVirtualSet(set: VirtualSet) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { stateDao.videoList(LibraryQueries.videos(virtualSetScope(set))) }
            queueDownloads(
                rows.map { downloadItem(it.relativePath, com.deepak.videolibrary.downloads.DownloadPaths.cleanFolder(set.name), it.size) },
                set.name,
            )
        }
    }

    /** Saves every video of the chosen subfolders (or of the whole Virtual folder) to this device, in a folder of its name. */
    fun downloadVirtualGroup(title: String, videos: List<VideoEntity>) {
        viewModelScope.launch {
            queueDownloads(
                videos.map { downloadItem(it.relativePath, com.deepak.videolibrary.downloads.DownloadPaths.cleanFolder(title), it.size) },
                title,
            )
        }
    }

    // ---- Display names of original folders (only in this app; the drive keeps its names) ----
    private val folderNames = FolderNameStore(app)

    /** Names of the three Folders tabs (Custom, Virtual, Original); the user can rename them by holding the tab. */
    fun sectionName(index: Int): String = folderNames.get(namespace.value, SECTION_KEYS[index]) ?: SECTION_DEFAULTS[index]

    fun renameSection(index: Int, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() && it != SECTION_DEFAULTS[index] }
        folderNames.set(namespace.value, SECTION_KEYS[index], clean)
    }

    fun originalDisplayName(path: String): String = folderNames.get(namespace.value, path) ?: originalFolderLabel(path)

    /** Sets (or with a blank name, removes) the name shown for an original folder. */
    fun renameOriginalDisplay(path: String, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() && it.length <= 100 }
        folderNames.set(namespace.value, path, clean)
    }

    // ---- thumbnails ----

    val thumbsDone: StateFlow<Int> = dao.observeThumbCount("DONE").state(0)
    val thumbsPending: StateFlow<Int> = dao.observeThumbCount("PENDING").state(0)
    val thumbsFailed: StateFlow<Int> = dao.observeThumbCount("FAILED").state(0)

    val thumbsActive: StateFlow<Boolean> =
        WorkManager
            .getInstance(app)
            .getWorkInfosForUniqueWorkFlow(ThumbnailWorker.name(services.libraryId))
            .map { infos ->
                infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            }.state(false)

    private val artwork = com.deepak.videolibrary.thumbs.ArtworkController(services)
    var coverRevision: Int
        get() = artwork.revision
        private set(value) { artwork.revision = value }
    // ---- Custom thumbnails ----
    init {
        // Load the custom-thumbnail index off the main thread. Choices of videos that left the library are kept
        // (and given back if the same file returns); only ones that can never come back are removed.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { com.deepak.videolibrary.thumbs.KeptArtwork.reconcile(getApplication(), services) }
        }
    }

    /** The SMB connection details, for components that open their own short-lived connection. */
    fun smbProfile() = settingsStore.toProfile(settings)

    fun hasCustomThumbnail(video: VideoEntity) = com.deepak.videolibrary.thumbs.CustomThumbs.has(services.artworkContext, video.id)

    /** Saves [frame] as the video's custom thumbnail and refreshes thumbnails and folder covers. */
    suspend fun saveCustomThumbnail(video: VideoEntity, frame: android.graphics.Bitmap) {
        withContext(Dispatchers.IO) { com.deepak.videolibrary.thumbs.CustomThumbs.save(services.artworkContext, video.id, frame) }
        artwork.invalidate()
        notice = "Thumbnail updated."
    }

    /** Removes the custom thumbnail so the automatic one is used again. */
    fun resetCustomThumbnail(video: VideoEntity) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                com.deepak.videolibrary.thumbs.CustomThumbs.reset(services.artworkContext, video.id)
            }
            if (removed) {
                artwork.invalidate()
                notice = "Thumbnail reset to automatic."
            }
        }
    }

    // ---- Artwork management (optional covers, performer portraits, overrides) ----
    val artworkSettings: kotlinx.coroutines.flow.StateFlow<com.deepak.videolibrary.artwork.ArtworkSettings> get() = services.artwork.settings
    val artworkRevision: kotlinx.coroutines.flow.StateFlow<Int> get() = services.artwork.revision
    /** Bumps when a provider key is saved or removed, so settings screens refresh their "key saved" state. */
    val providerKeyRevision = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            services.artwork.ensureLoaded()
            // Rebuild folder covers whenever settings or stored artwork change.
            // Only folders whose artwork changed get a new cover; the rest keep theirs (no flashing during lookups).
            services.artwork.revision.collect { artwork.refresh() }
        }
    }

    /** The picture to show for a video under the current artwork settings (null = neutral placeholder). */
    val metadataSettings: StateFlow<com.deepak.videolibrary.metadata.SceneMetadataSettings>
        get() = services.sceneMetadataPreferences.settings

    init {
        viewModelScope.launch { services.sceneMetadataPreferences.settings.collect { artwork.refresh() } }
        viewModelScope.launch { services.scenePosters.revision.collect { artwork.refresh() } }
    }

    val metadataPosterRevision: StateFlow<Int> get() = services.scenePosters.revision

    fun videoArtwork(video: VideoEntity): File? {
        val existing = services.artwork.videoFile(video)
        val options = services.sceneMetadataPreferences.settings.value
        if (!options.enabled || options.posterMode == com.deepak.videolibrary.metadata.PosterMode.DISABLED ||
            !options.videoPosters) return existing
        // Hand-picked video images or frames always override automatic scene posters.
        if (com.deepak.videolibrary.thumbs.CustomThumbs.has(services.artworkContext, video.id) ||
            services.artwork.row(com.deepak.videolibrary.artwork.ArtworkKeys.video(video.id))?.manual == true
        ) return existing
        return services.scenePosters.fileFor(video) ?: existing
    }

    fun updateFolderCoverChoice(
        section: String,
        value: com.deepak.videolibrary.metadata.FolderCoverChoice,
    ) {
        services.sceneMetadataPreferences.update { current ->
            when (section) {
                "original" -> current.copy(originalFolderCover = value)
                "custom" -> current.copy(customFolderCover = value)
                "virtual" -> current.copy(virtualFolderCover = value)
                "generated" -> current.copy(generatedFolderCover = value)
                else -> current
            }
        }
        artwork.refresh()
    }

    fun updateArtwork(change: (com.deepak.videolibrary.artwork.ArtworkSettings) -> com.deepak.videolibrary.artwork.ArtworkSettings) {
        services.artwork.preferences.update(change)
        services.artwork.settingsChanged()
    }

    fun providerHasKey(providerId: String) = services.providerKeys.has(providerId)

    /** What the last background portrait lookup did, for Settings. */
    fun artworkBackgroundNote(): String? = services.artwork.preferences.lastBackgroundNote

    fun saveProviderKey(providerId: String, key: String): Boolean {
        val saved = services.providerKeys.save(providerId, key)
        providerKeyRevision.value++
        if (!saved) notice = "Could not save that key securely. Check it has no spaces and try again."
        return saved
    }

    fun clearProviderKey(providerId: String) {
        services.providerKeys.clear(providerId)
        providerKeyRevision.value++
    }

    /** The search term for this item, following the naming switches in Settings > Artwork. */
    fun artworkSearchTerm(key: String, label: String) = artworkSettings.value.searchTerm(com.deepak.videolibrary.artwork.ArtworkKeys.scopeOf(key), label)

    suspend fun searchPerformers(query: String, page: Int = 1): com.deepak.videolibrary.artwork.online.SearchOutcome {
        val settings = artworkSettings.value
        if (!com.deepak.videolibrary.artwork.online.NetworkPolicy.allowed(getApplication(), settings)) {
            return com.deepak.videolibrary.artwork.online.SearchOutcome.Failed(com.deepak.videolibrary.artwork.online.ProviderFailure.BLOCKED_BY_SETTINGS)
        }
        return services.performerSearch.search(query, settings, page)
    }

    /** Downloads a candidate picture (bounded, HTTPS only). Throws ProviderException with a readable reason. */
    suspend fun downloadPortrait(url: String, maxWidth: Int = 1280): android.graphics.Bitmap {
        val settings = artworkSettings.value
        if (!com.deepak.videolibrary.artwork.online.NetworkPolicy.allowed(getApplication(), settings)) {
            throw com.deepak.videolibrary.artwork.online.ProviderException(com.deepak.videolibrary.artwork.online.ProviderFailure.BLOCKED_BY_SETTINGS)
        }
        return com.deepak.videolibrary.artwork.online.ArtworkHttp.downloadImage(
            url, maxWidth, com.deepak.videolibrary.artwork.online.EndpointPolicy.hostsOf(settings.customEndpoints.values),
        )
    }

    /**
     * A picture chosen on a web page (Google Images tab): an https address, or a picture embedded in the page
     * (data:image/...). Bounded in size; throws ProviderException with a readable reason.
     */
    suspend fun pictureFromWeb(address: String): android.graphics.Bitmap {
        val text = address.trim()
        if (text.startsWith("data:image/", ignoreCase = true)) {
            val encoded = text.substringAfter("base64,", "")
            if (encoded.isEmpty() || encoded.length > 12_000_000) {
                throw com.deepak.videolibrary.artwork.online.ProviderException(com.deepak.videolibrary.artwork.online.ProviderFailure.BAD_RESPONSE)
            }
            return withContext(Dispatchers.Default) {
                val bytes = try {
                    android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    throw com.deepak.videolibrary.artwork.online.ProviderException(com.deepak.videolibrary.artwork.online.ProviderFailure.BAD_RESPONSE, e)
                }
                com.deepak.videolibrary.artwork.online.ArtworkHttp.decode(bytes, 1280)
                    ?: throw com.deepak.videolibrary.artwork.online.ProviderException(com.deepak.videolibrary.artwork.online.ProviderFailure.BAD_RESPONSE)
            }
        }
        return downloadPortrait(text)
    }

    /**
     * The bytes of a picture from the web (an https address or an embedded data: picture), so an animated GIF can
     * be kept moving. Throws ProviderException when it can't be used.
     */
    suspend fun pictureBytesFromWeb(address: String): ByteArray {
        val text = address.trim()
        val settings = artworkSettings.value
        if (!com.deepak.videolibrary.artwork.online.NetworkPolicy.allowed(getApplication(), settings) && !text.startsWith("data:", ignoreCase = true)) {
            throw com.deepak.videolibrary.artwork.online.ProviderException(com.deepak.videolibrary.artwork.online.ProviderFailure.BLOCKED_BY_SETTINGS)
        }
        if (text.startsWith("data:image/", ignoreCase = true)) {
            val encoded = text.substringAfter("base64,", "")
            if (encoded.isEmpty() || encoded.length > 12_000_000) {
                throw com.deepak.videolibrary.artwork.online.ProviderException(com.deepak.videolibrary.artwork.online.ProviderFailure.BAD_RESPONSE)
            }
            return withContext(Dispatchers.Default) {
                try {
                    android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    throw com.deepak.videolibrary.artwork.online.ProviderException(com.deepak.videolibrary.artwork.online.ProviderFailure.BAD_RESPONSE, e)
                }
            }
        }
        return com.deepak.videolibrary.artwork.online.ArtworkHttp.downloadBytes(
            text,
            com.deepak.videolibrary.artwork.online.EndpointPolicy.hostsOf(settings.customEndpoints.values),
        )
    }

    /** Saves a GIF (from the phone or the web) as the moving cover of [key]. */
    suspend fun applyAnimatedCover(key: String, bytes: ByteArray) {
        services.artwork.saveAnimated(key, bytes, "gif")
        coverRevision++
        notice = "Moving cover saved."
    }

    /**
     * Makes a short moving cover from a video: eight frames spread over it, played one after another on the card.
     * For a folder, the first video in it is used. Returns false when no frames could be read.
     */
    suspend fun makeMovingCover(key: String, video: VideoEntity?): Boolean {
        val source = video ?: videosForCoverKey(key).firstOrNull() ?: return false
        val grabber = com.deepak.videolibrary.thumbs.FrameGrabber(smbProfile(), source.relativePath, source.size)
        try {
            val info = grabber.open()
            val length = info.durationMs.takeIf { it > 0 } ?: 60_000L
            val frames = (1..MOVING_FRAMES).mapNotNull { i ->
                try {
                    grabber.frame(length * i / (MOVING_FRAMES + 1), exact = false, maxWidth = 480)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            if (frames.size < 2) return false
            val bytes = withContext(Dispatchers.Default) {
                val w = frames.first().width
                val h = frames.first().height
                val strip = android.graphics.Bitmap.createBitmap(w, h * frames.size, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(strip)
                frames.forEachIndexed { i, frame ->
                    canvas.drawBitmap(frame, null, android.graphics.Rect(0, i * h, w, (i + 1) * h), null)
                    frame.recycle()
                }
                java.io.ByteArrayOutputStream().also { out ->
                    strip.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    strip.recycle()
                }.toByteArray()
            }
            services.artwork.saveAnimated(key, bytes, "strip${frames.size}.jpg")
            coverRevision++
            notice = "Moving cover saved."
            return true
        } finally {
            grabber.close()
        }
    }

    /**
     * The videos a cover belongs to, for choosing which ones a collage uses: a drive folder, a Virtual folder or
     * subfolder, a Custom folder, or a group of single videos. At most [COLLAGE_CHOICES].
     */
    suspend fun videosForCoverKey(key: String): List<VideoEntity> = withContext(Dispatchers.IO) {
        val ns = namespace.value
        val list: List<VideoEntity> = when {
            key.startsWith("original:") ->
                stateDao.videoList(LibraryQueries.videos(LibraryScope(ns, directory = key.removePrefix("original:"), subtree = true)))
            key.startsWith("vset:") -> {
                val rest = key.removePrefix("vset:")
                val set = virtualStore.load(ns).firstOrNull { rest == it.id || rest.startsWith(it.id + ":") }
                if (set == null || set.isEmpty) {
                    emptyList()
                } else {
                    val all = stateDao.videoList(LibraryQueries.videos(virtualSetScope(set)))
                    val identity = rest.removePrefix(set.id).removePrefix(":")
                    if (identity.isEmpty()) all else VirtualGroups.build(set, all, virtualGrouping).firstOrNull { it.identity == identity }?.videos.orEmpty()
                }
            }
            key.startsWith("own:") ->
                ownStore.load(ns).firstOrNull { ownFolderKey(it.id) == key }?.let { stateDao.videoList(LibraryQueries.videos(ownFolderScope(it))) }.orEmpty()
            key.startsWith("match:") ->
                stateDao.savedCollections(ns).firstOrNull { com.deepak.videolibrary.artwork.ArtworkKeys.matchFolder(it.id) == key }?.let { folder ->
                    stateDao.videoList(
                        LibraryQueries.videos(
                            LibraryScope(ns, match = folder.takeIf { it.sourcePath == null }?.term, sourcePath = folder.sourcePath, includeSubfolders = folder.includeSubfolders),
                        ),
                    )
                }.orEmpty()
            key.startsWith("virtual:") -> {
                // A group of single videos in a Custom folder (or an older Virtual folder).
                val identity = key.removePrefix("virtual:")
                val legacy = stateDao.videoList(LibraryQueries.videos(LibraryScope(ns, group = identity)))
                legacy.ifEmpty {
                    ownStore.load(ns).flatMap { own -> groupByNames(indexedVideos(own.files).values.toList(), customGrouping)[identity].orEmpty() }
                        .distinctBy { it.relativePath }
                }
            }
            else -> emptyList()
        }
        list.take(COLLAGE_CHOICES)
    }

    /** A picture of a video to put into a collage: its automatic thumbnail (never a moving cover). */
    fun stillOf(video: VideoEntity): File? =
        ThumbStore.thumbFile(services.artworkContext, video.id).takeIf { it.isFile }
            ?: com.deepak.videolibrary.thumbs.CustomThumbs.fileFor(services.artworkContext, video.id)

    /** A collage of the videos you picked, saved as this folder's cover. */
    suspend fun applyChosenCollage(key: String, videos: List<VideoEntity>): Boolean {
        val ids = videos.map { it.id }
        if (ids.isEmpty()) return false
        val file = services.covers.buildStyled(
            "chosen:$key:${ids.hashCode()}", ids, com.deepak.videolibrary.artwork.ArtworkMode.COLLAGE, ids.size.coerceIn(2, 6),
        ) ?: return false
        val bitmap = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file.path) } ?: return false
        try {
            services.artwork.saveImage(key, com.deepak.videolibrary.artwork.ArtworkMode.CUSTOM, bitmap, manual = true, focusX = 0.5f, focusY = 0.5f)
        } finally {
            bitmap.recycle()
        }
        coverRevision++
        notice = "Cover updated."
        return true
    }

    suspend fun applyCoverImage(
        key: String,
        mode: com.deepak.videolibrary.artwork.ArtworkMode,
        image: android.graphics.Bitmap,
        focusY: Float,
        candidate: com.deepak.videolibrary.artwork.online.PerformerCandidate? = null,
        focusX: Float = 0.5f,
        zoom: Float = 1f,
    ) {
        services.artwork.saveImage(
            key = key, mode = mode, image = image, manual = true,
            providerId = candidate?.providerId, performerId = candidate?.performerId, performerName = candidate?.name,
            focusX = focusX, focusY = focusY, zoom = zoom,
        )
        notice = "Cover updated."
    }

    fun setCoverMode(key: String, mode: com.deepak.videolibrary.artwork.ArtworkMode) {
        viewModelScope.launch {
            services.artwork.setMode(key, mode)
            notice = "Cover updated."
        }
    }

    /** Back to the inherited default: removes the stored cover (and a hand-picked frame for a video). */
    fun resetCover(key: String, video: VideoEntity? = null) {
        viewModelScope.launch {
            val removed = services.artwork.clear(listOf(key))
            if (video != null) {
                withContext(Dispatchers.IO) { com.deepak.videolibrary.thumbs.CustomThumbs.reset(services.artworkContext, video.id) }
                artwork.invalidate()
            }
            notice = if (removed > 0 || video != null) "Cover reset to the default." else notice
        }
    }

    /** Progress of a running artwork lookup as (done, total), or null when none is running. */
    val portraitProgress: StateFlow<Pair<Int, Int>?> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(com.deepak.videolibrary.artwork.PortraitFetchWorker.workName(services.libraryId))
        .map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let { info ->
                val total = info.progress.getInt(com.deepak.videolibrary.artwork.PortraitFetchWorker.PROGRESS_TOTAL, 0)
                info.progress.getInt(com.deepak.videolibrary.artwork.PortraitFetchWorker.PROGRESS_DONE, 0) to total
            }
        }.state(null)

    fun fetchPortraitsNow() {
        val settings = artworkSettings.value
        if (!settings.enabled || !settings.adultConsent || !(settings.portraitsOnFolders || settings.portraitsOnVideos)) {
            notice = "Turn on internet portraits first."
            return
        }
        if (!settings.portraitLookupWanted(com.deepak.videolibrary.artwork.ArtworkScope.SMB_FOLDER) &&
            !settings.portraitLookupWanted(com.deepak.videolibrary.artwork.ArtworkScope.VIRTUAL_FOLDER)
        ) {
            notice = "Nothing to look up: set a default cover to Internet performer portrait, and choose Automatic or Manual instead of Custom."
            return
        }
        com.deepak.videolibrary.artwork.PortraitFetchWorker.enqueue(getApplication(), services.libraryId, force = true)
        notice = "Looking up portraits in the background."
    }

    /** Folders with portrait suggestions waiting for the user. */
    val portraitReview: StateFlow<List<com.deepak.videolibrary.artwork.ArtworkOverride>> = services.artwork.reviewQueue.state(emptyList())

    suspend fun portraitSuggestions(key: String) = services.artwork.suggestions(key)

    /** Readable names for review entries: custom folders are stored by number, so look their names up. */
    suspend fun artworkLabels(keys: List<String>): Map<String, String> {
        val custom = withContext(Dispatchers.IO) { dao.allMatchFolders() }.associate { com.deepak.videolibrary.artwork.ArtworkKeys.matchFolder(it.id) to it.name }
        return keys.associateWith { key ->
            custom[key] ?: com.deepak.videolibrary.artwork.ArtworkKeys.labelOf(key).let { if (key.startsWith("original:")) originalFolderLabel(it) else it }
        }
    }

    /** Uses a suggestion as the folder's cover. Returns an error message, or null when it worked. */
    suspend fun choosePortrait(key: String, candidate: com.deepak.videolibrary.artwork.online.PerformerCandidate): String? {
        val url = candidate.imageUrl ?: return "This suggestion has no picture."
        return try {
            val bitmap = downloadPortrait(url)
            try {
                services.artwork.saveImage(
                    key = key, mode = com.deepak.videolibrary.artwork.ArtworkMode.PORTRAIT, image = bitmap, manual = true,
                    providerId = candidate.providerId, performerId = candidate.performerId, performerName = candidate.name, focusY = 0.25f,
                )
            } finally {
                bitmap.recycle()
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.deepak.videolibrary.artwork.online.ProviderException) {
            e.failure.message
        } catch (e: Exception) {
            "Couldn't save the picture. Check free space and try again."
        }
    }

    fun skipPortrait(key: String) {
        viewModelScope.launch { services.artwork.skipSuggestions(key) }
    }

    /** Removes every cover, crop and portrait (kept ones too); thumbnails picked for videos stay. */
    fun resetAllArtwork() {
        viewModelScope.launch {
            services.artwork.ensureLoaded()
            val n = services.artwork.clearAll()
            artwork.invalidate()
            coverRevision++
            notice = if (n > 0) "Artwork reset ($n removed)." else "No artwork to reset."
        }
    }

    /** Removes every thumbnail picked for a video, so the automatic ones show again; covers stay. */
    fun resetAllCustomThumbnails() {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { com.deepak.videolibrary.thumbs.CustomThumbs.forgetAll(services.artworkContext) }
            artwork.invalidate()
            coverRevision++
            notice = if (n > 0) "$n thumbnails reset." else "No thumbnails to reset."
        }
    }

    fun clearFetchedPortraits() {
        viewModelScope.launch {
            val n = services.artwork.clearFetchedPortraits()
            notice = if (n > 0) "Removed $n fetched portraits." else "No fetched portraits to remove."
        }
    }

    // Bulk actions on the selected videos.
    private suspend fun selectedVideos(paths: Collection<String>): List<VideoEntity> =
        withContext(Dispatchers.IO) { paths.toList().chunked(500).flatMap { dao.videosByPaths(it) } }

    /** Regenerates the automatic frames of the selected videos. */
    fun bulkGenerateFrames(paths: Collection<String>) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { paths.toList().chunked(500).sumOf { dao.resetThumbsForPaths(it) } }
            artwork.invalidate()
            startThumbnails()
            notice = "Regenerating $n frames."
        }
    }

    /** Removes every cover override of the selected videos so they follow the default again (hand-picked frames stay). */
    fun bulkApplyDefault(paths: Collection<String>) {
        viewModelScope.launch {
            val videos = selectedVideos(paths)
            val n = services.artwork.clear(videos.map { com.deepak.videolibrary.artwork.ArtworkKeys.video(it.id) })
            notice = "Default applied to ${videos.size} videos ($n overrides removed)."
        }
    }

    /** Removes overrides and hand-picked frames of the selected videos. */
    fun bulkResetOverrides(paths: Collection<String>) {
        viewModelScope.launch {
            val videos = selectedVideos(paths)
            services.artwork.clear(videos.map { com.deepak.videolibrary.artwork.ArtworkKeys.video(it.id) })
            withContext(Dispatchers.IO) { com.deepak.videolibrary.thumbs.CustomThumbs.forget(services.artworkContext, videos.map { it.id }) }
            artwork.invalidate()
            notice = "Reset ${videos.size} videos."
        }
    }

    // ---- Downloads to this device ----
    private val downloadDao = com.deepak.videolibrary.downloads.DownloadStore.dao(app)

    /** Recent downloads, newest first, with live progress. */
    val downloads: StateFlow<List<com.deepak.videolibrary.downloads.DownloadItem>> = downloadDao.observeRecent().state(emptyList())

    /** The saved server this screen works with; downloads of other servers wait until it is selected again. */
    val currentLibraryId: String get() = services.libraryId

    private fun queueDownloads(items: List<com.deepak.videolibrary.downloads.DownloadItem>, what: String) {
        if (items.isEmpty()) {
            notice = "Nothing to download in $what."
            return
        }
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) {
                val resolver = getApplication<android.app.Application>().contentResolver
                items.filter { item ->
                    val previous = downloadDao.completedAt(item.libraryId, item.sourcePath, item.targetFolder)
                    val accessible = previous?.uri?.let { saved ->
                        runCatching { resolver.openFileDescriptor(android.net.Uri.parse(saved), "r")?.use { descriptor ->
                            descriptor.statSize >= 0 && descriptor.statSize == previous?.size && (item.size <= 0 || item.size == previous?.size)
                        } == true }.getOrDefault(false)
                    } ?: false
                    !accessible
                }.also { remaining -> remaining.chunked(500).forEach { downloadDao.insertAll(it) } }
            }
            if (pending.isNotEmpty()) com.deepak.videolibrary.downloads.DownloadWorker.enqueue(getApplication())
            if (pending.isEmpty()) {
                notice = "These files are already downloaded. Existing files stay in their original location."
                return@launch
            }
            notice = "Downloading ${pending.size} ${if (pending.size == 1) "file" else "files"} to Download/${com.deepak.videolibrary.downloads.DownloadPaths.ROOT}. Tap the download button at the top to see progress."
        }
    }

    private fun downloadItem(path: String, targetFolder: String, size: Long = 0) = com.deepak.videolibrary.downloads.DownloadItem(
        libraryId = services.libraryId,
        sourcePath = path.trim('/'),
        name = path.trim('/').substringAfterLast('/'),
        targetFolder = targetFolder,
        size = size,
    )

    /** Single files or a selection of files (videos or File Explorer files). */
    fun downloadFiles(paths: Collection<String>) {
        viewModelScope.launch {
            val known = withContext(Dispatchers.IO) { paths.toList().chunked(500).flatMap { dao.videosByPaths(it) } }.associateBy { it.relativePath }
            queueDownloads(paths.distinct().map { downloadItem(it, "", known[it]?.size ?: 0L) }, "the selection")
        }
    }

    /** A whole folder on the server, with everything inside it and its sub-folders. */
    fun downloadDirectory(path: String) {
        if (settingsStore.loadProfile() == null) {
            notice = "Enter the server address and share name in Settings first."
            return
        }
        notice = "Preparing download of ${path.substringAfterLast('/')}…"
        viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    SmbConnection(settingsStore.toProfile(settings)).use { conn ->
                        conn.open()
                        val found = ArrayList<com.deepak.videolibrary.smb.SmbEntry>()
                        val paths = ArrayList<String>()
                        val pending = ArrayDeque<Pair<String, Int>>()
                        pending.add(path.trim('/') to 0)
                        while (pending.isNotEmpty() && paths.size < MAX_DOWNLOAD_FILES) {
                            val (dir, depth) = pending.removeFirst()
                            for (entry in conn.list(dir)) {
                                if (entry.isReparsePoint || entry.name.startsWith('.')) continue
                                val child = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                                if (entry.isDirectory) {
                                    if (depth < 32) pending.add(child to depth + 1)
                                } else {
                                    found += entry
                                    paths += child
                                    if (paths.size >= MAX_DOWNLOAD_FILES) break
                                }
                            }
                        }
                        paths.mapIndexed { i, p -> p to found[i].size }
                    }
                }
                queueDownloads(
                    files.map { (file, size) -> downloadItem(file, com.deepak.videolibrary.downloads.DownloadPaths.folderTarget(path, file), size) },
                    path.substringAfterLast('/').ifEmpty { "this folder" },
                )
                if (files.size >= MAX_DOWNLOAD_FILES) notice = "Only the first $MAX_DOWNLOAD_FILES files were added. Download sub-folders separately for the rest."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "Could not read the folder: ${SmbErrors.describe(e)}"
            }
        }
    }

    /** A virtual folder: its videos, saved into a device folder with the same name. */
    fun downloadVirtualFolder(name: String) {
        viewModelScope.launch {
            val rows = stateDao.videoList(LibraryQueries.videos(LibraryScope(namespace.value, group = name)))
            queueDownloads(rows.map { downloadItem(it.relativePath, com.deepak.videolibrary.downloads.DownloadPaths.cleanFolder(name), it.size) }, name)
        }
    }

    /** A custom (matching) folder: its videos, saved into a device folder with the same name. */
    fun downloadMatchingFolder(folder: MatchFolderEntity) {
        viewModelScope.launch {
            val rows = stateDao.videoList(LibraryQueries.videos(LibraryScope(namespace.value, match = folder.takeIf { it.sourcePath == null }?.term, sourcePath = folder.sourcePath, includeSubfolders = folder.includeSubfolders)))
            queueDownloads(rows.map { downloadItem(it.relativePath, com.deepak.videolibrary.downloads.DownloadPaths.cleanFolder(folder.name), it.size) }, folder.name)
        }
    }

    /** An own custom folder: every video in its folders and its single files, saved into a folder of its name. */
    fun downloadOwnFolder(folder: OwnFolder) {
        viewModelScope.launch {
            val rows = stateDao.videoList(LibraryQueries.videos(ownFolderScope(folder)))
            queueDownloads(rows.map { downloadItem(it.relativePath, com.deepak.videolibrary.downloads.DownloadPaths.cleanFolder(folder.name), it.size) }, folder.name)
        }
    }

    fun cancelDownload(id: Long) { viewModelScope.launch { downloadDao.cancel(listOf(id)) } }
    fun cancelAllDownloads() { viewModelScope.launch { downloadDao.cancelAll() } }
    fun retryDownload(id: Long) {
        viewModelScope.launch {
            if (downloadDao.retry(id) > 0) com.deepak.videolibrary.downloads.DownloadWorker.enqueue(getApplication())
        }
    }
    fun removeDownload(id: Long) { viewModelScope.launch { downloadDao.remove(id) } }
    fun clearCompletedDownloads() { viewModelScope.launch { downloadDao.clearCompleted() } }

    /** The Downloads page is open (kept for the session, so leaving and coming back returns to it). */
    var downloadsOpen by mutableStateOf(false)
    fun openDownloads() { downloadsOpen = true }

    /** Files downloaded at the same time (1 to 4). */
    var downloadParallel by mutableIntStateOf(com.deepak.videolibrary.downloads.DownloadPrefs.parallel(app))
        private set

    fun updateDownloadParallel(value: Int) {
        val clean = value.coerceIn(com.deepak.videolibrary.downloads.DownloadPrefs.MIN_PARALLEL, com.deepak.videolibrary.downloads.DownloadPrefs.MAX_PARALLEL)
        downloadParallel = clean
        com.deepak.videolibrary.downloads.DownloadPrefs.setParallel(getApplication(), clean)
    }

    /** Starts the queue again (after switching back to a server, or when it stopped on a network problem). */
    fun resumeDownloads() = com.deepak.videolibrary.downloads.DownloadWorker.enqueue(getApplication())

    fun coverKey(name: String, count: Int, done: Int, modified: Long = 0) = artwork.key(name,count,done,modified)
    fun cachedCover(key: String): File? = artwork.cached(key)
    suspend fun coverFor(group: String, key: String): File? = artwork.cover("virtual:$group",key,LibraryScope(namespace.value,group=group))
    suspend fun originalCover(path: String, key: String): File? = artwork.cover("original:$path",key,LibraryScope(namespace.value,directory=path,subtree=true))

    fun startThumbnails() {
        if (settingsStore.loadProfile() == null) {
            notice = "Enter the server address and share name in Settings first."
            return
        }
        ThumbnailWorker.enqueue(getApplication(), services.libraryId)
    }

    fun retryFailedThumbnails() {
        updateDismissedFailures { emptySet() }
        viewModelScope.launch {
            dao.resetFailedThumbs()
            startThumbnails()
        }
    }

    /** Deletes cached thumbnails and covers only. Nothing on the SMB drive is touched. */
    var artworkMaintenance by mutableStateOf(false)
        private set
    fun clearThumbnailCache() {
        if(artworkMaintenance) return
        artworkMaintenance = true
        viewModelScope.launch {
            try {
            ThumbnailWorker.cancel(getApplication())
            artwork.clear()
            notice = "Artwork cache cleared. Generate thumbnails when ready."
            } catch(e: CancellationException) { throw e } catch(_: Exception) { notice = "Could not clear artwork. Check free space and try again." }
            finally { artworkMaintenance = false }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val old = app.getSharedPreferences("player_prefs", Application.MODE_PRIVATE)
            try {
                if (!old.getBoolean("database_history_imported_v3", false)) {
                    val rows = stateDao.legacyVideos().associateBy { it.id }
                    val paths = old.all.keys
                        .filter { it.startsWith("resume_path_") }
                        .map { it.removePrefix("resume_path_") }
                        .toMutableSet()
                    paths.addAll(rows.values.filter { old.contains("resume_${it.id}") }.map { it.relativePath })
                    val byPath = rows.values.associateBy { it.relativePath }
                    stateDao.importProgress(
                        paths.map { path ->
                            PlaybackStateEntity(
                                namespace.value,
                                path,
                                old.getLong("resume_path_$path", old.getLong("resume_${byPath[path]?.id ?: 0}", 0)),
                                old.getLong("duration_path_$path", 0),
                                old.getBoolean("hide_continue_$path", false),
                                0,
                            )
                        },
                    )
                    val editor = old.edit().putBoolean("database_history_imported_v3", true)
                    old.all.keys
                        .filter {
                            it.startsWith("resume_") || it.startsWith("duration_path_") || it.startsWith("hide_continue_")
                        }.forEach { editor.remove(it) }
                    editor.apply()
                }
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (
                e: Exception,
            ) {
                AppDiagnostics.record(app, "migration", "history_import_failed_${e.javaClass.simpleName}")
                notice =
                    "Playback history import failed. Old history was retained; try restarting the app."
            } finally {
                storageReady.value = true
            }
        }
        // Pick up any thumbnails still waiting from an earlier session.
        viewModelScope.launch {
            if (settingsStore.loadProfile() != null && dao.countThumbs("PENDING") > 0) {
                ThumbnailWorker.enqueue(getApplication(), services.libraryId)
            }
        }
    }

    // ---- scanning ----

    val session: StateFlow<ScanSessionEntity?> = dao.observeLatestSession().state(null)
    val pendingDirs: StateFlow<Int> = dao.observePendingCount().state(0)

    val errors: StateFlow<List<ScanErrorEntity>> =
        session
            .flatMapLatest { s -> if (s == null) flowOf(emptyList()) else dao.observeErrors(s.id) }
            .state(emptyList())

    val scanActive: StateFlow<Boolean> =
        WorkManager
            .getInstance(app)
            .getWorkInfosForUniqueWorkFlow(ScanWorker.name(services.libraryId))
            .map { infos ->
                infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            }.state(false)

    init {
        // After every scan, videos that came back get their chosen thumbnails and covers again.
        viewModelScope.launch {
            var wasActive = false
            scanActive.collect { active ->
                if (wasActive && !active) {
                    withContext(Dispatchers.IO) { runCatching { com.deepak.videolibrary.thumbs.KeptArtwork.reconcile(getApplication(), services) } }
                    coverRevision++
                }
                wasActive = active
            }
        }
    }

    var notice by mutableStateOf<String?>(null)
        private set

    fun startScan(mode: ScanMode) {
        if (settingsStore.loadProfile() == null) {
            notice = "Enter the server address and share name in Settings first."
            return
        }
        viewModelScope.launch {
            val reason = withContext(Dispatchers.IO) {
                when {
                    mode == ScanMode.RESUME && (dao.latestResumableSession() == null || dao.pendingCountNow() == 0) -> "There is no interrupted scan to resume. Use Refresh instead."
                    mode == ScanMode.RETRY &&
                        (
                            dao.latestSession()?.let {
                                dao.errorDirPaths(it.id).isNotEmpty()
                            } != true
                        ) -> "There are no failed folders to retry."
                    else -> null
                }
            }
            notice = reason
            if (reason == null) ScanWorker.enqueue(getApplication(), mode, libraryId = services.libraryId)
        }
    }

    fun confirmEmptyLibrary(sessionId: Long) {
        viewModelScope.launch {
            val cleared=services.scanRepository.confirmEmpty(sessionId)
            if(cleared)artwork.invalidate()
            notice=if(cleared) "Missing entries removed from the index. Files on your drive were kept." else "The scan changed. Check its current results before clearing entries."
        }
    }
    fun stopScan() {
        ScanWorker.cancel(getApplication())
        ThumbnailWorker.cancel(getApplication())
    }

    var groupingRule by mutableStateOf(settingsStore.groupingRule(settings))
        private set
    fun updateGrouping(rule: com.deepak.videolibrary.scan.GroupingRule) {
        settingsStore.saveGrouping(settings, rule)
        groupingRule = rule
        startScan(ScanMode.REFRESH)
    }
    fun exportLibrary(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = LibraryBackup(services.db, services.libraryId, namespace.value).export()
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) }
                }
                notice = "Library organization and history exported. Passwords are excluded."
            } catch(e: CancellationException) { throw e } catch (_: Exception) { notice = "Could not export the library." }
        }
    }
    fun importLibrary(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val skipped = withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while(true) { val n=input.read(buffer); if(n<0)break; require(output.size()+n <= 10*1024*1024); output.write(buffer,0,n) }
                        output.toByteArray()
                    }
                    LibraryBackup(services.db, services.libraryId, namespace.value).restore(bytes.toString(Charsets.UTF_8))
                }
                artwork.invalidate()
                notice = if(skipped==0) "Library restored." else "Library restored. $skipped assignments need their videos scanned before restoring again."
            } catch(e: CancellationException) { throw e } catch (_: Exception) { notice = "Could not restore. Use a valid backup for this server, account and starting folder." }
        }
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
                val result = com.deepak.videolibrary.smb.LanDiscovery
                    .find(getApplication(), settings.host)
                discoveredHosts = result.hosts
                discoveryMessage = result.description
            } catch (e: Exception) {
                discoveredHosts = emptyList()
                discoveryMessage = "Discovery failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                discoveryRunning = false
            }
        }
    }

    // ---- Read/write SMB explorer (paths are relative to share root) ----
    private val explorer = ExplorerController(viewModelScope) { settingsStore.toProfile(settings) }
    val explorerPath get() = explorer.explorerPath
    val explorerEntries get() = explorer.explorerEntries
    val explorerBusy get() = explorer.explorerBusy
    var explorerMessage: String?
        get() = explorer.explorerMessage
        private set(value) { explorer.explorerMessage = value }
    var explorerClipboard by mutableStateOf<Triple<String, Boolean, Boolean>?>(null)
        private set
    var explorerQuery: String
        get() = explorer.explorerQuery
        set(value) { explorer.explorerQuery = value }
    val explorerHits get() = explorer.explorerHits
    val explorerSearching get() = explorer.explorerSearching
    val explorerSearchMessage get() = explorer.explorerSearchMessage
    val explorerFocusPath get() = explorer.explorerFocusPath
    fun stopExplorerSearch() = explorer.stopExplorerSearch()
    fun searchExplorer(query: String, path: String) = explorer.searchExplorer(query,path)
    fun browse(path: String = explorerPath, focus: String? = null) = explorer.browse(path,focus)

    /** Read-only display string. No credentials are included. */
    fun originalVideoLocation(relativePath: String): String {
        val profile = settingsStore.loadProfile() ?: return relativePath
        val parts = listOf(profile.host, profile.share, relativePath)
            .filter { it.isNotBlank() }
        return "\\\\" + parts.joinToString("\\") { it.trim('/', '\\').replace('/', '\\') }
    }

    fun stageExplorer(path: String, directory: Boolean, cut: Boolean) {
        clipboardBatch = emptyList()
        explorerClipboard = Triple(path, directory, cut)
        notice = if (cut) "Cut: ${path.substringAfterLast('/')}" else "Copied: ${path.substringAfterLast('/')}"
        explorerMessage = if (cut) "Ready to move: $path" else "Ready to copy: $path"
    }

    fun pasteExplorer() = pasteInto(explorerPath)

    fun renameOriginal(path: String, newName: String, directory: Boolean = false) {
        val clean = newName.trim()
        if (clean.isBlank() || clean == "." || clean == ".." || '/' in clean || '\\' in clean) {
            notice = "Invalid file name"
            return
        }
        val dest = listOf(path.substringBeforeLast('/', ""), clean).filter(String::isNotBlank).joinToString("/")
        TransferWorker.enqueue(getApplication(), "MOVE", path, dest, directory, namespace.value, services.libraryId)
        notice = "Rename queued. Existing artwork and playback history will follow the file."
    }
    fun deleteOriginal(path: String, directory: Boolean) {
        TransferWorker.enqueue(getApplication(), "DELETE", path, "", directory, namespace.value, services.libraryId)
        notice = "Delete queued."
    }

    // ---- settings ----

    var testState by mutableStateOf<TestState>(TestState.Idle)
        private set

    fun saveSettings(s: SmbSettings) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { settingsStore.save(s) }
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (
                _: Exception,
            ) {
                notice = "Could not save connection securely. Existing settings were kept. Please try again."
                return@launch
            }
            if (com.deepak.videolibrary.settings.LibraryIdentity.of(s) != services.libraryId) {
                clearClipboard(); clearSelection()
                com.deepak.videolibrary.settings.SettingsStore.revision.value++
                return@launch
            }
            settings = settingsStore.load().let { saved ->
                // Keep what was typed in the form even if the password is not persisted.
                saved.copy(password = s.password)
            }
            namespace.value = connectionKey(settings)
            originalPath = settings.rootPath.trim('/')
            notice = "Connection saved."
            if (com.deepak.videolibrary.settings.LibraryIdentity.of(settings) != services.libraryId) {
                com.deepak.videolibrary.settings.SettingsStore.revision.value++
            }
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

/** Limits for own folders, so the library query stays within SQLite's parameter limit. */
private const val MAX_OWN_FOLDERS = 100
private const val MAX_OWN_FILES = 500

/** A cover is built from a few thumbnails, so a long subfolder is sampled to stay within SQLite's parameter limit. */
private const val COVER_SAMPLE = 300

/** Frames in a moving cover made from a video, and how many videos a collage can be chosen from. */
private const val MOVING_FRAMES = 8
private const val COLLAGE_CHOICES = 300

/** A single folder download adds at most this many files, so a mistaken tap on the share root stays manageable. */
private const val MAX_DOWNLOAD_FILES = 5000

/** Stored-name keys of the Folders tabs (never valid drive paths) and their default names. */
private val SECTION_KEYS = listOf("::section:custom", "::section:virtual", "::section:original")
internal val SECTION_DEFAULTS = listOf("Custom folders", "Virtual folders", "Original folders")
