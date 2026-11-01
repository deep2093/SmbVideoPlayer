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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
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

private val thumbCache = object : LruCache<String, Bitmap>(80) {}

@Composable
private fun PhoneThumb(uri: String, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(thumbCache.get(uri), uri) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(android.net.Uri.parse(uri), Size(480, 270), null) }.getOrNull()
            }?.also { thumbCache.put(uri, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val shown = bitmap
        if (shown != null) {
            Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

/** The Phone section: videos stored on this device, listed under the folder they are in. */
@Composable
internal fun PhoneVideosScreen(vm: LibraryViewModel, onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPhonePermission(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var folders by remember { mutableStateOf<List<PhoneFolder>?>(null) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
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
        folders = if (granted) withContext(Dispatchers.IO) { runCatching { loadPhoneFolders(context) }.getOrDefault(emptyList()) } else null
    }
    val open = folders?.firstOrNull { it.key == openKey }
    BackHandler(enabled = open != null) { openKey = null }
    val bottomPadding = if (floatingPadding(vm)) 112.dp else 24.dp

    fun play(folder: PhoneFolder, index: Int) {
        val queue = folder.videos.drop(index).map {
            VideoEntity(
                relativePath = it.uri,
                name = it.name,
                parentDir = folder.title,
                size = it.size,
                modified = it.modifiedSeconds * 1000,
                groupKey = "phone",
                lastSeenSession = 0,
                addedAt = 0,
            )
        }
        onPlay(queue.first(), queue)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (open != null) {
                IconButton(onClick = { openKey = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to phone folders") }
            }
            Text(
                open?.title ?: "Phone",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (granted) IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
            LocalTopBarEnd.current()
        }
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
            open == null -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(folders!!, key = { it.key }) { folder ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { openKey = folder.key }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PhoneThumb(folder.videos.first().uri, Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(folder.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${folder.videos.size} video${if (folder.videos.size == 1) "" else "s"} · ${folder.key}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> {
              val current = open!!
              LazyVerticalGrid(
                GridCells.Adaptive(160.dp),
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                androidx.compose.foundation.lazy.grid.itemsIndexed(current.videos, key = { _, v -> v.uri }) { index, video ->
                    Column(Modifier.clickable { play(current, index) }) {
                        Box {
                            PhoneThumb(video.uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
                            Text(
                                formatDuration(video.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                    .background(androidx.compose.ui.graphics.Color(0xAA000000), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(video.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(formatSize(video.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            }
        }
    }
}
