package com.deepak.videolibrary.thumbs

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import java.io.File

/** Where thumbnails and folder covers live. Both are in the app's private storage. */
object ThumbStore {
    const val THUMB_WIDTH = 480

    fun thumbDir(context: Context) = File(context.filesDir, "thumbs")

    fun coverDir(context: Context) = File(context.filesDir, "covers")

    fun thumbFile(context: Context, videoId: Long) = File(thumbDir(context), "$videoId.jpg")

    /** Private disk cache is persistent; it is not a video-stream buffer. */
    fun artworkBytes(context: Context): Long =
        listOf(thumbDir(context), coverDir(context)).sumOf { dir ->
            dir.listFiles()?.asSequence()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }

    /** Deletes cached artwork only. Nothing on the SMB drive is touched. */
    fun clearAll(context: Context) {
        thumbDir(context).deleteRecursively()
        coverDir(context).deleteRecursively()
        ImageMemoryCache.clear()
    }
}

/** Small in-memory cache of decoded images, capped by size so it cannot grow with the library. */
object ImageMemoryCache {
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    @Volatile private var registered = false
    fun register(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            context.applicationContext.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
                override fun onConfigurationChanged(config: android.content.res.Configuration) = Unit
                override fun onLowMemory() = clear()
                override fun onTrimMemory(level: Int) {
                    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                        level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) clear()
                    else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.trimToSize(cache.maxSize() / 2)
                }
            })
            registered = true
        }
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, image: ImageBitmap) {
        cache.put(key, image)
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    fun clear() {
        cache.evictAll()
    }
}
