package com.skywing.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.skywing.desktop.artwork.ArtworkHttp
import com.skywing.desktop.artwork.ArtworkKeys
import com.skywing.desktop.artwork.ArtworkMode
import com.skywing.desktop.artwork.ArtworkRepository
import com.skywing.desktop.artwork.AutoMatch
import com.skywing.desktop.artwork.CoverCrop
import com.skywing.desktop.artwork.EndpointPolicy
import com.skywing.desktop.artwork.FlipBook
import com.skywing.desktop.artwork.FolderCovers
import com.skywing.desktop.artwork.NameCleaner
import com.skywing.desktop.artwork.PerformerCandidate
import com.skywing.desktop.artwork.PhotoCollage
import com.skywing.desktop.artwork.Pictures
import com.skywing.desktop.artwork.ProviderException
import com.skywing.desktop.artwork.ProviderFailure
import com.skywing.desktop.artwork.StashDb
import com.skywing.desktop.playback.ThumbnailExtractor
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/** What Change cover edits: a video or a folder, by the same key the cards use for covers. */
internal data class CoverTarget(
    val key: String,
    val title: String,
    /** The video itself, when the target is a video. */
    val video: WindowsMediaEntry?,
    /** The videos of a folder (for frames and collages); a video's own entry for a video. */
    val videos: List<WindowsMediaEntry>,
) {
    val isFolder: Boolean get() = !key.startsWith("video:")
}

internal fun videoCoverTarget(entry: WindowsMediaEntry) = CoverTarget(ArtworkKeys.video(entry.path), entry.file.nameWithoutExtension, entry, listOf(entry))

private enum class CoverTab(val label: String) {
    AUTO("Auto frame"),
    COLLAGE("Folder collage"),
    PHOTOS("Photo collage"),
    INTERNET("StashDB"),
    GOOGLE("Google Images"),
    IMAGE("From this PC"),
    RESET("Reset"),
}

/** The Google Images results page for [query]. */
internal fun googleImagesUrl(query: String): String = "https://www.google.com/search?tbm=isch&q=" + URLEncoder.encode(query.trim(), "UTF-8")

/** A picture handed to the dialog by pasting or dropping: its bytes, or an address to download. */
private sealed class Incoming {
    class Bytes(val bytes: ByteArray) : Incoming()
    class Address(val url: String) : Incoming()
}

/** HTML flavors browsers put on a dragged or copied picture. */
private val htmlFlavors = listOf(DataFlavor.allHtmlFlavor, DataFlavor.fragmentHtmlFlavor, DataFlavor.selectionHtmlFlavor)
private val uriListFlavor = runCatching { DataFlavor("text/uri-list;class=java.lang.String") }.getOrNull()
private val imageSource = Regex("""<img[^>]*?\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** A picture address from text: a web or data address; a Google result link gives the picture it points to. */
internal fun pictureAddress(text: String?): String? {
    val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
    val address = line.replace("&amp;", "&")
    if (address.startsWith("data:image/", ignoreCase = true)) return address
    if (!address.startsWith("https://", ignoreCase = true) && !address.startsWith("http://", ignoreCase = true)) return null
    // "google.com/imgres?imgurl=<picture>&imgrefurl=<page>": use the picture itself.
    val query = runCatching { URI(address).rawQuery }.getOrNull().orEmpty()
    query.split('&').firstOrNull { it.startsWith("imgurl=") }?.let { param ->
        return runCatching { java.net.URLDecoder.decode(param.removePrefix("imgurl="), "UTF-8") }.getOrNull()
    }
    return address
}

/** The first picture address in a piece of HTML (the <img> a browser hands over when a picture is dragged). */
internal fun pictureInHtml(html: String?): String? = html?.let { imageSource.find(it)?.groupValues?.get(1) }?.let(::pictureAddress)

/**
 * Reads a picture handed over by pasting or dropping: a picture file, the picture itself, or its address (from the
 * HTML, link list or plain text browsers provide). Null when there is no picture in it.
 */
private fun readIncoming(t: Transferable): Incoming? {
    fun <T> read(flavor: DataFlavor?, cast: (Any?) -> T?): T? =
        if (flavor != null && t.isDataFlavorSupported(flavor)) runCatching { cast(t.getTransferData(flavor)) }.getOrNull() else null
    read(DataFlavor.javaFileListFlavor) { it as? List<*> }?.firstOrNull()?.let { it as? File }?.let { file ->
        if (file.isFile && file.length() in 1..ArtworkRepository.MAX_ANIMATED_BYTES) return Incoming.Bytes(file.readBytes())
    }
    htmlFlavors.firstNotNullOfOrNull { flavor -> pictureInHtml(read(flavor) { it as? String }) }?.let { return Incoming.Address(it) }
    pictureAddress(read(uriListFlavor) { it as? String })?.let { return Incoming.Address(it) }
    pictureAddress(read(DataFlavor.stringFlavor) { it as? String })?.let { return Incoming.Address(it) }
    read(DataFlavor.imageFlavor) { it as? java.awt.Image }?.let { image ->
        val buffered = BufferedImage(image.getWidth(null).coerceAtLeast(1), image.getHeight(null).coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        val out = ByteArrayOutputStream()
        ImageIO.write(buffered, "png", out)
        return Incoming.Bytes(out.toByteArray())
    }
    return null
}

/** Preview pictures of StashDB results, downloaded once. */
private object RemotePictures {
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 120
    }
    private val gate = Semaphore(4)

    suspend fun load(url: String, trusted: Set<String>): ImageBitmap? {
        synchronized(memory) { memory[url] }?.let { return it }
        return gate.withPermit {
            runCatching {
                val bytes = ArtworkHttp.downloadBytes(url, trusted)
                withContext(Dispatchers.Default) { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }
            }.getOrNull()?.also { synchronized(memory) { memory[url] = it } }
        }
    }
}

/**
 * Change cover, as on Android: tabbed sources (auto frame, folder collage, photo collage, StashDB, Google Images, a
 * picture from this PC, reset), and a position step that shows both card shapes before anything is saved. It opens as
 * its own window, so pictures can be dragged onto it from a browser.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun ChangeCoverDialog(target: CoverTarget, onDismiss: () -> Unit) {
    val covers = LocalCovers.current ?: return
    val art = covers.artwork
    val theme = LocalSkyTheme.current
    val scope = rememberCoroutineScope()
    val settings = art.settings
    val tabs = remember(target.key) { CoverTab.entries.filter { it != CoverTab.COLLAGE || target.isFolder } }
    var tab by remember { mutableStateOf(CoverTab.AUTO) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope0 = ArtworkKeys.scopeOf(target.key)
    var query by remember(target.key) { mutableStateOf(settings.searchTerm(scope0, target.title).ifBlank { NameCleaner.clean(target.title) }) }
    val choices = remember(target.title) { NameCleaner.choices(target.title) }
    var pending by remember { mutableStateOf<SkiaImage?>(null) }
    var pendingCandidate by remember { mutableStateOf<PerformerCandidate?>(null) }
    // Pictures gathered for a photo collage (up to four), and whether the next picture picked goes there.
    val collage = remember { mutableStateListOf<SkiaImage>() }
    var collecting by remember { mutableStateOf(false) }
    var choosingVideos by remember { mutableStateOf<String?>(null) }
    var frameVideo by remember(target.key) { mutableStateOf<WindowsMediaEntry?>(null) }
    val trusted = remember(settings.customEndpoint) { EndpointPolicy.hostOf(settings.customEndpoint) }

    fun close() {
        if (!busy) onDismiss()
    }

    fun run(work: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                error = e.failure.message
            } catch (e: Exception) {
                error = e.message ?: "That didn't work. Try again."
            } finally {
                busy = false
            }
        }
    }

    fun takePicture(image: SkiaImage, candidate: PerformerCandidate?) {
        if (collecting) {
            if (collage.size < PhotoCollage.MAX_PHOTOS) collage.add(image)
            collecting = false
            tab = CoverTab.PHOTOS
        } else {
            pending = image
            pendingCandidate = candidate
        }
    }

    /** A picture's bytes: a GIF stays moving (unless it goes into a collage); anything else goes to the position step. */
    suspend fun useBytes(bytes: ByteArray, candidate: PerformerCandidate?) {
        if (ArtworkHttp.isGif(bytes) && !collecting) {
            art.saveAnimated(target.key, bytes, "gif")
            onDismiss()
            return
        }
        val image = withContext(Dispatchers.Default) { Pictures.decode(bytes) } ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
        takePicture(image, candidate)
    }

    suspend fun bytesOf(address: String): ByteArray {
        val text = address.trim()
        if (text.startsWith("data:image/", ignoreCase = true)) {
            val encoded = text.substringAfter("base64,", "")
            if (encoded.isEmpty() || encoded.length > 30_000_000) throw ProviderException(ProviderFailure.BAD_RESPONSE)
            return runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrElse { throw ProviderException(ProviderFailure.BAD_RESPONSE) }
        }
        if (text.startsWith("http://", ignoreCase = true)) {
            throw IllegalStateException("That picture's address isn't secure (http). Right-click the picture, choose Copy image, then paste here.")
        }
        return ArtworkHttp.downloadBytes(text, trusted)
    }

    fun receive(incoming: Incoming?) {
        if (incoming == null) {
            error = "No picture found. In the browser, right-click a picture and choose Copy image (or Copy image address), then paste here."
            return
        }
        run {
            when (incoming) {
                is Incoming.Bytes -> useBytes(incoming.bytes, null)
                is Incoming.Address -> useBytes(bytesOf(incoming.url), null)
            }
        }
    }

    fun pasteClipboard() = receive(runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()?.let(::readIncoming))

    val drop = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                receive(readIncoming(event.awtTransferable))
                return true
            }
        }
    }

    DialogWindow(
        onCloseRequest = ::close,
        state = rememberDialogState(size = DpSize(820.dp, 720.dp)),
        title = "Change cover · " + target.title,
        onPreviewKeyEvent = { e ->
            // Ctrl+V pastes a copied picture or picture address, unless a text box takes it as text.
            if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.V && pending == null &&
                runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()
                    ?.let { it.isDataFlavorSupported(DataFlavor.imageFlavor) || it.isDataFlavorSupported(DataFlavor.javaFileListFlavor) } == true
            ) {
                pasteClipboard()
                true
            } else false
        },
    ) {
        LaunchedEffect(theme) { WindowChrome.apply(window, theme) }
        Box(
            Modifier.fillMaxSize().skyWallpaper(theme)
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = drop),
        ) {
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().padding(end = 14.dp).verticalScroll(scroll).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Change cover", color = theme.ink, style = MaterialTheme.typography.titleLarge)
                Text(target.title, color = theme.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val image = pending
                val purpose = choosingVideos
                if (purpose != null) {
                    VideoChooser(
                        title = when (purpose) {
                            "photos" -> "Pick pictures for the photo collage"
                            "frame" -> "Pick a video for its frame"
                            else -> "Pick videos for the collage"
                        },
                        videos = target.videos,
                        max = when (purpose) {
                            "photos" -> (PhotoCollage.MAX_PHOTOS - collage.size).coerceAtLeast(1)
                            "frame" -> 1
                            else -> 6
                        },
                        onDismiss = { choosingVideos = null },
                    ) { chosen ->
                        choosingVideos = null
                        run {
                            if (purpose == "frame") {
                                frameVideo = chosen.firstOrNull()
                            } else if (purpose == "photos") {
                                val pictures = withContext(Dispatchers.IO) {
                                    chosen.mapNotNull { v -> ThumbnailStore.fileFor(v.file, v.modified)?.let { Pictures.decode(it.readBytes()) } }
                                }
                                if (pictures.isEmpty()) throw IllegalStateException("Those videos have no thumbnails yet.")
                                collage.addAll(pictures.take(PhotoCollage.MAX_PHOTOS - collage.size))
                                if (pictures.size < chosen.size) error = "Some videos have no thumbnail yet."
                            } else {
                                val pictures = FolderCoverFiles.collagePictures(art, chosen, maxOf(settings.collageTiles, chosen.size))
                                try {
                                    if (pictures.isEmpty()) throw IllegalStateException("No pictures could be read from those videos.")
                                    val bytes = withContext(Dispatchers.Default) { FolderCovers.compose(pictures, grid = pictures.size >= 4) }
                                    val made = Pictures.decode(bytes) ?: throw IllegalStateException("Couldn't make the collage.")
                                    art.saveImage(target.key, ArtworkMode.CUSTOM, made, manual = true, focusX = 0.5f, focusY = 0.5f)
                                    onDismiss()
                                } finally {
                                    pictures.forEach { it.close() }
                                }
                            }
                        }
                    }
                } else if (image != null) {
                    PositionStep(image, saving = busy, onCancel = { pending = null }) { fx, fy, zoom ->
                        run {
                            val candidate = pendingCandidate
                            art.saveImage(target.key, if (candidate != null) ArtworkMode.PORTRAIT else ArtworkMode.CUSTOM, image, manual = true, candidate = candidate, focusX = fx, focusY = fy, zoom = zoom)
                            onDismiss()
                        }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tabs.forEach { t -> SkyChip(t.label, tab == t, { tab = t; error = null }) }
                    }
                    if (collecting) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Pick a picture for the photo collage.", color = theme.accent, style = MaterialTheme.typography.bodyMedium)
                            SkyButton("Back to the collage", { collecting = false; tab = CoverTab.PHOTOS }, primary = false)
                        }
                    }
                    when (tab) {
                        CoverTab.AUTO -> AutoFrameTab(target, art, busy, ::run, onDone = onDismiss, onNotice = { notice = it },
                            frameVideo = frameVideo, onChooseFrameVideo = { choosingVideos = "frame" })
                        CoverTab.COLLAGE -> {
                            Hint(
                                "A collage of ${settings.collageTiles} pictures from this folder (change the number in Settings > Artwork). " +
                                    "A folder with fewer videos uses several moments of each video.",
                            )
                            SkyButton("Use a collage", { run { art.setMode(target.key, ArtworkMode.COLLAGE); onDismiss() } }, enabled = !busy && target.videos.isNotEmpty(), icon = SkyIcon.Grid)
                            Hint("Or pick the videos it shows. Pick one video to make the collage from its moments.")
                            SkyButton("Choose videos…", { choosingVideos = "collage" }, enabled = !busy && target.videos.isNotEmpty(), primary = false)
                        }
                        CoverTab.PHOTOS -> PhotoCollageTab(
                            collage = collage,
                            enabled = !busy,
                            fromFolder = if (target.videos.isNotEmpty()) ({ choosingVideos = "photos" }) else null,
                            fromPc = {
                                val files = chooseImages(window, multiple = true)
                                run {
                                    val room = PhotoCollage.MAX_PHOTOS - collage.size
                                    val read = withContext(Dispatchers.IO) { files.take(room).mapNotNull { Pictures.decode(it.readBytes()) } }
                                    collage.addAll(read)
                                    if (read.size < minOf(room, files.size)) error = "Some pictures could not be read."
                                }
                            },
                            fromStashDb = { collecting = true; tab = CoverTab.INTERNET },
                            fromGoogle = { collecting = true; tab = CoverTab.GOOGLE },
                            onUse = {
                                run {
                                    val bytes = withContext(Dispatchers.Default) { PhotoCollage.compose(collage.toList()) }
                                    pending = Pictures.decode(bytes)
                                    pendingCandidate = null
                                }
                            },
                        )
                        CoverTab.INTERNET -> StashDbTab(
                            art = art,
                            target = target,
                            query = query,
                            onQuery = { query = it },
                            choices = choices,
                            busy = busy,
                            trusted = trusted,
                            run = ::run,
                            onError = { error = it },
                        ) { candidate, url -> run { useBytes(ArtworkHttp.downloadBytes(url, trusted), candidate) } }
                        CoverTab.GOOGLE -> {
                            Hint(
                                "Search opens Google Images in your browser. Then drag a picture onto this window, or right-click it, choose Copy image " +
                                    "(or Copy image address for a moving GIF) and press Ctrl+V or Paste here. Only what you search is sent, to Google.",
                            )
                            SearchRow(query, { query = it }, choices, "Search Google Images") { text ->
                                runCatching { Desktop.getDesktop().browse(URI(googleImagesUrl(text))) }
                                    .onFailure { error = "Couldn't open the browser." }
                            }
                            DropZone(busy, onPaste = ::pasteClipboard)
                        }
                        CoverTab.IMAGE -> {
                            Hint("Use a picture from this PC (JPG, PNG, WebP or a moving GIF). Your original file is not changed or moved. You can also drag a picture onto this window.")
                            SkyButton("Choose a picture…", {
                                chooseImages(window, multiple = false).firstOrNull()?.let { file ->
                                    run {
                                        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                                        useBytes(bytes, null)
                                    }
                                }
                            }, enabled = !busy, icon = SkyIcon.Image)
                        }
                        CoverTab.RESET -> {
                            Hint("Go back to the automatic cover for this item. Pictures you chose are removed from the app only.")
                            SkyButton("Reset to automatic", { run { art.clear(listOf(target.key)); onDismiss() } }, enabled = !busy, icon = SkyIcon.Refresh)
                        }
                    }
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = theme.accent, strokeWidth = 2.dp)
                        Text("Working…", color = theme.muted)
                    }
                }
                error?.let { Text(it, color = theme.accent, style = MaterialTheme.typography.bodyMedium) }
                notice?.let { Text(it, color = theme.muted, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SkyButton("Close", ::close, enabled = !busy, primary = false)
                }
            }
            ColumnScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
        }
    }

}

@Composable
private fun Hint(text: String) {
    Text(text, color = LocalSkyTheme.current.muted, style = MaterialTheme.typography.bodySmall)
}

/** Picture files from this PC. */
private fun chooseImages(owner: java.awt.Dialog, multiple: Boolean): List<File> {
    val dialog = FileDialog(owner, "Choose a picture", FileDialog.LOAD)
    dialog.isMultipleMode = multiple
    dialog.file = "*.jpg;*.jpeg;*.png;*.gif;*.webp;*.bmp"
    dialog.isVisible = true
    val files = dialog.files.orEmpty().toList().filter { it.isFile && it.length() in 1..ArtworkRepository.MAX_ANIMATED_BYTES }
    dialog.dispose()
    return files
}

/** A search box with its button and name suggestions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchRow(query: String, onQuery: (String) -> Unit, choices: List<String>, button: String, enabled: Boolean = true, onSearch: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SkyField(query, onQuery, "Name", Modifier.weight(1f), leading = SkyIcon.Search)
        SkyButton(button, { onSearch(query) }, enabled = enabled && query.isNotBlank(), icon = SkyIcon.Search)
    }
    if (choices.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { choice -> SkyChip(choice, choice == query, { onQuery(choice); onSearch(choice) }) }
        }
    }
}

/** Where a picture can be dropped, with the Paste button. */
@Composable
private fun DropZone(busy: Boolean, onPaste: () -> Unit) {
    val theme = LocalSkyTheme.current
    Column(
        Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(18.dp))
            .border(2.dp, theme.accent.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .background(theme.card.copy(alpha = 0.25f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        SkyIconView(SkyIcon.Image, theme.accent, size = 34.dp)
        Text("Drop a picture here, or paste it (Ctrl+V)", color = theme.ink, style = MaterialTheme.typography.bodyMedium)
        SkyButton("Paste picture or address", onPaste, enabled = !busy, primary = false)
    }
}

/** Auto frame: the automatic frame, a frame picked by hand (videos), the first video's frame (folders), or a moving cover. */
@Composable
private fun AutoFrameTab(
    target: CoverTarget,
    art: ArtworkRepository,
    busy: Boolean,
    run: (suspend () -> Unit) -> Unit,
    onDone: () -> Unit,
    onNotice: (String) -> Unit,
    frameVideo: WindowsMediaEntry?,
    onChooseFrameVideo: () -> Unit,
) {
    val video = target.video
    var picking by remember { mutableStateOf(false) }
    if (target.isFolder) {
        Hint("Use the frame of the first video in this folder.")
        SkyButton("Use first video frame", { run { art.setMode(target.key, ArtworkMode.FIRST_FRAME); onDone() } }, enabled = !busy, icon = SkyIcon.VideoLibrary)
        SkyButton("Choose a single video frame…", onChooseFrameVideo, enabled = !busy && target.videos.isNotEmpty(), primary = false, icon = SkyIcon.Image)
        if (frameVideo != null) FramePicker(frameVideo, busy, run) { image ->
            run { art.saveImage(target.key, ArtworkMode.CUSTOM, image, manual = true, focusY = 0.5f); onDone() }
        }
    } else if (video != null) {
        Hint("Pick any moment of this video, or go back to the automatic frame.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyButton("Choose a frame…", { picking = true }, enabled = !busy && !picking, icon = SkyIcon.Image)
            SkyButton("Use the automatic frame", { run { art.setMode(target.key, ArtworkMode.FRAME); onDone() } }, enabled = !busy, primary = false)
        }
        if (picking) FramePicker(video, busy, run) { image -> run { art.saveImage(target.key, ArtworkMode.CUSTOM, image, manual = true, focusY = 0.5f); onDone() } }
    }
    val source = video ?: target.videos.firstOrNull()
    Hint("Moving cover: eight moments of " + (if (target.isFolder) "the first video" else "this video") + " played one after another on the card.")
    SkyButton("Make a moving cover", {
        val file = source?.file ?: return@SkyButton
        run {
            onNotice("Reading frames from the video…")
            val frames = movingFrames(file)
            try {
                if (frames.size < 2) throw IllegalStateException("No frames could be read from the video. Try again when it is reachable.")
                val bytes = withContext(Dispatchers.Default) { FlipBook.strip(frames) }
                art.saveAnimated(target.key, bytes, "strip${frames.size}.jpg")
                onDone()
            } finally {
                frames.forEach { it.close() }
            }
        }
    }, enabled = !busy && source != null, primary = false, icon = SkyIcon.Play)
}

/** One frame of [video] at [start] (an mpv position), decoded, or null. */
private suspend fun frameAt(video: File, start: String, exact: Boolean, width: Int): SkiaImage? = withContext(Dispatchers.IO) {
    val temporary = Files.createTempFile("skywing-frame", ".jpg").toFile()
    try {
        if (ThumbnailExtractor.extract(video, temporary, start = start, exact = exact, width = width)) Pictures.decode(temporary.readBytes()) else null
    } finally {
        temporary.delete()
    }
}

/** Eight frames spread over a video, read three at a time. */
private suspend fun movingFrames(video: File): List<SkiaImage> = withContext(Dispatchers.IO) {
    val gate = Semaphore(3)
    (1..8).map { i -> async { gate.withPermit { frameAt(video, "${i * 100 / 9}%", exact = false, width = 480) } } }.awaitAll().filterNotNull()
}

/** A slider over the video: release it to see that moment, then use it as the cover. */
@Composable
private fun FramePicker(video: WindowsMediaEntry, busy: Boolean, run: (suspend () -> Unit) -> Unit, onUse: (SkiaImage) -> Unit) {
    val theme = LocalSkyTheme.current
    var position by remember { mutableFloatStateOf(10f) }
    var shown by remember { mutableFloatStateOf(-1f) }
    var frame by remember { mutableStateOf<SkiaImage?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown < 0f) {
            shown = position
            return@LaunchedEffect
        }
        loading = true
        frame = frameAt(video.file, "%.2f%%".format(java.util.Locale.ROOT, shown), exact = true, width = 1280)
        loading = false
    }
    Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(theme.card), contentAlignment = Alignment.Center) {
            val bitmap = remember(frame) { frame?.toComposeImageBitmap() }
            if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = theme.accent)
            else if (bitmap == null) Text("No frame could be read here. Try another moment.", color = theme.muted)
        }
        Text("Moment: ${position.toInt()}% of the video", color = theme.muted, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = position, onValueChange = { position = it }, valueRange = 0f..99f,
            onValueChangeFinished = { shown = position },
            colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent),
        )
        SkyButton("Use this frame", { frame?.let(onUse) }, enabled = !busy && !loading && frame != null, icon = SkyIcon.Check)
    }
}

/** Photo collage: up to four pictures from this folder, this PC, StashDB or Google, joined into one cover. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoCollageTab(
    collage: MutableList<SkiaImage>,
    enabled: Boolean,
    fromFolder: (() -> Unit)?,
    fromPc: () -> Unit,
    fromStashDb: () -> Unit,
    fromGoogle: () -> Unit,
    onUse: () -> Unit,
) {
    val theme = LocalSkyTheme.current
    Hint("Join up to four pictures into one cover. Pick them from this folder, this PC, StashDB or Google Images.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(PhotoCollage.MAX_PHOTOS) { index ->
            val picture = collage.getOrNull(index)
            Box(Modifier.size(110.dp).clip(RoundedCornerShape(12.dp)).background(theme.card), contentAlignment = Alignment.Center) {
                if (picture != null) {
                    val bitmap = remember(picture) { picture.toComposeImageBitmap() }
                    Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text("${index + 1}", color = theme.muted)
                }
            }
        }
    }
    val full = collage.size >= PhotoCollage.MAX_PHOTOS
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fromFolder != null) SkyButton("From this folder", fromFolder, enabled = enabled && !full, primary = false, icon = SkyIcon.Folder)
        SkyButton("From this PC", fromPc, enabled = enabled && !full, primary = false, icon = SkyIcon.Computer)
        SkyButton("From StashDB", fromStashDb, enabled = enabled && !full, primary = false, icon = SkyIcon.Search)
        SkyButton("From Google Images", fromGoogle, enabled = enabled && !full, primary = false, icon = SkyIcon.Image)
        SkyButton("Remove last", { if (collage.isNotEmpty()) collage.removeAt(collage.lastIndex) }, enabled = enabled && collage.isNotEmpty(), primary = false, icon = SkyIcon.Delete)
    }
    SkyButton("Use these pictures", onUse, enabled = enabled && collage.isNotEmpty(), icon = SkyIcon.Check)
}

/** StashDB: search a name with your own key, choose a match, then one of its photos. */
@Composable
private fun StashDbTab(
    art: ArtworkRepository,
    target: CoverTarget,
    query: String,
    onQuery: (String) -> Unit,
    choices: List<String>,
    busy: Boolean,
    trusted: Set<String>,
    run: (suspend () -> Unit) -> Unit,
    onError: (String?) -> Unit,
    onPick: (PerformerCandidate, String) -> Unit,
) {
    val theme = LocalSkyTheme.current
    val settings = art.settings
    var oneOff by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PerformerCandidate>?>(null) }
    var photosOf by remember { mutableStateOf<PerformerCandidate?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    // Suggestions already found in the background for this item are shown straight away.
    LaunchedEffect(target.key) {
        val saved = art.suggestions(target.key)
        if (saved.isNotEmpty() && results == null) results = saved
    }
    if (!settings.adultConsent && !oneOff) {
        Hint("Searching sends only the name below to StashDB, with the key you saved in Settings > Artwork. Nothing is sent until you allow it.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = oneOff, onCheckedChange = { oneOff = it })
            Text("Allow this one search", color = theme.ink)
        }
        return
    }
    val photos = photosOf
    if (photos != null) {
        PhotoChoice(photos, trusted, enabled = !busy, onBack = { photosOf = null }) { url -> onPick(photos, url) }
        return
    }
    fun search(text: String, next: Boolean) {
        val wanted = if (next) page + 1 else 1
        if (!next) results = null
        run {
            val key = art.keys.get() ?: throw ProviderException(ProviderFailure.NO_KEY)
            var term = text.trim()
            var found = StashDb.search(term, key, settings.customEndpoint.ifBlank { null }, wanted)
            // A video tries the name before the first comma, then the name before the hyphen.
            val terms = if (target.video != null) NameCleaner.videoTerms(target.title) else emptyList()
            val fallback = terms.getOrNull(1)
            if (!next && found.isEmpty() && fallback != null && term == terms.first()) {
                term = fallback
                onQuery(fallback)
                found = StashDb.search(term, key, settings.customEndpoint.ifBlank { null }, wanted)
            }
            val shown = if (next) results.orEmpty() else emptyList()
            val seen = shown.map { it.performerId }.toSet()
            results = shown + AutoMatch.ranked(term, found).filter { it.performerId !in seen }
            page = wanted
            more = found.size >= StashDb.PAGE_SIZE
            if (results.isNullOrEmpty()) onError("No matches. Try a different spelling, or the Google Images tab.")
        }
    }
    SearchRow(query, onQuery, choices, "Search StashDB", enabled = !busy) { search(it, next = false) }
    results?.forEach { candidate ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.surface)
                .skyClickable({
                    val url = candidate.imageUrl
                    when {
                        candidate.images.size > 1 -> photosOf = candidate
                        url != null -> onPick(candidate, url)
                        else -> onError("This match has no picture.")
                    }
                }, enabled = !busy).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemoteThumb(candidate.previewUrl, trusted, Modifier.width(72.dp).aspectRatio(3f / 4f))
            Column(Modifier.weight(1f)) {
                Text(candidate.name, color = theme.ink, style = MaterialTheme.typography.bodyMedium)
                val detail = listOfNotNull(candidate.disambiguation, candidate.providerName, if (candidate.images.size > 1) "${candidate.images.size} photos" else null)
                Text(detail.joinToString(" · "), color = theme.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (more && !results.isNullOrEmpty()) {
        SkyButton("More results (page ${page + 1})", { search(query, next = true) }, enabled = !busy, primary = false)
    }
}

/** All photos of one match, a page at a time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoChoice(candidate: PerformerCandidate, trusted: Set<String>, enabled: Boolean, onBack: () -> Unit, onChoose: (String) -> Unit) {
    val theme = LocalSkyTheme.current
    val perPage = 12
    var page by remember(candidate) { mutableIntStateOf(0) }
    val pages = (candidate.images.size + perPage - 1) / perPage
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SkyButton("‹ Results", onBack, primary = false)
        Text("${candidate.name} · ${candidate.images.size} photos", color = theme.ink, style = MaterialTheme.typography.titleSmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        candidate.images.drop(page * perPage).take(perPage).forEach { url ->
            Box(Modifier.width(120.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).skyClickable({ onChoose(url) }, enabled = enabled)) {
                RemoteThumb(url, trusted, Modifier.fillMaxSize())
            }
        }
    }
    if (pages > 1) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyButton("‹ Previous", { page-- }, enabled = page > 0, primary = false)
            Text("Page ${page + 1} of $pages", color = theme.muted)
            SkyButton("Next ›", { page++ }, enabled = page < pages - 1, primary = false)
        }
    }
}

@Composable
internal fun RemoteThumb(url: String?, trusted: Set<String>, modifier: Modifier) {
    val theme = LocalSkyTheme.current
    val picture by produceState<ImageBitmap?>(null, url) { value = url?.let { RemotePictures.load(it, trusted) } }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(theme.card), contentAlignment = Alignment.Center) {
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: SkyIconView(SkyIcon.Image, theme.muted, size = 20.dp)
    }
}

/** Ticks up to [max] videos of the folder, shown with their thumbnails, inside the cover window. */
@Composable
private fun VideoChooser(title: String, videos: List<WindowsMediaEntry>, max: Int, onDismiss: () -> Unit, onDone: (List<WindowsMediaEntry>) -> Unit) {
    val theme = LocalSkyTheme.current
    val chosen = remember { mutableStateListOf<WindowsMediaEntry>() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$title (${chosen.size} of $max)", color = theme.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        SkyButton("Cancel", onDismiss, primary = false)
        SkyButton("Use ${chosen.size}", { onDone(chosen.toList()) }, enabled = chosen.isNotEmpty(), icon = SkyIcon.Check)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxWidth().height(520.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(videos.take(500), key = { it.path.lowercase() }) { video ->
            val index = chosen.indexOf(video)
            Column(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .border(2.dp, if (index >= 0) theme.accent else theme.ink.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .skyClickable({ if (index >= 0) chosen.remove(video) else if (chosen.size < max) chosen.add(video) })
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                    Thumb(video.file, video.modified, Modifier.fillMaxSize())
                    if (index >= 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(RoundedCornerShape(50)).background(theme.accent),
                            contentAlignment = Alignment.Center,
                        ) { Text("${index + 1}", color = theme.onAccent, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                Text(video.file.nameWithoutExtension, color = theme.ink, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Position the picture: both card shapes side by side, with up/down, left/right and zoom, before anything is saved.
 */
@Composable
private fun PositionStep(image: SkiaImage, saving: Boolean, onCancel: () -> Unit, onApply: (Float, Float, Float) -> Unit) {
    val theme = LocalSkyTheme.current
    val bitmap = remember(image) { image.toComposeImageBitmap() }
    var fx by remember(image) { mutableFloatStateOf(0.5f) }
    var fy by remember(image) { mutableFloatStateOf(0.3f) }
    var slider by remember(image) { mutableFloatStateOf(0.5f) }
    val zoom = CoverCrop.zoomForSlider(slider)
    Text("Position the picture", color = theme.ink, style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(420.dp)) {
            Text("Landscape", color = theme.muted, style = MaterialTheme.typography.labelSmall)
            CropPreview(bitmap, CoverCrop.CARD_ASPECT, fx, fy, zoom)
        }
        Column(Modifier.width(178.dp)) {
            Text("Portrait 3:4", color = theme.muted, style = MaterialTheme.typography.labelSmall)
            CropPreview(bitmap, CoverCrop.TALL_ASPECT, fx, fy, zoom)
        }
    }
    val colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Up / down", modifier = Modifier.width(100.dp), color = theme.ink, style = MaterialTheme.typography.labelMedium)
        Slider(fy, { fy = it }, modifier = Modifier.weight(1f), colors = colors)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Left / right", modifier = Modifier.width(100.dp), color = theme.ink, style = MaterialTheme.typography.labelMedium)
        Slider(fx, { fx = it }, modifier = Modifier.weight(1f), colors = colors)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Zoom", modifier = Modifier.width(100.dp), color = theme.ink, style = MaterialTheme.typography.labelMedium)
        Slider(slider, { slider = it }, modifier = Modifier.weight(1f), colors = colors)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SkyButton("Back", onCancel, enabled = !saving, primary = false)
        SkyButton("Use this cover", { onApply(fx, fy, zoom) }, enabled = !saving, icon = SkyIcon.Check)
    }
}

@Composable
private fun CropPreview(bitmap: ImageBitmap, aspect: Float, fx: Float, fy: Float, zoom: Float) {
    val theme = LocalSkyTheme.current
    val window = CoverCrop.rect(bitmap.width, bitmap.height, aspect, fx, fy, zoom)
    Canvas(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(14.dp)).background(theme.card)) {
        // Draw the picture so the chosen window fills the box; outside the picture stays dark (blurred when saved).
        val scale = size.width / window.width
        drawImage(
            bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset((-window.left * scale).toInt(), (-window.top * scale).toInt()),
            dstSize = IntSize((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()),
        )
    }
}
