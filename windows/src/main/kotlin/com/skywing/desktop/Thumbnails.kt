package com.skywing.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.skywing.desktop.playback.GpuAdapters
import com.skywing.desktop.playback.ThumbnailExtractor
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thumbnails for video cards. A picture is made once per video (a frame about a tenth of the way in),
 * kept on disk, and shown from memory afterwards. Cards on screen are made first; the rest of the library is worked
 * through one by one in the background while nothing on screen is waiting, like the Android app does after a scan.
 * How many are made at once follows the PC and its power ([thumbnailWorkers]); while a video plays, one card picture
 * is made at a time and the background run waits (B24).
 */
internal object ThumbnailStore {
    private val directory: File by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        Paths.get(base, "SkyWing", "library", "thumbs").toFile()
    }
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val failureDetails = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val retryAfter = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun workers() = thumbnailWorkers(PowerSource.onBattery(), GpuAdapters.hasDedicated, playing)
    private val gate = WorkLimit(::workers)

    /** True while a video plays: the background run waits and cards get one picture at a time. */
    @Volatile var playing = false
    private val waiting = java.util.concurrent.atomic.AtomicInteger()
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var backgroundJob: kotlinx.coroutines.Job? = null
    private val made = java.util.concurrent.atomic.AtomicInteger()

    init {
        PerfMonitor.gauge("Thumbnails waiting") { waiting.get().toLong() }
        PerfMonitor.gauge("Thumbnails made") { made.get().toLong() }
        PerfMonitor.gauge("Thumbnails being made") { gate.active.toLong() }
        PerfMonitor.gauge("Thumbnails at once") { workers().toLong() }
    }

    /** The name (without .jpg) of the picture file kept for the video at [path] with modification time [stamp]. */
    internal fun keyOf(path: String, stamp: Long): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((path.lowercase() + "|" + stamp).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** The stored picture decoded for a card of [box] size (B10). */
    private suspend fun decode(key: String, picture: File, box: IntSize): ImageBitmap? =
        CardPictures.load(picture, key, CardPictures.THUMBNAILS, box)?.bitmap

    /**
     * The thumbnail of [video] for a card of [box] size, made first when there is none. Before the card has a size
     * only a picture already in memory is given, and nothing is made.
     */
    suspend fun load(video: File, stamp: Long, box: IntSize): ImageBitmap? {
        val key = keyOf(video.path, stamp)
        val known = CardPictures.memory.get(key)
        if (known?.fits(box) == true || box.width <= 0 || box.height <= 0) return known?.bitmap
        if (key in failed) return null
        if ((retryAfter[key] ?: 0L) > System.currentTimeMillis()) return null
        val target = File(directory, "$key.jpg")
        decode(key, target, box)?.let { return it }
        waiting.incrementAndGet()
        return try { make(key, video, target, box) } finally { waiting.decrementAndGet() }
    }

    private suspend fun make(key: String, video: File, target: File, box: IntSize): ImageBitmap? =
        gate.withPermit {
            CardPictures.memory.get(key)?.takeIf { it.fits(box) }?.let { return@withPermit it.bitmap }
            val made = withContext(Dispatchers.IO) { generate(key, video, target) }
            val bitmap = if (made) decode(key, target, box) else null
            if (bitmap == null && made) { failed.add(key); failureDetails[key] = "The generated JPEG could not be decoded" }
            bitmap
        }

    private fun generate(key: String, video: File, target: File): Boolean {
        if (target.isFile && target.length() > 0) return true
        val first = ThumbnailExtractor.extractDetailed(video, target)
        val fallback = if (!first.success) ThumbnailExtractor.extractDetailed(video, target, start = "0") else null
        val worked = first.success || fallback?.success == true
        if (worked) {
            made.incrementAndGet()
            retryAfter.remove(key)
            failureDetails.remove(key)
            failed.remove(key)
        } else {
            failureDetails[key] = "10%: ${first.reason} (${first.elapsedMs} ms); start: ${fallback?.reason} (${fallback?.elapsedMs} ms)"
            if (first.timedOut || fallback?.timedOut == true) retryAfter[key] = System.currentTimeMillis() + RETRY_DELAY_MS
            else failed.add(key)
        }
        return worked
    }

    /** Picture file of a video, using the same queue, fallback and failure tracking as video cards. */
    suspend fun fileFor(video: File, stamp: Long): File? {
        val key = keyOf(video.path, stamp)
        val target = File(directory, "$key.jpg")
        if (target.isFile && target.length() > 0) return target
        if (key in failed || (retryAfter[key] ?: 0L) > System.currentTimeMillis()) return null
        return gate.withPermit { if (withContext(Dispatchers.IO) { generate(key, video, target) }) target else null }
    }

    fun failureReason(video: File, stamp: Long): String? = failureDetails[keyOf(video.path, stamp)]

    /** Extra collage moment, kept behind the same limit as ordinary thumbnail work. */
    suspend fun momentFor(video: File, target: File, start: String): File? {
        if (target.isFile && target.length() > 0) return target
        return gate.withPermit {
            val made = withContext(Dispatchers.IO) { ThumbnailExtractor.extractDetailed(video, target, start = start) }
            target.takeIf { made.success && it.isFile && it.length() > 0 }
        }
    }

    /** How many of [videos] have a picture, still wait for one, or could not get one. Blocking. */
    data class Stats(val ready: Int, val waiting: Int, val failed: List<File>)

    fun stats(videos: List<Pair<File, Long>>): Stats {
        var ready = 0
        val bad = ArrayList<File>()
        for ((video, stamp) in videos) {
            val key = keyOf(video.path, stamp)
            when {
                File(directory, "$key.jpg").let { it.isFile && it.length() > 0 } -> ready++
                key in failed -> bad += video
            }
        }
        return Stats(ready, videos.size - ready - bad.size, bad)
    }

    /** True while the background run is making pictures. */
    val working: Boolean get() = backgroundJob?.isActive == true

    /** Lets videos whose picture failed be tried again by the next run. */
    fun retryFailed() { failed.clear(); retryAfter.clear() }

    /** Stops the background run. */
    fun stop() {
        backgroundJob?.cancel()
    }

    /** Deletes every stored picture; they are made again when shown. Returns how many were deleted. */
    fun clearCache(): Int {
        stop()
        CardPictures.memory.clear(CardPictures.THUMBNAILS)
        failed.clear()
        failureDetails.clear()
        retryAfter.clear()
        return directory.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.count { it.delete() } ?: 0
    }

    /**
     * Makes the missing pictures of [videos] in the background, one at a time and only while no card on screen is
     * waiting and no video plays. A newer call replaces the previous run.
     */
    fun prefetch(videos: List<Pair<File, Long>>) {
        backgroundJob?.cancel()
        backgroundJob = background.launch {
            val startedNanos = System.nanoTime()
            val madeBefore = made.get()
            PerfMonitor.mark("thumbnails started", "videos" to videos.size)
            try { prefetchPasses(videos) }
            finally {
                PerfMonitor.mark("thumbnails stopped", "seconds" to (System.nanoTime() - startedNanos) / 1e9,
                    "made" to made.get() - madeBefore, "finished" to if (isActive) 1 else 0)
            }
        }
    }

    private suspend fun prefetchPasses(videos: List<Pair<File, Long>>) {
        run {
            repeat(2) { pass ->
                if (pass > 0) kotlinx.coroutines.delay(RETRY_DELAY_MS)
                for ((video, stamp) in videos) {
                    val key = keyOf(video.path, stamp)
                    if (key in failed || (retryAfter[key] ?: 0L) > System.currentTimeMillis()) continue
                    val target = File(directory, "$key.jpg")
                    if (target.isFile && target.length() > 0) continue
                    while (waiting.get() > 0 || playing) kotlinx.coroutines.delay(400)
                    gate.withPermit { withContext(Dispatchers.IO) { generate(key, video, target) } }
                }
                if (retryAfter.isEmpty()) return@run
            }
        }
    }

    private const val RETRY_DELAY_MS = 120_000L
}
