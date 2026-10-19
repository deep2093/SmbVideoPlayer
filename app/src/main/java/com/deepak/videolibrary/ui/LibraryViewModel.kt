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
import androidx.room.withTransaction
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
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.deepak.videolibrary.data.*
import com.deepak.videolibrary.files.TransferWorker
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
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
    private val stateDao = services.db.libraryState()
    var settings by mutableStateOf(settingsStore.load())
        private set
    val namespace = MutableStateFlow(connectionKey(settings))
    private val storageReady = MutableStateFlow(false)
    var foldersTab by mutableIntStateOf(1)
    val matchFolderFilter=MutableStateFlow<MatchFolderEntity?>(null)
    val matchFolderQuery=MutableStateFlow("")
    private val folderQueryFlow = MutableStateFlow("")
    private val originalQueryFlow = MutableStateFlow("")
    private val originalLocation = MutableStateFlow(settings.rootPath.trim('/'))
    private fun connectionKey(s: SmbSettings) = s.host.trim().lowercase(java.util.Locale.ROOT) + "/" + s.share.trim().lowercase(java.util.Locale.ROOT)


    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    // Grid preset preferences are independent for each library tab.
    private val layoutPrefs = app.getSharedPreferences("library_layout", Application.MODE_PRIVATE)
    var navigationPosition by mutableStateOf(layoutPrefs.getString("navigation_position","SIDEBAR") ?: "SIDEBAR")
        private set
    fun updateNavigationPosition(value:String){if(value !in setOf("SIDEBAR","FLOATING"))return;navigationPosition=value;layoutPrefs.edit().putString("navigation_position",value).apply()}
    var automaticVirtualFolders by mutableStateOf(layoutPrefs.getBoolean("virtual_enabled:${namespace.value}",false))
        private set
    var virtualSources by mutableStateOf(layoutPrefs.getStringSet("virtual_sources:${namespace.value}",emptySet())!!.toSet())
        private set
    private val virtualSourceFlow=MutableStateFlow(if(automaticVirtualFolders) virtualSources.toList() else emptyList())
    fun updateAutomaticVirtualFolders(enabled:Boolean){
        automaticVirtualFolders=enabled
        layoutPrefs.edit().putBoolean("virtual_enabled:${namespace.value}",enabled).apply()
        virtualSourceFlow.value=if(enabled) virtualSources.toList() else emptyList()
        coverRevision++
    }
    fun updateVirtualSources(paths:Set<String>){
        virtualSources=paths.toSet()
        layoutPrefs.edit().putStringSet("virtual_sources:${namespace.value}",virtualSources).apply()
        virtualSourceFlow.value=if(automaticVirtualFolders) virtualSources.toList() else emptyList()
        coverRevision++
    }
    suspend fun virtualSourceDirectories(path:String):List<SmbEntry> = withContext(Dispatchers.IO) {
        val profile=settingsStore.loadProfile() ?: error("Configure SMB in Settings first")
        SmbConnection(profile).use { it.open();it.list(path).filter { entry->entry.isDirectory }.sortedBy { entry->entry.name.lowercase() } }
    }
    private fun virtualScope(scope:LibraryScope)=scope.copy(automaticSources=virtualSourceFlow.value)
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

    private val sortChoice = MutableStateFlow(videoSort.name to sortDescending)
    private val folderSortChoice = MutableStateFlow(folderSort to folderSortDescending)
    fun selectFolderSort(sort: String) {
        folderSortDescending = if (folderSort == sort) !folderSortDescending else false
        folderSort = sort
        folderSortChoice.value = folderSort to folderSortDescending
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
        sortChoice.value = videoSort.name to sortDescending
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

    private var folderSearchValue by mutableStateOf("")
    var folderSearch: String
        get() = folderSearchValue
        set(value) { folderSearchValue=value; folderQueryFlow.value=value }
    private var originalPathValue by mutableStateOf(settings.rootPath.trim('/'))
    var originalPath: String
        get() = originalPathValue
        set(value) { originalPathValue=value; originalLocation.value=value }
    private var originalQueryValue by mutableStateOf("")
    var originalQuery: String
        get() = originalQueryValue
        set(value) { originalQueryValue=value; originalQueryFlow.value=value }

    private val unscopedVideoScope = combine(query.debounce(200), groupFilter, sortChoice, namespace,matchFolderFilter) { q,g,sort,key,rule ->
        LibraryScope(key,q,g,sort=sort.first,descending=sort.second,match=rule?.term)
    }
    private val videoScope=combine(unscopedVideoScope,virtualSourceFlow){scope,sources -> if(scope.group!=null) scope.copy(automaticSources=sources) else scope}
    private val folderScope = combine(folderQueryFlow.debounce(200),sortChoice,namespace,virtualSourceFlow) { q,sort,key,sources ->
        LibraryScope(key,q,groupMatches=true,sort=sort.first,descending=sort.second,automaticSources=sources)
    }
    private val originalScope = combine(originalLocation,originalQueryFlow.debounce(200),sortChoice,namespace) { dir,q,sort,key ->
        LibraryScope(key,q,directory=dir,subtree=q.isNotBlank(),pathMatches=true,sort=sort.first,descending=sort.second)
    }
    private fun pages(scopes: Flow<LibraryScope>) = scopes.flatMapLatest { scope ->
        Pager(PagingConfig(pageSize=60,initialLoadSize=90,enablePlaceholders=false), pagingSourceFactory={stateDao.pages(LibraryQueries.videos(scope))}).flow
    }.cachedIn(viewModelScope)
    val videoPages = pages(videoScope)
    val folderPages = pages(folderScope)
    val originalPages = pages(originalScope)
    val videoResultsCount = videoScope.flatMapLatest { stateDao.count(LibraryQueries.count(it)) }.state(0)
    val folderResultsCount = folderScope.flatMapLatest { stateDao.count(LibraryQueries.count(it)) }.state(0)
    val originalResultsCount = originalScope.flatMapLatest { stateDao.count(LibraryQueries.count(it.copy(subtree=true))) }.state(0)
    val groups = combine(folderScope,folderSortChoice) { scope,sort -> LibraryQueries.groups(scope,sort.first,sort.second) }
        .flatMapLatest { stateDao.folders(it) }.state(emptyList())
    val matchingFolders=combine(namespace,matchFolderQuery.debounce(200)){key,q->LibraryQueries.matchingFolders(key,q)}.flatMapLatest {stateDao.matchingFolders(it)}.state(emptyList())
    fun createMatchingFolder(name:String)=organize {val clean=cleanFolderName(name) ?: return@organize;stateDao.createMatch(MatchFolderEntity(connectionKey=namespace.value,name=clean,term=clean))}
    fun renameMatchingFolder(folder:MatchFolderEntity,name:String)=organize {val clean=cleanFolderName(name) ?: return@organize;stateDao.renameMatch(folder.id,clean);coverRevision++}
    fun deleteMatchingFolder(folder:MatchFolderEntity)=organize {stateDao.deleteMatch(folder.id)}
    fun hideMatchingFolder(folder:MatchFolderEntity) {viewModelScope.launch {stateDao.hide(HiddenItem(namespace.value,"MATCH",folder.id.toString(),folder.name))}}
    fun stageMatchingFolder(folder:MatchFolderEntity,cut:Boolean) {viewModelScope.launch {
        val rows=withContext(Dispatchers.IO){stateDao.videoList(LibraryQueries.videos(LibraryScope(namespace.value,match=folder.term)))}
        clipboardBatch=rows.map {Triple(it.relativePath,false,cut)};explorerClipboard=clipboardBatch.firstOrNull()
        notice="${rows.size} matching videos ready to ${if(cut) "move" else "copy"}."
    }}
    fun mergeMatchingFolder(folder:MatchFolderEntity,targetName:String)=organize {
        val target=cleanFolderName(targetName) ?: return@organize
        val rows=stateDao.videoList(LibraryQueries.videos(LibraryScope(namespace.value,match=folder.term)))
        services.db.withTransaction {dao.createFolder(CustomFolderEntity(target));rows.map {it.relativePath}.chunked(500).forEach {dao.assignPaths(it,target)}}
    }
    suspend fun matchingCover(folder:MatchFolderEntity,key:String):File? {
        coverFiles.get(key)?.let {return it}
        val ids=withContext(Dispatchers.IO){stateDao.ids(LibraryQueries.coverIds(LibraryScope(namespace.value,match=folder.term)))}
        return services.covers.buildOriginal("${namespace.value}:match:${folder.id}",ids)?.also {coverFiles.put(key,it)}
    }
    val originalDirectories = originalScope.flatMapLatest { stateDao.directories(if(it.directory==settings.rootPath.trim('/')) LibraryQueries.allDirectories(it) else LibraryQueries.directories(it)) }.state(emptyList())
    val continuing = namespace.flatMapLatest { stateDao.continuing(LibraryQueries.continuing(it)) }.state(emptyList())
    val hiddenItems = namespace.flatMapLatest { stateDao.hiddenItems(it) }.state(emptyList())
    val videoCount = dao.observeVideoCount().state(0)

    fun progressFlow(path: String) = stateDao.observeProgress(namespace.value,path)
    suspend fun resumeFor(path: String): PlaybackStateEntity? { storageReady.first { it }; return stateDao.progress(namespace.value,path) }
    fun saveProgress(path: String, position: Long, duration: Long) {
        val key=namespace.value
        viewModelScope.launch(Dispatchers.IO) { storageReady.first { it }; stateDao.saveProgress(key,path,position,duration) }
    }
    fun hideContinue(path: String, hidden: Boolean=true) {
        val key=namespace.value
        viewModelScope.launch(Dispatchers.IO) { storageReady.first { it }; stateDao.hideContinue(key,path,hidden) }
    }
    fun playFromStart(path:String,onPlay:()->Unit) { viewModelScope.launch {
        withContext(Dispatchers.IO) {storageReady.first {it};stateDao.saveProgress(namespace.value,path,0,stateDao.progress(namespace.value,path)?.durationMs ?: 0)}
        onPlay()
    } }
    fun clearResume(path: String) { viewModelScope.launch(Dispatchers.IO) {
        storageReady.first { it }; stateDao.saveProgress(namespace.value,path,0,stateDao.progress(namespace.value,path)?.durationMs ?: 0)
    } }
    fun hidePath(path: String, directory: Boolean) { viewModelScope.launch(Dispatchers.IO) {
        stateDao.hide(HiddenItem(namespace.value,if(directory) "DIRECTORY" else "FILE",path,path.substringAfterLast('/')));coverRevision++
    } }
    fun hideGroup(group: String) { viewModelScope.launch(Dispatchers.IO) { stateDao.hide(HiddenItem(namespace.value,"GROUP",group,group));coverRevision++ } }
    fun unhide(item: HiddenItem) { viewModelScope.launch(Dispatchers.IO) { stateDao.unhide(item);coverRevision++ } }
    fun unhideAll() { viewModelScope.launch(Dispatchers.IO) { stateDao.unhideAll(namespace.value);coverRevision++ } }
    private fun selectedScope(mode: String): LibraryScope = when(mode) {
        "folders" -> virtualScope(LibraryScope(namespace.value,folderSearch,groupMatches=true,sort=videoSort.name,descending=sortDescending))
        "originals" -> LibraryScope(namespace.value,originalQuery,directory=originalPath,subtree=true,pathMatches=true,sort=videoSort.name,descending=sortDescending)
        else -> LibraryScope(namespace.value,query.value,groupFilter.value,sort=videoSort.name,descending=sortDescending,match=matchFolderFilter.value?.term,automaticSources=if(groupFilter.value!=null) virtualSourceFlow.value else null)
    }
    fun playLibrary(mode: String, start: VideoEntity?=null, onPlay:(VideoEntity,List<VideoEntity>)->Unit) {
        val scope=selectedScope(mode)
        viewModelScope.launch {
            val queue=withContext(Dispatchers.IO) { stateDao.videoList(LibraryQueries.videos(scope)) }
            val first=start?.let { selected -> queue.firstOrNull { it.relativePath==selected.relativePath } } ?: queue.firstOrNull()
            if(first!=null) onPlay(first,queue) else notice="No visible videos match this search."
        }
    }
    var pasteDestination by mutableStateOf<String?>(null)
    var explorerNavigationRequest by mutableStateOf<String?>(null)
        private set
    fun openFailureFolder(path:String) { explorerNavigationRequest=path }
    fun consumeExplorerNavigation() { explorerNavigationRequest=null }
    val failedThumbnails=namespace.flatMapLatest {stateDao.thumbnailFailures(LibraryQueries.thumbnailFailures(it))}.state(emptyList())
    fun retryThumbnail(id:Long) {viewModelScope.launch {dao.setThumbStatus(id,"PENDING");startThumbnails()}}
    var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        private set
    var selectionScope by mutableStateOf("videos")
        private set
    fun toggleSelected(path:String,mode:String="videos") {selectionScope=mode;selectedPaths=if(path in selectedPaths)selectedPaths-path else selectedPaths+path}
    fun clearSelection(){selectedPaths=emptySet()}
    fun selectAll(mode:String) {viewModelScope.launch {
        selectionScope=mode
        if(mode=="explorer") {
            selectedPaths=if(explorerQuery.isNotBlank())explorerHits.filter {isPlayable(it.entry)}.map {it.path}.toSet()
                else explorerEntries.filter {isPlayable(it)}.map {listOf(explorerPath,it.name).filter(String::isNotBlank).joinToString("/")}.toSet()
            return@launch
        }
        val scope=selectedScope(mode)
        selectedPaths=withContext(Dispatchers.IO){stateDao.videoList(LibraryQueries.videos(scope)).map {it.relativePath}.toSet()}
    }}
    fun stageSelected(cut:Boolean) {
        clipboardBatch=selectedPaths.map {Triple(it,false,cut)};explorerClipboard=clipboardBatch.firstOrNull();clearSelection()
    }
    fun mergeSelected(folder:String) {
        val target=cleanFolderName(folder) ?: return
        val paths=selectedPaths.toList()
        viewModelScope.launch {
            services.db.withTransaction {dao.createFolder(CustomFolderEntity(target));paths.chunked(500).forEach {dao.assignPaths(it,target)}}
            clearSelection();notice="${paths.size} videos assigned to virtual folder '$target'."
        }
    }
    var clipboardBatch by mutableStateOf<List<Triple<String,Boolean,Boolean>>>(emptyList())
        private set
    fun stageGroup(group: String, cut: Boolean) { viewModelScope.launch {
        val rows=withContext(Dispatchers.IO) { stateDao.videoList(LibraryQueries.videos(virtualScope(LibraryScope(namespace.value,group=group)))) }
        clipboardBatch=rows.map { Triple(it.relativePath,false,cut) }
        explorerClipboard=clipboardBatch.firstOrNull()
        notice="${rows.size} videos ${if(cut) "cut" else "copied"}. Choose Paste to select a destination."
    } }
    fun deleteGroupFiles(group: String) { viewModelScope.launch(Dispatchers.IO) {
        stateDao.videoList(LibraryQueries.videos(virtualScope(LibraryScope(namespace.value,group=group)))).forEach {
            TransferWorker.enqueue(getApplication(),"DELETE",it.relativePath,"",false,namespace.value)
        }
    } }
    fun clearClipboard() { explorerClipboard=null;clipboardBatch=emptyList() }
    val transfers = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(TransferWorker.UNIQUE_NAME).state(emptyList())
    fun pasteInto(directory: String) {
        val entries=clipboardBatch.ifEmpty { listOfNotNull(explorerClipboard) }
        if(entries.isEmpty()) return
        val names=entries.map { it.first.substringAfterLast('/') }
        if(names.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size!=names.size) {
            notice="Several selected files have the same filename. Paste them individually to avoid overwriting.";return
        }
        if(entries.any { listOf(directory,it.first.substringAfterLast('/')).filter(String::isNotBlank).joinToString("/")==it.first }) {
            notice="Choose another destination folder.";return
        }
        entries.forEach { clip ->
            val dest=listOf(directory,clip.first.substringAfterLast('/')).filter(String::isNotBlank).joinToString("/")
            TransferWorker.enqueue(getApplication(),if(clip.third) "MOVE" else "COPY",clip.first,dest,clip.second,namespace.value)
        }
        notice="${entries.size} file operation(s) queued. You can keep using the app."
        clearClipboard()
    }
    fun cancelTransfer(id: java.util.UUID) { WorkManager.getInstance(getApplication()).cancelWorkById(id) }

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

    private val coverFiles=android.util.LruCache<String,File>(100)
    var coverRevision by mutableIntStateOf(0)
        private set
    fun coverKey(name:String,count:Int,done:Int,modified:Long=0)="${namespace.value}:$name:$count:$done:$modified:$coverRevision"
    fun cachedCover(key:String):File?=coverFiles.get(key)
    suspend fun coverFor(group:String,key:String):File? {
        coverFiles.get(key)?.let{return it}
        val ids=withContext(Dispatchers.IO){stateDao.ids(LibraryQueries.coverIds(virtualScope(LibraryScope(namespace.value,group=group))))}
        return services.covers.buildOriginal("${namespace.value}:virtual:$group",ids)?.also{coverFiles.put(key,it)}
    }
    suspend fun originalCover(path:String,key:String):File? {
        coverFiles.get(key)?.let{return it}
        val ids=withContext(Dispatchers.IO){stateDao.ids(LibraryQueries.coverIds(LibraryScope(namespace.value,directory=path,subtree=true)))}
        return services.covers.buildOriginal("${namespace.value}:original:$path",ids)?.also{coverFiles.put(key,it)}
    }

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
        viewModelScope.launch(Dispatchers.IO) {
            val old=app.getSharedPreferences("player_prefs",Application.MODE_PRIVATE)
            try {
                if(!old.getBoolean("database_history_imported_v3",false)) {
                    val rows=stateDao.legacyVideos().associateBy { it.id }
                    val paths=old.all.keys.filter { it.startsWith("resume_path_") }.map { it.removePrefix("resume_path_") }.toMutableSet()
                    paths.addAll(rows.values.filter { old.contains("resume_${it.id}") }.map { it.relativePath })
                    val byPath=rows.values.associateBy { it.relativePath }
                    stateDao.importProgress(paths.map { path ->
                        PlaybackStateEntity(namespace.value,path,old.getLong("resume_path_$path",old.getLong("resume_${byPath[path]?.id ?: 0}",0)),old.getLong("duration_path_$path",0),old.getBoolean("hide_continue_$path",false),0)
                    })
                    val editor=old.edit().putBoolean("database_history_imported_v3",true)
                    old.all.keys.filter {it.startsWith("resume_") || it.startsWith("duration_path_") || it.startsWith("hide_continue_")}.forEach {editor.remove(it)}
                    editor.apply()
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){AppDiagnostics.record(app,"migration","history_import_failed_${e.javaClass.simpleName}");notice="Playback history import failed. Old history was retained; try restarting the app."}
            finally { storageReady.value=true }
        }
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

    var explorerFocusPath by mutableStateOf<String?>(null)
        private set
    fun browse(path: String = explorerPath,focus:String?=null) {
        explorerFocusPath=focus
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
        val parts = listOf(profile.host, profile.share, relativePath)
            .filter { it.isNotBlank() }
        return "\\\\" + parts.joinToString("\\") { it.trim('/', '\\').replace('/', '\\') }
    }

    fun stageExplorer(path: String, directory: Boolean, cut: Boolean) {
        clipboardBatch=emptyList()
        explorerClipboard = Triple(path, directory, cut)
        notice = if(cut) "Cut: ${path.substringAfterLast('/')}" else "Copied: ${path.substringAfterLast('/')}"
        explorerMessage = if (cut) "Ready to move: $path" else "Ready to copy: $path"
    }

    fun pasteExplorer() = pasteInto(explorerPath)

    fun renameOriginal(path: String, newName: String, directory: Boolean=false) {
        val clean=newName.trim()
        if(clean.isBlank() || clean=="." || clean==".." || '/' in clean || '\\' in clean) { notice="Invalid file name"; return }
        val dest=listOf(path.substringBeforeLast('/',""),clean).filter(String::isNotBlank).joinToString("/")
        TransferWorker.enqueue(getApplication(),"MOVE",path,dest,directory,namespace.value)
        notice="Rename queued. Existing artwork and playback history will follow the file."
    }
    fun deleteOriginal(path: String, directory: Boolean) {
        TransferWorker.enqueue(getApplication(),"DELETE",path,"",directory,namespace.value)
        notice="Delete queued."
    }

    // ---- settings ----

    var testState by mutableStateOf<TestState>(TestState.Idle)
        private set

    fun saveSettings(s: SmbSettings) {
        if(connectionKey(s)!=namespace.value) {clearClipboard();clearSelection()}
        settingsStore.save(s)
        settings = settingsStore.load().let { saved ->
            // Keep what was typed in the form even if the password is not persisted.
            saved.copy(password = s.password)
        }
        namespace.value=connectionKey(settings)
        automaticVirtualFolders=layoutPrefs.getBoolean("virtual_enabled:${namespace.value}",false)
        virtualSources=layoutPrefs.getStringSet("virtual_sources:${namespace.value}",emptySet())!!.toSet()
        virtualSourceFlow.value=if(automaticVirtualFolders) virtualSources.toList() else emptyList()
        originalPath=settings.rootPath.trim('/')
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
