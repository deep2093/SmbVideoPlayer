package com.skywing.desktop.artwork

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** A window inside an image, in pixels. */
internal data class CropWindow(val left: Int, val top: Int, val width: Int, val height: Int)

/** Cropping an image to a card shape while letting the user move the visible part and zoom (as on Android). */
internal object CoverCrop {
    /** Landscape cards and thumbnails are 16:9. */
    const val CARD_ASPECT = 16f / 9f

    /** Taller (portrait) cards are 3:4. */
    const val TALL_ASPECT = 3f / 4f
    const val MIN_ZOOM = 0.5f
    const val MAX_ZOOM = 3f

    /**
     * The part of a [width] x [height] image to keep for [aspect]. [focusX] and [focusY] (0..1) place the window in
     * the room available. [zoom] above 1 moves closer in; below 1 the window grows past the picture's edges and the
     * extra space is filled with a blurred copy of the picture.
     */
    fun rect(width: Int, height: Int, aspect: Float, focusX: Float, focusY: Float, zoom: Float = 1f): CropWindow {
        require(width > 0 && height > 0 && aspect > 0f)
        val imageAspect = width.toFloat() / height
        val fx = focusX.coerceIn(0f, 1f)
        val fy = focusY.coerceIn(0f, 1f)
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val fullW = if (imageAspect > aspect) height * aspect else width.toFloat()
        val fullH = if (imageAspect > aspect) height.toFloat() else width / aspect
        val w = (fullW / z).toInt().let { if (z >= 1f) it.coerceIn(1, width) else it.coerceAtLeast(1) }
        val h = (fullH / z).toInt().let { if (z >= 1f) it.coerceIn(1, height) else it.coerceAtLeast(1) }
        return CropWindow(((width - w) * fx).toInt(), ((height - h) * fy).toInt(), w, h)
    }

    /** Zoom for a slider position 0..1, with 1x in the middle: left zooms out, right zooms in. */
    fun zoomForSlider(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        return if (p <= 0.5f) MIN_ZOOM + (1f - MIN_ZOOM) * (p / 0.5f) else 1f + (MAX_ZOOM - 1f) * ((p - 0.5f) / 0.5f)
    }

    fun overflows(window: CropWindow, width: Int, height: Int): Boolean =
        window.left < 0 || window.top < 0 || window.left + window.width > width || window.top + window.height > height

    /** The chosen window as a JPEG at most [maxWidth] wide. */
    fun apply(source: Image, aspect: Float, focusX: Float, focusY: Float, maxWidth: Int, zoom: Float = 1f): ByteArray {
        val r = rect(source.width, source.height, aspect, focusX, focusY, zoom)
        val outW = minOf(maxWidth, r.width).coerceAtLeast(1)
        val outH = maxOf(1, (outW.toLong() * r.height / r.width).toInt())
        return Pictures.draw(outW, outH) { canvas ->
            if (overflows(r, source.width, source.height)) {
                // Zoomed out: a soft, darkened copy of the picture fills the borders.
                val cover = maxOf(outW.toFloat() / source.width, outH.toFloat() / source.height)
                val bw = source.width * cover
                val bh = source.height * cover
                val blur = Paint().apply { imageFilter = ImageFilter.makeBlur(18f, 18f, FilterTileMode.CLAMP) }
                canvas.drawImageRect(source, Rect.makeXYWH((outW - bw) / 2f, (outH - bh) / 2f, bw, bh), blur)
                canvas.drawRect(Rect.makeWH(outW.toFloat(), outH.toFloat()), Paint().apply { color = Color.makeARGB(90, 0, 0, 0) })
            }
            val scale = outW.toFloat() / r.width
            canvas.drawImageRect(
                source,
                Rect.makeXYWH(-r.left * scale, -r.top * scale, source.width * scale, source.height * scale),
                Pictures.smooth,
            )
        }
    }
}

/** Drawing, decoding and encoding pictures with Skia, the graphics library Compose already uses. */
internal object Pictures {
    const val MAX_DIMENSION = 10_000
    val smooth = Paint().apply { isAntiAlias = true }

    /** Decodes a picture, or null when the bytes are not a usable image. */
    fun decode(bytes: ByteArray): Image? = runCatching { Image.makeFromEncoded(bytes) }.getOrNull()
        ?.takeIf { it.width in 1..MAX_DIMENSION && it.height in 1..MAX_DIMENSION }

    fun draw(width: Int, height: Int, block: (Canvas) -> Unit): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            surface.canvas.clear(Color.BLACK)
            block(surface.canvas)
            return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 88)?.bytes ?: error("Could not encode the picture")
        } finally {
            surface.close()
        }
    }

    /** Draws [image] into [dst], cropped from around ([fx], [fy]) so nothing is stretched. */
    fun fill(canvas: Canvas, image: Image, dst: Rect, fx: Float = 0.5f, fy: Float = 0.5f) {
        val w = CoverCrop.rect(image.width, image.height, dst.width / dst.height, fx, fy)
        canvas.drawImageRect(
            image,
            Rect.makeXYWH(w.left.toFloat(), w.top.toFloat(), w.width.toFloat(), w.height.toFloat()),
            dst,
            SamplingMode.LINEAR,
            smooth,
            true,
        )
    }
}

/**
 * Folder covers built from video thumbnails, like the Android CoverBuilder: the existing look (one video, two panels,
 * or the first, middle and last video in three panels), the first video's frame, or a grid of 2 to 6 frames.
 */
internal object FolderCovers {
    private const val WIDTH = 480
    private const val HEIGHT = 270
    private const val GAP = 4

    /** [count] items spread evenly across [items] (all of them when there are fewer). */
    fun <T> evenly(items: List<T>, count: Int): List<T> {
        if (items.size <= count) return items
        return List(count) { i -> items[(i.toLong() * (items.size - 1) / (count - 1)).toInt()] }
    }

    /** Which videos a cover of [style] uses. */
    fun <T> picks(items: List<T>, style: ArtworkMode, tiles: Int): List<T> = when {
        items.isEmpty() -> emptyList()
        style == ArtworkMode.FIRST_FRAME -> listOf(items[0])
        style == ArtworkMode.COLLAGE -> evenly(items, tiles.coerceIn(2, 6))
        items.size <= 2 -> items
        else -> listOf(items.first(), items[items.size / 2], items.last())
    }

    fun compose(pictures: List<Image>, grid: Boolean): ByteArray = Pictures.draw(WIDTH, HEIGHT) { canvas ->
        val half = ((WIDTH - GAP) / 2).toFloat()
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        if (grid && pictures.size >= 4) {
            // Two columns for four tiles, three for five or six; a shorter last row stretches its tiles.
            val cols = if (pictures.size == 4) 2 else 3
            val rows = (pictures.size + cols - 1) / cols
            val cellH = (h - GAP * (rows - 1)) / rows
            pictures.forEachIndexed { i, picture ->
                val row = i / cols
                val col = i % cols
                val inRow = minOf(cols, pictures.size - row * cols)
                val cellW = (w - GAP * (inRow - 1)) / inRow
                Pictures.fill(canvas, picture, Rect.makeXYWH(col * (cellW + GAP), row * (cellH + GAP), cellW, cellH))
            }
        } else when (pictures.size) {
            1 -> Pictures.fill(canvas, pictures[0], Rect.makeWH(w, h))
            2 -> {
                Pictures.fill(canvas, pictures[0], Rect.makeXYWH(0f, 0f, half, h))
                Pictures.fill(canvas, pictures[1], Rect.makeXYWH(w - half, 0f, half, h))
            }
            else -> {
                Pictures.fill(canvas, pictures[0], Rect.makeXYWH(0f, 0f, half, h))
                Pictures.fill(canvas, pictures[1], Rect.makeXYWH(w - half, 0f, half, (h - GAP) / 2))
                Pictures.fill(canvas, pictures[2], Rect.makeXYWH(w - half, (h + GAP) / 2, half, (h - GAP) / 2))
            }
        }
    }
}

/**
 * Joins up to four chosen pictures into one square cover: 1 fills it, 2 side by side, 3 one tall picture with two
 * stacked, 4 a 2 x 2 grid. Each picture is cut around its upper part, where faces usually are.
 */
internal object PhotoCollage {
    const val MAX_PHOTOS = 4
    private const val SIZE = 1200f
    private const val GAP = 6f

    fun tiles(count: Int): List<Rect> {
        val half = (SIZE - GAP) / 2
        val right = half + GAP
        return when (count.coerceIn(1, MAX_PHOTOS)) {
            1 -> listOf(Rect.makeWH(SIZE, SIZE))
            2 -> listOf(Rect.makeXYWH(0f, 0f, half, SIZE), Rect.makeXYWH(right, 0f, half, SIZE))
            3 -> listOf(Rect.makeXYWH(0f, 0f, half, SIZE), Rect.makeXYWH(right, 0f, half, half), Rect.makeXYWH(right, right, half, half))
            else -> listOf(
                Rect.makeXYWH(0f, 0f, half, half), Rect.makeXYWH(right, 0f, half, half),
                Rect.makeXYWH(0f, right, half, half), Rect.makeXYWH(right, right, half, half),
            )
        }
    }

    fun compose(pictures: List<Image>): ByteArray {
        require(pictures.isNotEmpty())
        val shown = pictures.take(MAX_PHOTOS)
        return Pictures.draw(SIZE.toInt(), SIZE.toInt()) { canvas ->
            tiles(shown.size).zip(shown).forEach { (tile, picture) -> Pictures.fill(canvas, picture, tile, 0.5f, 0.25f) }
        }
    }
}

/** A moving cover made from a video: frames stacked top to bottom in one picture, played as a flip-book. */
internal object FlipBook {
    fun strip(frames: List<Image>): ByteArray {
        require(frames.size >= 2)
        val w = frames.first().width
        val h = frames.first().height
        return Pictures.draw(w, h * frames.size) { canvas ->
            frames.forEachIndexed { i, frame -> Pictures.fill(canvas, frame, Rect.makeXYWH(0f, (i * h).toFloat(), w.toFloat(), h.toFloat())) }
        }
    }
}
