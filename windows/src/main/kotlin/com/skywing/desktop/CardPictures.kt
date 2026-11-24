package com.skywing.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import com.skywing.desktop.artwork.Pictures
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect

/**
 * How many times smaller a picture of [source] size is decoded for a card of [box] size (B10): 1, 2, 4, 8 and so on,
 * the largest step that still covers the card when the picture is cropped to fill it. Whole steps of two keep one
 * decoded picture good for cards of similar sizes, so a small change of window size doesn't decode it again.
 */
internal fun shrinkStep(source: IntSize, box: IntSize): Int {
    if (box.width <= 0 || box.height <= 0) return 1
    var step = 1
    while (source.width / (step * 2) >= box.width && source.height / (step * 2) >= box.height) step *= 2
    return step
}

/** A picture decoded for cards, [step] times smaller than its file's [source] size (one frame's size for a strip). */
internal class CardPicture(val bitmap: ImageBitmap, val source: IntSize, val step: Int) {
    val bytes: Long get() = bitmap.width.toLong() * bitmap.height * 4

    /** True when it is sharp enough for a card of [box] size. */
    fun fits(box: IntSize): Boolean = step <= shrinkStep(source, box)
}

/** Decoded pictures kept within [maxBytes]; the one shown longest ago goes first. */
internal class PictureMemory(private val maxBytes: Long) {
    private class Kept(val picture: CardPicture, val kind: String)

    private val kept = LinkedHashMap<String, Kept>(256, 0.75f, true)
    private var total = 0L

    @Synchronized
    fun get(key: String): CardPicture? = kept[key]?.picture

    @Synchronized
    fun put(key: String, picture: CardPicture, kind: String) {
        kept.remove(key)?.let { total -= it.picture.bytes }
        if (picture.bytes > maxBytes) return
        kept[key] = Kept(picture, kind)
        total += picture.bytes
        val oldest = kept.values.iterator()
        while (total > maxBytes && oldest.hasNext()) {
            val gone = oldest.next()
            oldest.remove()
            total -= gone.picture.bytes
        }
    }

    @Synchronized
    fun bytesOf(kind: String): Long = kept.values.sumOf { if (it.kind == kind) it.picture.bytes else 0L }

    @Synchronized
    fun clear(kind: String) {
        val all = kept.values.iterator()
        while (all.hasNext()) {
            val one = all.next()
            if (one.kind == kind) {
                all.remove()
                total -= one.picture.bytes
            }
        }
    }
}

/** One moving cover's frames, decoded for cards of one size. */
internal class CardAnimation(val file: File, val frames: List<Pair<ImageBitmap, Int>>, val source: IntSize, val step: Int) {
    fun fits(file: File, box: IntSize): Boolean = file == this.file && step <= shrinkStep(source, box)
}

/** Covers and thumbnails decoded at the size their cards show them, in one memory budget (B10). */
internal object CardPictures {
    const val COVERS = "covers"
    const val THUMBNAILS = "thumbnails"
    private const val MAX_FRAMES = 200
    private const val MEGABYTE = 1024L * 1024

    /** About an eighth of the largest Java heap, and never more than 256 MB. */
    val memory = PictureMemory(minOf(Runtime.getRuntime().maxMemory() / 8, 256 * MEGABYTE))

    /** At most three pictures are read and decoded at once. */
    val decoding = Dispatchers.IO.limitedParallelism(3)

    private val sampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

    init {
        PerfMonitor.gauge("Picture memory: covers (MB)") { memory.bytesOf(COVERS) / MEGABYTE }
        PerfMonitor.gauge("Picture memory: thumbnails (MB)") { memory.bytesOf(THUMBNAILS) / MEGABYTE }
    }

    /**
     * The picture in [file] for a card of [box] size: the one in memory when it is sharp enough, otherwise read and
     * decoded again. Nothing is decoded before the card has a size. A card that leaves the screen first cancels it.
     */
    suspend fun load(file: File, key: String, kind: String, box: IntSize, frames: Int = 1): CardPicture? {
        val known = memory.get(key)
        if (known?.fits(box) == true || box.width <= 0 || box.height <= 0) return known
        return withContext(decoding) {
            memory.get(key)?.takeIf { it.fits(box) }?.let { return@withContext it }
            val bytes = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@withContext null
            ensureActive()
            decode(bytes, box, frames)?.also { memory.put(key, it, kind) }
        }
    }

    /**
     * Decodes [bytes] for a card of [box] size, [frames] being the number of frames a flip-book strip stacks top to
     * bottom. Null when the bytes are not a usable picture.
     */
    fun decode(bytes: ByteArray, box: IntSize, frames: Int = 1): CardPicture? {
        val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
        try {
            if (image.width !in 1..Pictures.MAX_DIMENSION || image.height !in frames..Pictures.MAX_DIMENSION) return null
            val frame = IntSize(image.width, image.height / frames)
            val step = shrinkStep(frame, box)
            val width = (frame.width / step).coerceAtLeast(1)
            val height = (frame.height / step).coerceAtLeast(1) * frames
            val bitmap = Bitmap()
            if (!bitmap.allocN32Pixels(width, height)) {
                bitmap.close()
                return null
            }
            val canvas = Canvas(bitmap)
            try {
                val source = Rect.makeWH(frame.width.toFloat(), (frame.height * frames).toFloat())
                canvas.drawImageRect(image, source, Rect.makeWH(width.toFloat(), height.toFloat()), sampling, null, true)
            } finally {
                canvas.close()
            }
            bitmap.setImmutable()
            return CardPicture(bitmap.asComposeImageBitmap(), frame, step)
        } catch (_: RuntimeException) {
            return null
        } finally {
            image.close()
        }
    }

    /** A moving GIF's frames (the first 200) for a card of [box] size. Each is decoded at full size, then shrunk. */
    fun decodeAnimation(file: File, bytes: ByteArray, box: IntSize): CardAnimation? = runCatching {
        Codec.makeFromData(Data.makeFromBytes(bytes)).use { codec ->
            val source = IntSize(codec.width, codec.height)
            val step = shrinkStep(source, box)
            val infos = codec.framesInfo
            Bitmap().use { full ->
                full.allocPixels(codec.imageInfo)
                val frames = List(minOf(codec.frameCount, MAX_FRAMES)) { i ->
                    codec.readPixels(full, i)
                    val small = Bitmap()
                    small.allocN32Pixels((source.width / step).coerceAtLeast(1), (source.height / step).coerceAtLeast(1))
                    full.peekPixels()?.scalePixels(small.peekPixels() ?: error("No pixels"), sampling)
                    small.setImmutable()
                    small.asComposeImageBitmap() to (infos.getOrNull(i)?.duration ?: 100).coerceAtLeast(20)
                }
                CardAnimation(file, frames, source, step)
            }
        }
    }.getOrNull()
}
