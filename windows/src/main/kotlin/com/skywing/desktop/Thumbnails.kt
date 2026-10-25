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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thumbnails for video cards. A picture is made once per video (a frame about a tenth of the way in),
 * kept on disk, and shown from memory afterwards. Cards on screen are made first, three at a time; the rest of the
 * library is worked through one by one in the background while nothing on screen is waiting, like the Android app
 * does after a scan.
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
    private val gate = Semaphore(3)
    private val waiting = java.util.concurrent.atomic.AtomicInteger()
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var backgroundJob: kotlinx.coroutines.Job? = null

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
        waiting.incrementAndGet()
        return try { make(key, video, target) } finally { waiting.decrementAndGet() }
    }

    private suspend fun make(key: String, video: File, target: File): ImageBitmap? =
        gate.withPermit {
            synchronized(memory) { memory[key] }?.let { return@withPermit it }
            val made = withContext(NonCancellable + Dispatchers.IO) {
                if (target.isFile && target.length() > 0) true else ThumbnailExtractor.extract(video, target)
            }
            val bitmap = if (made) withContext(Dispatchers.IO) { decode(target) } else null
            if (bitmap != null) remember(key, bitmap) else failed.add(key)
            bitmap
        }

    /** Picture file of a video, made now if missing. Blocking; for covers built from video frames. */
    fun fileFor(video: File, stamp: Long): File? {
        val target = File(directory, keyOf(video.path, stamp) + ".jpg")
        if (target.isFile && target.length() > 0) return target
        return if (ThumbnailExtractor.extract(video, target)) target else null
    }

    /**
     * Makes the missing pictures of [videos] in the background, one at a time and only while no card on screen is
     * waiting. A newer call replaces the previous run.
     */
    fun prefetch(videos: List<Pair<File, Long>>) {
        backgroundJob?.cancel()
        backgroundJob = background.launch {
            for ((video, stamp) in videos) {
                val key = keyOf(video.path, stamp)
                if (key in failed) continue
                val target = File(directory, "$key.jpg")
                if (target.isFile && target.length() > 0) continue
                while (waiting.get() > 0) kotlinx.coroutines.delay(400)
                val made = withContext(NonCancellable) { ThumbnailExtractor.extract(video, target) }
                if (!made) failed.add(key)
            }
        }
    }
}
