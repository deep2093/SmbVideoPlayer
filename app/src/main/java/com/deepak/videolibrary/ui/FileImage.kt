package com.deepak.videolibrary.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.deepak.videolibrary.thumbs.ImageMemoryCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows a cached JPEG, decoded off the main thread and kept in a size-limited memory cache.
 * While there is no image (null file, or not decoded yet) a dark placeholder is shown.
 */
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
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("▶", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}
