package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.deepak.videolibrary.thumbs.ImageMemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows a cached JPEG, decoded off the main thread and kept in a size-limited memory cache.
 * While there is no image (null file, or not decoded yet) a dark placeholder is shown.
 */
// Compose lint misses the explicit producer value assignments below.
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun FileImage(file: File?, modifier: Modifier = Modifier) {
    // Moving covers: an animated GIF, or a strip of frames made from a video that plays as a flip-book.
    val name = file?.name.orEmpty()
    if (file != null && name.endsWith(".gif", ignoreCase = true)) {
        AnimatedGifImage(file, modifier)
        return
    }
    val strip = STRIP.find(name)?.groupValues?.get(1)?.toIntOrNull()
    if (file != null && strip != null && strip > 1) {
        FlipbookImage(file, strip, modifier)
        return
    }
    StillFileImage(file, modifier)
}

/** "name.strip8.jpg": eight frames stacked top to bottom. */
private val STRIP = Regex("\\.strip(\\d+)\\.jpg$", RegexOption.IGNORE_CASE)

/** Plays an animated GIF, cropped to fill like a still cover. Decoded at a small size to save memory. */
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun AnimatedGifImage(file: File, modifier: Modifier) {
    val drawable by produceState<android.graphics.drawable.Drawable?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.setTargetSampleSize(maxOf(1, info.size.width / 480))
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context -> android.widget.ImageView(context).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } },
            update = { view ->
                val current = drawable
                if (view.drawable !== current) {
                    view.setImageDrawable(current)
                    (current as? android.graphics.drawable.AnimatedImageDrawable)?.apply {
                        repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                        start()
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Plays a strip of video frames one after another, each cropped to fill the card. */
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun FlipbookImage(file: File, frames: Int, modifier: Modifier) {
    val image by produceState(ImageMemoryCache.get(file.path), file) {
        value = ImageMemoryCache.get(file.path) ?: withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also { ImageMemoryCache.put(file.path, it) }
        }
    }
    var frame by androidx.compose.runtime.remember(file) { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(file, frames) {
        while (true) {
            kotlinx.coroutines.delay(450)
            frame = (frame + 1) % frames
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val img = image ?: return@Canvas
            val frameHeight = img.height / frames
            if (frameHeight <= 0 || size.width <= 0f || size.height <= 0f) return@Canvas
            val target = size.width / size.height
            val source = img.width.toFloat() / frameHeight
            val (w, h) = if (source > target) (frameHeight * target).toInt() to frameHeight else img.width to (img.width / target).toInt()
            val x = (img.width - w) / 2
            val y = frame * frameHeight + (frameHeight - h) / 2
            drawImage(
                img,
                srcOffset = androidx.compose.ui.unit.IntOffset(x, y),
                srcSize = androidx.compose.ui.unit.IntSize(w.coerceAtLeast(1), h.coerceAtLeast(1)),
                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
    }
}

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun StillFileImage(file: File?, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(
        initialValue = file?.let { ImageMemoryCache.get(it.path) },
        file,
    ) {
        value = if (file == null) {
            null
        } else {
            ImageMemoryCache.get(file.path) ?: withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also {
                    ImageMemoryCache.put(file.path, it)
                }
            }
        }
    }

    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        // Adaptive themes colour the background from the pictures on screen: each shown picture lends its colour.
        if (img != null && file != null && LocalLibraryTheme.current.adaptive != null) {
            val color by produceState(CoverColors.cached(file.path), file.path, img) { value = CoverColors.of(file.path, img) }
            androidx.compose.runtime.DisposableEffect(color) {
                val token = color?.let(ScreenColors::add)
                onDispose { token?.let(ScreenColors::remove) }
            }
        }
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
    }
}
