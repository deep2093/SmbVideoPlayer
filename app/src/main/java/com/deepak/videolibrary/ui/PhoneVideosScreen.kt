package com.deepak.videolibrary.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.deepak.videolibrary.data.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File

/** Marks a library item that is a file on this phone (its relativePath is the content:// address). */
internal const val PHONE_URI_PREFIX = "content://"

internal data class PhoneVideo(
    val uri: String,
    val name: String,
    val size: Long,
    val modifiedSeconds: Long,
    val durationMs: Long,
)

internal data class PhoneFolder(val key: String, val title: String, val videos: List<PhoneVideo>)

private fun phoneVideoPermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasPhonePermission(context: Context) =
    ContextCompat.checkSelfPermission(context, phoneVideoPermission()) == PackageManager.PERMISSION_GRANTED

/** Every video on the phone, grouped by the folder it lives in (Camera, Download, Movies, …). */
private fun loadPhoneFolders(context: Context): List<PhoneFolder> {
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.RELATIVE_PATH,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
    )
    val groups = LinkedHashMap<String, MutableList<PhoneVideo>>()
    val titles = HashMap<String, String>()
    context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val modified = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
        val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val path = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
        val bucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        while (c.moveToNext()) {
            val relative = c.getString(path).orEmpty().trim('/')
            val key = relative.ifEmpty { c.getString(bucket).orEmpty() }
            titles.getOrPut(key) { key.substringAfterLast('/').ifEmpty { "Videos" } }
            groups.getOrPut(key) { mutableListOf() }.add(
                PhoneVideo(
                    uri = ContentUris.withAppendedId(collection, c.getLong(id)).toString(),
                    name = c.getString(name).orEmpty(),
                    size = c.getLong(size),
                    modifiedSeconds = c.getLong(modified),
                    durationMs = c.getLong(duration),
                ),
            )
        }
    }
    return groups.map { (key, list) -> PhoneFolder(key, titles[key] ?: key, list) }
        .sortedWith(compareBy({ !it.title.equals("Camera", ignoreCase = true) }, { it.title.lowercase() }))
}


/** Phone section state that must outlive the screen: coming back from the player returns to the same folder. */
internal object PhoneUi {
    var openKey by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var folderSort by mutableStateOf("NAME")
    var videoSort by mutableStateOf("DATE")
    var descending by mutableStateOf(false)
    var cache: List<PhoneFolder>? = null
    var showHidden by mutableStateOf(false)
    /** Videos ticked in the open folder (their content:// addresses). */
    val selected = androidx.compose.runtime.mutableStateListOf<String>()
}

/**
 * Your own touches to phone folders, kept on this phone only: a name shown in SkyWing (the folder itself is never
 * renamed), a chosen cover video, and hidden folders. Keyed by the folder's path on the phone.
 */
private class PhoneFolderPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("phone_folders", Context.MODE_PRIVATE)
    var revision by mutableIntStateOf(0)
        private set

    fun name(folder: PhoneFolder): String = prefs.getString("name:" + folder.key, null) ?: folder.title
    fun rename(folder: PhoneFolder, name: String) {
        val clean = name.trim()
        prefs.edit().apply { if (clean.isEmpty() || clean == folder.title) remove("name:" + folder.key) else putString("name:" + folder.key, clean) }.apply()
        revision++
    }

    fun hidden(folder: PhoneFolder): Boolean = folder.key in prefs.getStringSet("hidden", emptySet()).orEmpty()
    fun setHidden(folder: PhoneFolder, hide: Boolean) {
        val now = prefs.getStringSet("hidden", emptySet()).orEmpty().toMutableSet()
        if (hide) now += folder.key else now -= folder.key
        prefs.edit().putStringSet("hidden", now).apply()
        revision++
    }

    /** The chosen cover video when it is still in the folder, else the newest video. */
    fun coverUri(folder: PhoneFolder): String? {
        val chosen = prefs.getString("cover:" + folder.key, null)
        return chosen?.takeIf { uri -> folder.videos.any { it.uri == uri } } ?: folder.videos.maxByOrNull { it.modifiedSeconds }?.uri
    }
    fun hasCustomCover(folder: PhoneFolder): Boolean = prefs.contains("cover:" + folder.key)
    fun setCover(folder: PhoneFolder, uri: String?) {
        prefs.edit().apply { if (uri == null) remove("cover:" + folder.key) else putString("cover:" + folder.key, uri) }.apply()
        revision++
    }
}

/** A cover/thumbnail for a phone video as a small cached file, so the shared folder cards can show it. */
private suspend fun phoneThumbFile(context: Context, uri: String): File? = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "phone-thumbs").apply { mkdirs() }
    val file = File(dir, uri.substringAfterLast('/').filter { it.isLetterOrDigit() } + ".jpg")
    if (file.length() > 0) return@withContext file
    runCatching {
        val bitmap = context.contentResolver.loadThumbnail(android.net.Uri.parse(uri), Size(640, 360), null)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file
    }.getOrNull()
}

@Composable
private fun rememberPhoneThumb(uri: String?): File? {
    val context = LocalContext.current
    val file by produceState<File?>(null, uri) { value = if (uri == null) null else phoneThumbFile(context, uri) }
    return file
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%d MB".format(bytes / (1L shl 20))
}

private fun PhoneFolder.newest() = videos.maxOfOrNull { it.modifiedSeconds } ?: 0L

/** The Phone section: videos stored on this device, listed by folder with the same cards, sorting, search and layout as Folders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneVideosScreen(
    vm: LibraryViewModel,
    foldersState: LazyGridState,
    videosState: LazyGridState,
    onPlay: (VideoEntity, List<VideoEntity>) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPhonePermission(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(PhoneUi.cache) }
    var searching by rememberSaveable { mutableStateOf(PhoneUi.query.isNotBlank()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasPhonePermission(context)
                refresh++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(granted, refresh) {
        loading = true
        val loaded = if (granted) withContext(Dispatchers.IO) { runCatching { loadPhoneFolders(context) }.getOrDefault(emptyList()) } else null
        folders = loaded
        PhoneUi.cache = loaded
        loading = false
    }
    val folderPrefs = remember(context) { PhoneFolderPrefs(context) }
    val prefsRevision = folderPrefs.revision
    var optionsFor by remember { mutableStateOf<PhoneFolder?>(null) }
    var renaming by remember { mutableStateOf<PhoneFolder?>(null) }
    var choosingCover by remember { mutableStateOf<PhoneFolder?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) { PhoneUi.selected.clear(); refresh++ }
    }
    val open = folders?.firstOrNull { it.key == PhoneUi.openKey }
    LaunchedEffect(PhoneUi.openKey) { PhoneUi.selected.clear() }
    // The saved folder disappeared (all its videos deleted): fall back to the folder list.
    LaunchedEffect(folders, open) { if (folders != null && PhoneUi.openKey != null && open == null) PhoneUi.openKey = null }
    fun closeFolder() { PhoneUi.openKey = null; PhoneUi.query = ""; searching = false }
    BackHandler(enabled = open != null) { closeFolder() }
    // Registered later, so while videos are ticked Back clears the selection first.
    BackHandler(enabled = PhoneUi.selected.isNotEmpty()) { PhoneUi.selected.clear() }
    val query = PhoneUi.query.trim()
    val descending = PhoneUi.descending

    val shownFolders = remember(folders, query, PhoneUi.folderSort, descending, PhoneUi.showHidden, prefsRevision) {
        val base = folders.orEmpty()
            .filter { PhoneUi.showHidden || !folderPrefs.hidden(it) }
            .filter { query.isEmpty() || folderPrefs.name(it).contains(query, ignoreCase = true) }
        val sorted = when (PhoneUi.folderSort) {
            "COUNT" -> base.sortedBy { it.videos.size }
            "DATE" -> base.sortedBy { it.newest() }
            "SIZE" -> base.sortedBy { f -> f.videos.sumOf { it.size } }
            else -> base.sortedBy { folderPrefs.name(it).lowercase() }
        }
        if (descending) sorted.reversed() else sorted
    }
    val shownVideos = remember(open, query, PhoneUi.videoSort, descending) {
        val base = open?.videos.orEmpty().filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        val sorted = when (PhoneUi.videoSort) {
            "NAME" -> base.sortedBy { it.name.lowercase() }
            "SIZE" -> base.sortedBy { it.size }
            "DURATION" -> base.sortedBy { it.durationMs }
            else -> base.sortedBy { it.modifiedSeconds }
        }
        // Dates and sizes read newest/biggest first unless the arrow is flipped.
        if (PhoneUi.videoSort == "NAME") (if (descending) sorted.reversed() else sorted) else (if (descending) sorted else sorted.reversed())
    }

    fun play(folder: PhoneFolder, list: List<PhoneVideo>, index: Int) {
        val queue = list.drop(index).map {
            VideoEntity(
                relativePath = it.uri,
                name = it.name,
                parentDir = folderPrefs.name(folder),
                size = it.size,
                modified = it.modifiedSeconds * 1000,
                groupKey = "phone",
                lastSeenSession = 0,
                addedAt = 0,
            )
        }
        onPlay(queue.first(), queue)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN)) {
        HubTopBar {
            if (open != null && PhoneUi.selected.isNotEmpty()) {
                val picked = shownVideos.filter { it.uri in PhoneUi.selected }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { PhoneUi.selected.clear() }) { Icon(Icons.Filled.Close, "Done") }
                    Text("${PhoneUi.selected.size} selected", style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    SelectAllToggle(
                        allSelected = shownVideos.isNotEmpty() && shownVideos.all { it.uri in PhoneUi.selected },
                        onSelectAll = { PhoneUi.selected.clear(); PhoneUi.selected.addAll(shownVideos.map { it.uri }) },
                        onDeselectAll = { PhoneUi.selected.clear() },
                    )
                    IconAction(Icons.Filled.PlayArrow, "Play selected", enabled = picked.isNotEmpty(), onClick = {
                        play(open, picked, 0)
                        PhoneUi.selected.clear()
                    })
                    IconAction(Icons.Filled.Share, "Share selected", enabled = picked.isNotEmpty(), onClick = {
                        val uris = ArrayList(picked.map { android.net.Uri.parse(it.uri) })
                        val send = android.content.Intent(if (uris.size == 1) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE)
                            .setType("video/*")
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (uris.size == 1) send.putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
                        else send.putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share videos")) }
                    })
                    // Deleting needs Android's own confirmation (Android 11 and newer).
                    if (Build.VERSION.SDK_INT >= 30) IconAction(Icons.Filled.Delete, "Delete selected", enabled = picked.isNotEmpty(), onClick = {
                        runCatching {
                            val request = MediaStore.createDeleteRequest(context.contentResolver, picked.map { android.net.Uri.parse(it.uri) })
                            deleteLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
                        }
                    })
                }
            } else SearchableTopBar(
                title = open?.let { folderPrefs.name(it) } ?: "Phone",
                searching = searching,
                onSearchingChange = { searching = it },
                query = PhoneUi.query,
                onQueryChange = { PhoneUi.query = it },
                placeholder = if (open == null) "Search phone folders" else "Search videos",
                modifier = if (LocalHubTitle.current == null) Modifier.padding(top = 8.dp) else Modifier,
                navigation = if (open != null) ({
                    IconButton(onClick = { closeFolder() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to phone folders") }
                }) else null,
            ) {
                if (granted) IconAction(Icons.Filled.Refresh, "Refresh phone videos", onClick = { refresh++ })
                CardShapeButton(vm, if (open == null) PHONE_PAGE else PHONE_VIDEOS_PAGE)
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Sort, "Sort and layout") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val options = if (open == null) {
                            listOf("NAME" to "Name", "COUNT" to "Number of videos", "DATE" to "Latest by date", "SIZE" to "Total size")
                        } else {
                            listOf("NAME" to "Name", "DATE" to "Date", "SIZE" to "Size", "DURATION" to "Length")
                        }
                        val current = if (open == null) PhoneUi.folderSort else PhoneUi.videoSort
                        options.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label + if (current == key) (if (descending) " ↓" else " ↑") else "") },
                                leadingIcon = { Icon(Icons.Filled.Sort, null) },
                                onClick = {
                                    if (current == key) PhoneUi.descending = !PhoneUi.descending
                                    else { if (open == null) PhoneUi.folderSort = key else PhoneUi.videoSort = key; PhoneUi.descending = false }
                                    menu = false
                                },
                            )
                        }
                        HorizontalDivider()
                        if (open == null) DropdownMenuItem(
                            text = { Text(if (PhoneUi.showHidden) "Don't show hidden folders" else "Show hidden folders") },
                            onClick = { PhoneUi.showHidden = !PhoneUi.showHidden; menu = false },
                        )
                        if (open != null) DropdownMenuItem(
                            text = { Text("Select videos") },
                            onClick = { shownVideos.firstOrNull()?.let { PhoneUi.selected.add(it.uri) }; menu = false },
                        )
                        val listMode = if (open == null) vm.folderListMode else vm.videoListMode
                        DropdownMenuItem(
                            text = { Text(if (listMode) "Grid view" else "List view") },
                            onClick = { if (open == null) vm.toggleFolderLayout() else vm.toggleVideoLayout(); menu = false },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val bottomPadding = if (floatingPadding(vm)) 112.dp else 24.dp
        when {
            !granted -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Allow SkyWing to see the videos on this phone to list them here, by folder.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { launcher.launch(phoneVideoPermission()) }) { Text("Allow access to videos") }
            }
            folders == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
            folders!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No videos found on this phone.") }
            else -> {
                val inFolder = open != null
                val state = if (inFolder) videosState else foldersState
                val listMode = if (inFolder) vm.videoListMode else vm.folderListMode
                val columns = libraryColumns(vm, folders = !inFolder)
                val aspect = cardAspect(vm, if (inFolder) PHONE_VIDEOS_PAGE else PHONE_PAGE)
                ScrollToTopOnChange(PhoneUi.query + PhoneUi.folderSort + PhoneUi.videoSort + PhoneUi.descending, state)
                val sortByName = if (inFolder) PhoneUi.videoSort == "NAME" else PhoneUi.folderSort == "NAME"
                FastScrollBox(
                    state = state,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    labelFor = if (sortByName) ({ index ->
                        if (inFolder) shownVideos.getOrNull(index)?.name?.let(::sectionLetter)
                        else shownFolders.getOrNull(index)?.title?.let(::sectionLetter)
                    }) else null,
                    bottomInset = if (floatingPadding(vm)) 100.dp else 16.dp,
                ) {
                    PullToRefreshBox(isRefreshing = loading, onRefresh = { refresh++ }, modifier = Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            state = state,
                            columns = GridCells.Fixed(if (listMode) 1 else columns),
                            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                            verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP),
                            contentPadding = PaddingValues(bottom = bottomPadding),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (!inFolder) {
                                if (shownFolders.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text("No matching folders.", modifier = Modifier.padding(vertical = 24.dp))
                                }
                                gridItems(shownFolders, key = { it.key }) { folder ->
                                    val cover = rememberPhoneThumb(remember(folder, prefsRevision) { folderPrefs.coverUri(folder) })
                                    val hidden = remember(folder, prefsRevision) { folderPrefs.hidden(folder) }
                                    FolderTile(
                                        title = remember(folder, prefsRevision) { folderPrefs.name(folder) },
                                        detail = "${folder.videos.size} video${if (folder.videos.size == 1) "" else "s"}" + if (hidden) " · Hidden" else "",
                                        cover = cover,
                                        aspect = aspect,
                                        listMode = listMode,
                                        onClick = { PhoneUi.openKey = folder.key; PhoneUi.query = ""; searching = false },
                                        onLongPress = { optionsFor = folder },
                                        onRename = { renaming = folder },
                                    )
                                }
                                if (shownFolders.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                                    ListTotal("${shownFolders.size} folders")
                                }
                            } else {
                                val current = open!!
                                if (shownVideos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text("No matching videos.", modifier = Modifier.padding(vertical = 24.dp))
                                }
                                itemsIndexed(shownVideos, key = { _, v -> v.uri }) { index, video ->
                                    val cover = rememberPhoneThumb(video.uri)
                                    val selecting = PhoneUi.selected.isNotEmpty()
                                    fun toggle() { if (video.uri in PhoneUi.selected) PhoneUi.selected.remove(video.uri) else PhoneUi.selected.add(video.uri) }
                                    FolderTile(
                                        title = video.name,
                                        detail = formatDuration(video.durationMs) + " · " + formatSize(video.size),
                                        cover = cover,
                                        aspect = aspect,
                                        listMode = listMode,
                                        // Hold a video to start ticking; while ticking, a tap ticks or unticks.
                                        selected = if (selecting) video.uri in PhoneUi.selected else null,
                                        onClick = { if (selecting) toggle() else play(current, shownVideos, index) },
                                        onLongPress = { toggle() },
                                    )
                                }
                                if (shownVideos.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                                    ListTotal("${shownVideos.size} videos")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    PhoneFolderDialogs(folderPrefs, optionsFor, renaming, choosingCover, { optionsFor = it }, { renaming = it }, { choosingCover = it })
}

@Composable
private fun PhoneFolderDialogs(
    prefs: PhoneFolderPrefs,
    optionsFor: PhoneFolder?,
    renaming: PhoneFolder?,
    choosingCover: PhoneFolder?,
    onOptions: (PhoneFolder?) -> Unit,
    onRename: (PhoneFolder?) -> Unit,
    onChooseCover: (PhoneFolder?) -> Unit,
) {
    optionsFor?.let { folder ->
        val hidden = prefs.hidden(folder)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { onOptions(null) },
            title = { Text(prefs.name(folder)) },
            text = {
                Column {
                    androidx.compose.material3.TextButton(onClick = { onOptions(null); onRename(folder) }) { Text("Rename") }
                    androidx.compose.material3.TextButton(onClick = { onOptions(null); onChooseCover(folder) }) { Text("Choose cover video") }
                    if (prefs.hasCustomCover(folder)) {
                        androidx.compose.material3.TextButton(onClick = { prefs.setCover(folder, null); onOptions(null) }) { Text("Use the newest video as cover") }
                    }
                    androidx.compose.material3.TextButton(onClick = { prefs.setHidden(folder, !hidden); onOptions(null) }) {
                        Text(if (hidden) "Show this folder again" else "Hide this folder")
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { onOptions(null) }) { Text("Close") } },
        )
    }
    renaming?.let { folder ->
        RenameDialog(
            "Rename folder",
            prefs.name(folder),
            "Only the name shown in SkyWing changes; the folder on your phone keeps its name (\"${folder.title}\"). Leave it empty to go back.",
            { onRename(null) },
        ) { prefs.rename(folder, it) }
    }
    choosingCover?.let { folder ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { onChooseCover(null) },
            title = { Text("Cover for ${prefs.name(folder)}") },
            text = {
                LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.heightIn(max = 380.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    gridItems(folder.videos, key = { it.uri }) { video ->
                        val thumb = rememberPhoneThumb(video.uri)
                        Box(
                            Modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { prefs.setCover(folder, video.uri); onChooseCover(null) },
                        ) { FileImage(thumb, Modifier.fillMaxSize()) }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { onChooseCover(null) }) { Text("Cancel") } },
        )
    }
}

private const val PHONE_PAGE = "phone"
private const val PHONE_VIDEOS_PAGE = "phone-videos"
