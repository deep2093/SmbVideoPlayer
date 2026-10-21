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
