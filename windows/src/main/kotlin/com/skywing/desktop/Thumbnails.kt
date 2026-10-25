package com.skywing.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.skywing.desktop.playback.ThumbnailExtractor
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Thumbnails for video cards. A picture is made once per video (a frame about a tenth of the way in),
 * kept on disk, and shown from memory afterwards. Making them runs two at a time in the background.
 */
internal object ThumbnailStore {
    private val directory: File by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        Paths.get(base, "SkyWing", "library", "thumbs").toFile()
    }
    private val memory = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 400
    }
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val gate = Semaphore(2)

    private fun keyOf(path: String, stamp: Long): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((path.lowercase() + "|" + stamp).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decode(picture: File): ImageBitmap? {
        if (!picture.isFile || picture.length() <= 0) return null
        return runCatching { org.jetbrains.skia.Image.makeFromEncoded(picture.readBytes()).toComposeImageBitmap() }.getOrNull()
    }

    private fun remember(key: String, bitmap: ImageBitmap) {
        synchronized(memory) { memory[key] = bitmap }
    }

    suspend fun load(video: File, stamp: Long): ImageBitmap? {
        val key = keyOf(video.path, stamp)
        synchronized(memory) { memory[key] }?.let { return it }
        if (key in failed) return null
        val target = File(directory, "$key.jpg")
        withContext(Dispatchers.IO) { decode(target) }?.let {
            remember(key, it)
            return it
        }
        return gate.withPermit {
            synchronized(memory) { memory[key] }?.let { return@withPermit it }
            val made = withContext(NonCancellable + Dispatchers.IO) {
                if (target.isFile && target.length() > 0) true else ThumbnailExtractor.extract(video, target)
            }
            val bitmap = if (made) withContext(Dispatchers.IO) { decode(target) } else null
            if (bitmap != null) remember(key, bitmap) else failed.add(key)
            bitmap
        }
    }
}
