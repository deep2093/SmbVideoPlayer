package com.skywing.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.skywing.desktop.artwork.ArtworkKeys
import com.skywing.desktop.artwork.ArtworkMode
import com.skywing.desktop.artwork.ArtworkRepository
import com.skywing.desktop.artwork.ArtworkSource
import com.skywing.desktop.artwork.FolderCovers
import com.skywing.desktop.artwork.Pictures
import com.skywing.desktop.playback.ThumbnailExtractor
import org.jetbrains.skia.Image
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What cards need to choose a cover: the stored artwork and the naming rules that tell a video's folder name. */
internal class CoverContext(val artwork: ArtworkRepository, val nameRules: GroupingSwitches)

internal val LocalCovers = staticCompositionLocalOf<CoverContext?> { null }

/** Decoded pictures shown on cards, kept while there is room. */
private object PictureCache {
    private val memory = object : LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 300
    }

    fun get(file: File): ImageBitmap? = synchronized(memory) { memory[file.path] }

    fun load(file: File): ImageBitmap? {
        get(file)?.let { return it }
        val bitmap = runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull() ?: return null
        synchronized(memory) { memory[file.path] = bitmap }
        return bitmap
    }
}

/** "name.strip8.jpg": eight frames stacked top to bottom. */
private val STRIP = Regex("\\.strip(\\d+)\\.jpg$", RegexOption.IGNORE_CASE)

/** A stored picture filling its box: a still, an animated GIF, or a flip-book strip made from a video. */
@Composable
internal fun CoverImage(file: File, modifier: Modifier = Modifier) {
    val name = file.name
    val strip = STRIP.find(name)?.groupValues?.get(1)?.toIntOrNull()
    when {
        name.endsWith(".gif", ignoreCase = true) -> AnimatedGif(file, modifier)
        strip != null && strip > 1 -> FlipBookImage(file, strip, modifier)
        else -> {
            val picture by produceState(PictureCache.get(file), file) { value = withContext(Dispatchers.IO) { PictureCache.load(file) } }
            ReportScreenColor(file.path, picture)
            picture?.let { Image(it, null, modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
}

/** Plays a moving GIF cover, cropped to fill like a still one. */
@Composable
private fun AnimatedGif(file: File, modifier: Modifier) {
    val frames by produceState<List<Pair<ImageBitmap, Int>>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val codec = org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(file.readBytes()))
                val infos = codec.framesInfo
                List(minOf(codec.frameCount, 200)) { i ->
                    val bitmap = org.jetbrains.skia.Bitmap()
                    bitmap.allocPixels(codec.imageInfo)
                    codec.readPixels(bitmap, i)
                    bitmap.setImmutable()
                    bitmap.asComposeImageBitmap() to (infos.getOrNull(i)?.duration ?: 100).coerceAtLeast(20)
                }
            }.getOrNull()
        }
    }
    var index by remember(file) { mutableIntStateOf(0) }
    val list = frames
    LaunchedEffect(list) {
        if (list == null || list.size < 2) return@LaunchedEffect
        while (true) {
            delay(list[index % list.size].second.toLong())
            index = (index + 1) % list.size
        }
    }
    list?.getOrNull(index % list.size.coerceAtLeast(1))?.let { Image(it.first, null, modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

/** Plays a strip of frames as a flip-book, one frame every 450 ms, like Android. */
@Composable
private fun FlipBookImage(file: File, frames: Int, modifier: Modifier) {
    val image by produceState(PictureCache.get(file), file) { value = withContext(Dispatchers.IO) { PictureCache.load(file) } }
    var frame by remember(file) { mutableIntStateOf(0) }
    LaunchedEffect(file, frames) {
        while (true) {
            delay(450)
            frame = (frame + 1) % frames
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val img = image ?: return@Canvas
        val frameHeight = img.height / frames
        if (frameHeight <= 0 || size.width <= 0f || size.height <= 0f) return@Canvas
        val target = size.width / size.height
        val source = img.width.toFloat() / frameHeight
        val (w, h) = if (source > target) (frameHeight * target).toInt() to frameHeight else img.width to (img.width / target).toInt()
        drawImage(
            img,
            srcOffset = IntOffset((img.width - w) / 2, frame * frameHeight + (frameHeight - h) / 2),
            srcSize = IntSize(w.coerceAtLeast(1), h.coerceAtLeast(1)),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}

/** The plain placeholder: no picture (yet, or chosen as "No cover"). */
@Composable
internal fun CoverPlaceholder(glyph: SkyIcon, modifier: Modifier = Modifier) {
    val theme = LocalSkyTheme.current
    Box(modifier.fillMaxSize().background(Brush.linearGradient(listOf(theme.card, theme.surface))), contentAlignment = Alignment.Center) {
        SkyIconView(glyph, theme.muted.copy(alpha = 0.5f), size = 30.dp)
    }
}

/** A video's cover: the picture chosen for it (or its folder's portrait), otherwise its thumbnail. */
@Composable
internal fun VideoPicture(entry: WindowsMediaEntry, tall: Boolean, modifier: Modifier = Modifier) {
    val covers = LocalCovers.current
    if (covers == null) {
        Thumb(entry.file, entry.modified, modifier)
        return
    }
    val art = covers.artwork
    val decision = remember(entry.path, art.revision, covers.nameRules) {
        val name = WindowsVirtualNames.of(entry.file.name, covers.nameRules).firstOrNull()?.let(WindowsVirtualNames::identity)
        art.decideVideo(entry.path, name)
    }
    when (decision.source) {
        ArtworkSource.IMAGE -> {
            val file = remember(decision.imageName, tall) { ArtworkRepository.forShape(art.imageFile(decision.imageName), tall) }
            if (file != null) Box(modifier) { CoverImage(file) } else Thumb(entry.file, entry.modified, modifier)
        }
        ArtworkSource.NONE -> Box(modifier) { CoverPlaceholder(SkyIcon.Play) }
        else -> Thumb(entry.file, entry.modified, modifier)
    }
}

/** Folder covers built from thumbnails, two at a time, cached on disk until the chosen videos change. */
internal object FolderCoverFiles {
    private val gate = Semaphore(2)

    suspend fun build(art: ArtworkRepository, key: String, videos: List<WindowsMediaEntry>, style: ArtworkMode, tiles: Int): File? =
        gate.withPermit {
            withContext(Dispatchers.IO) {
                val picks = FolderCovers.picks(videos, style, tiles)
                if (picks.isEmpty()) return@withContext null
                // One video: its thumbnail is the cover.
                if (picks.size == 1 && style != ArtworkMode.COLLAGE) return@withContext ThumbnailStore.fileFor(picks[0].file, picks[0].modified)
                val prefix = ArtworkRepository.hash(key)
                val tag = when (style) {
                    ArtworkMode.FIRST_FRAME -> "f"
                    ArtworkMode.COLLAGE -> "c"
                    else -> "e"
                }
                val fingerprint = ArtworkRepository.hash(picks.joinToString("|") { it.path.lowercase() + ":" + it.modified } + "|" + tiles)
                val dir = art.coverDir.apply { mkdirs() }
                val target = File(dir, "${prefix}_${tag}_$fingerprint.jpg")
                if (target.isFile) return@withContext target
                val images = if (style == ArtworkMode.COLLAGE) collagePictures(art, videos, tiles.coerceIn(2, 6))
                else picks.mapNotNull { v -> ThumbnailStore.fileFor(v.file, v.modified)?.let { Pictures.decode(it.readBytes()) } }
                if (images.isEmpty()) return@withContext null
                try {
                    val bytes = FolderCovers.compose(images, grid = style == ArtworkMode.COLLAGE)
                    val temporary = File(dir, target.name + ".tmp")
                    temporary.writeBytes(bytes)
                    java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    dir.listFiles { f -> f != target && f.name.startsWith("${prefix}_$tag") }?.forEach { it.delete() }
                    target
                } finally {
                    images.forEach { it.close() }
                }
            }
        }

    /**
     * [count] pictures for a collage of [videos]: one thumbnail per video when there are enough videos, otherwise
     * several moments of each video (made once and kept). The caller closes the pictures.
     */
    suspend fun collagePictures(art: ArtworkRepository, videos: List<WindowsMediaEntry>, count: Int): List<Image> = withContext(Dispatchers.IO) {
        if (videos.isEmpty()) return@withContext emptyList()
        if (videos.size >= count) {
            return@withContext FolderCovers.evenly(videos, count).mapNotNull { v -> ThumbnailStore.fileFor(v.file, v.modified)?.let { Pictures.decode(it.readBytes()) } }
        }
        val rounds = (count + videos.size - 1) / videos.size
        List(count) { i ->
            val video = videos[i % videos.size]
            val moment = (i / videos.size + 1) * 100 / (rounds + 1)
            val file = File(art.coverDir.apply { mkdirs() }, "m_" + ArtworkRepository.hash(video.path.lowercase() + ":" + video.modified + ":" + moment) + ".jpg")
            if (!(file.isFile && file.length() > 0)) ThumbnailStore.momentFor(video.file, file, "$moment%")
            file.takeIf { it.isFile && it.length() > 0 }?.let { Pictures.decode(it.readBytes()) }
        }.filterNotNull()
    }
}

/** A folder's cover: the picture chosen for it, its portrait, or one made from its videos' thumbnails. */
@Composable
internal fun FolderPicture(coverKey: String?, videos: List<WindowsMediaEntry>, tall: Boolean, listMode: Boolean, modifier: Modifier = Modifier) {
    val covers = LocalCovers.current
    if (covers == null || coverKey == null) {
        val first = videos.firstOrNull()
        if (first != null) Thumb(first.file, first.modified, modifier, glyph = SkyIcon.Folder) else Box(modifier) { CoverPlaceholder(SkyIcon.Folder) }
        return
    }
    val art = covers.artwork
    val decision = remember(coverKey, art.revision) { art.decideFolder(coverKey) }
    val tiles = art.settings.collageTiles
    val fingerprint = remember(videos) { videos.size to videos.take(1).plus(videos.getOrNull(videos.size / 2)).plus(videos.lastOrNull()).map { it?.path } }
    val file by produceState<File?>(null, coverKey, decision, tall, fingerprint, tiles) {
        value = when (decision.source) {
            ArtworkSource.NONE -> null
            ArtworkSource.IMAGE -> ArtworkRepository.forShape(art.imageFile(decision.imageName), tall)
            else -> {
                val style = when (decision.source) {
                    ArtworkSource.FIRST_FRAME -> ArtworkMode.FIRST_FRAME
                    ArtworkSource.COLLAGE -> ArtworkMode.COLLAGE
                    else -> ArtworkMode.EXISTING
                }
                runCatching { FolderCoverFiles.build(art, coverKey, videos, style, tiles) }.getOrNull()
            }
        }
    }
    Box(modifier) {
        val shown = file
        if (shown != null) CoverImage(shown) else CoverPlaceholder(SkyIcon.Folder)
    }
}

/** The cover key of a drive folder. */
internal fun driveCoverKey(path: String) = ArtworkKeys.originalFolder(path)
