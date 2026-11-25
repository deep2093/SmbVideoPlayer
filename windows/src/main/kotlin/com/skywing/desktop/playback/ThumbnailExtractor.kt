package com.skywing.desktop.playback

import com.skywing.desktop.PerfMonitor
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Grabs one picture from a video with a windowless libmpv (the same DLL that plays videos), so cards can show a real
 * thumbnail like the Android app. No extra program is needed.
 *
 * B24: each worker keeps its player for many pictures instead of starting a new one per picture, decodes on the
 * graphics card where it can (`hwdec=auto-copy`, which falls back to the processor by itself), and tone maps HDR
 * videos so their pictures are not washed out.
 */
internal object ThumbnailExtractor {
    private interface Api : Library {
        fun mpv_create(): Pointer?
        fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_command(ctx: Pointer, args: Array<String?>): Int
        fun mpv_get_property(ctx: Pointer, name: String, format: Int, value: DoubleByReference): Int
        fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
        fun mpv_free(data: Pointer)
        fun mpv_hook_add(ctx: Pointer, userdata: Long, name: String, priority: Int): Int
        fun mpv_hook_continue(ctx: Pointer, id: Long): Int
        fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
        fun mpv_error_string(error: Int): Pointer?
        fun mpv_terminate_destroy(ctx: Pointer)
    }

    private val api: Api? by lazy {
        runCatching {
            val resources = System.getProperty("compose.application.resources.dir")
            val packaged = resources?.let { File(it, "libmpv-2.dll") }
            val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
            if (packaged?.isFile == true) Native.load(packaged.absolutePath, Api::class.java, options)
            else Native.load("mpv-2", Api::class.java, options)
        }.getOrNull()
    }

    private const val EVENT_SHUTDOWN = 1
    private const val EVENT_END_FILE = 7
    private const val EVENT_HOOK = 25
    private const val END_REASON_ERROR = 4
    private const val FORMAT_DOUBLE = 5

    /** Reads the timeline length for second-by-second frame picking without starting visible playback. */
    fun durationSeconds(video: File, timeoutMs: Long = 12_000): Double? {
        val native = api ?: return null
        if (!video.isFile) return null
        val ctx = native.mpv_create() ?: return null
        try {
            fun set(name: String, value: String): Boolean = native.mpv_set_option_string(ctx, name, value) >= 0
            if (!set("config", "no") || !set("terminal", "no") || !set("load-scripts", "no") ||
                !set("ytdl", "no") || !set("vo", "null") || !set("ao", "null") ||
                !set("pause", "yes") || !set("cache", "no")) return null
            if (native.mpv_initialize(ctx) < 0) return null
            if (native.mpv_command(ctx, arrayOf("loadfile", video.absolutePath, "replace", null)) < 0) return null
            val value = DoubleByReference()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                if (native.mpv_get_property(ctx, "duration", FORMAT_DOUBLE, value) >= 0 && value.value.isFinite() && value.value > 0) {
                    return value.value
                }
                native.mpv_wait_event(ctx, 0.2)
            }
            return null
        } finally {
            native.mpv_terminate_destroy(ctx)
        }
    }

    /**
     * Saves a JPEG of the video to [target]: by default about a tenth of the way in, at the nearest key frame.
     * [start] is an mpv position ("10%" or seconds); [exact] decodes the precise frame (slower, for a frame the user
     * picks); [width] is the picture width. Returns true when it was written.
     */
    data class Result(
        val success: Boolean,
        val reason: String,
        val elapsedMs: Long,
        val timedOut: Boolean = false,
        /** True when the video is HDR and the picture went through the tone-mapping pass. */
        val toneMapped: Boolean = false,
        /** The video's length in seconds, read as the picture was made; null when it could not be read. */
        val duration: Double? = null,
    )

    fun extract(video: File, target: File, start: String = "10%", exact: Boolean = false, width: Int = 480): Boolean =
        extractDetailed(video, target, start, exact, width).success

    fun extractDetailed(video: File, target: File, start: String = "10%", exact: Boolean = false, width: Int = 480, timeoutMs: Long = 45_000): Result {
        val begun = System.nanoTime()
        var duration: Double? = null
        fun result(success: Boolean, reason: String, timedOut: Boolean = false, toneMapped: Boolean = false) =
            Result(success, reason, (System.nanoTime() - begun) / 1_000_000, timedOut, toneMapped, duration)
        if (api == null) return result(false, "libmpv-2.dll could not be loaded")
        if (!video.isFile) return result(false, "Video file is unavailable")
        val player = try {
            players.borrow()
        } catch (e: Exception) {
            return result(false, e.message ?: e.javaClass.simpleName)
        }
        var healthy = false
        try {
            val scale = "scale=${width.coerceIn(64, 1920)}:-2"
            val first = player.grab(video, start, exact, "lavfi=[$scale]", timeoutMs)
            healthy = !first.broken
            duration = first.duration
            val picture = first.picture ?: return result(false, first.failure, first.timedOut)
            target.parentFile?.mkdirs()
            Files.move(picture.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (first.gamma !in HDR_CURVES) return result(true, "ok")
            // HDR: grab the same frame again through zscale's tone mapping. The first picture stays when that fails.
            val mapped = player.grab(video, start, exact, "lavfi=[$TONE_MAP,$scale]", timeoutMs)
            healthy = !mapped.broken
            val toned = mapped.picture
                ?.takeIf { !it.readBytes().contentEquals(target.readBytes()) }
                ?: return result(true, "ok; tone mapping failed: ${mapped.failure}")
            Files.move(toned.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return result(true, "ok", toneMapped = true)
        } catch (e: Exception) {
            return result(false, "Could not save JPEG: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            players.giveBack(player, healthy)
        }
    }

    /** Transfer curves mpv reports for HDR video (video-params/gamma). */
    private val HDR_CURVES = setOf("pq", "hlg")

    /**
     * HDR to SDR for a still picture: to linear light (100 nits as reference white), BT.2020 to BT.709 colours, the
     * Hable curve, then back to BT.709. The plan's chain plus the primaries step, without which colours are oversaturated.
     */
    private const val TONE_MAP = "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709," +
        "tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p"

    /** One picture's outcome. [broken] means the player must not be used again. */
    private class Grab(val picture: File?, val failure: String, val timedOut: Boolean, val broken: Boolean, val gamma: String?,
        val duration: Double? = null)

    /** A windowless player kept for many pictures, with its own folder for the image output. */
    private class Player(private val native: Api, val ctx: Pointer, val folder: File) {
        private fun error(code: Int) = native.mpv_error_string(code)?.getString(0) ?: "error $code"

        fun grab(video: File, start: String, exact: Boolean, vf: String, timeoutMs: Long): Grab {
            folder.listFiles()?.forEach { it.delete() }
            for ((name, value) in listOf("start" to start, "hr-seek" to if (exact) "yes" else "no", "vf" to vf)) {
                val code = native.mpv_set_property_string(ctx, name, value)
                if (code < 0) return Grab(null, "mpv option $name: ${error(code)}", false, false, null)
            }
            val loaded = native.mpv_command(ctx, arrayOf("loadfile", video.absolutePath, "replace", null))
            if (loaded < 0) return Grab(null, "mpv loadfile: ${error(loaded)}", false, false, null)
            var failure = "mpv produced no JPEG"
            var gamma: String? = null
            var duration: Double? = null
            var ended = false
            var broken = false
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!ended && System.nanoTime() < deadline) {
                val event = native.mpv_wait_event(ctx, 0.25) ?: continue
                when (event.getInt(0)) {
                    EVENT_HOOK -> {
                        // on_unload: the file is about to close but its video is still set up, so its colours can be read.
                        val hook = event.getPointer(16)
                        try {
                            gamma = native.mpv_get_property_string(ctx, "video-params/gamma")?.let { text ->
                                try { text.getString(0, "UTF-8") } finally { native.mpv_free(text) }
                            }
                            duration = native.mpv_get_property_string(ctx, "duration")?.let { text ->
                                try { text.getString(0, "UTF-8").toDoubleOrNull() } finally { native.mpv_free(text) }
                            }?.takeIf { it.isFinite() && it > 0 }
                        } finally {
                            if (hook != null) native.mpv_hook_continue(ctx, hook.getLong(8))
                        }
                    }
                    EVENT_END_FILE -> {
                        ended = true
                        val end = event.getPointer(16)
                        val code = if (end != null && end.getInt(0) == END_REASON_ERROR) end.getInt(4) else event.getInt(4)
                        if (code < 0) failure = "mpv playback: ${error(code)}"
                    }
                    EVENT_SHUTDOWN -> {
                        ended = true
                        broken = true
                    }
                }
            }
            if (!ended) return Grab(null, "Timed out after ${timeoutMs / 1000}s", timedOut = true, broken = true, gamma = gamma, duration = duration)
            val picture = folder.listFiles()?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) && it.length() > 0 }
                ?.minByOrNull { it.name }
            return Grab(picture, failure, false, broken, gamma, duration)
        }

        fun close() {
            runCatching { native.mpv_terminate_destroy(ctx) }
            runCatching { folder.deleteRecursively() }
            folders -= folder
        }
    }

    private val started = AtomicInteger()
    private const val MAX_IDLE = 3
    private const val IDLE_CLOSE_MS = 30_000L
    private val closer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "SkyWing thumbnail players").apply { isDaemon = true }
    }
    private val players = KeptPlayers(MAX_IDLE, IDLE_CLOSE_MS, closer, start = { open(checkNotNull(api)) }, stop = Player::close)

    /**
     * The image folder of every open player. SkyWing can close while players wait idle (they close only after
     * [IDLE_CLOSE_MS]), so the folders left are removed at exit. The set takes no lock: the exit must never wait.
     */
    private val folders: MutableSet<File> = ConcurrentHashMap.newKeySet()

    /** How many players were started since SkyWing began. */
    val playersStarted: Int get() = started.get()

    init {
        PerfMonitor.gauge("Thumbnail players started") { started.get().toLong() }
        PerfMonitor.gauge("Thumbnail players open") { players.count.toLong() }
        Runtime.getRuntime().addShutdownHook(Thread({ folders.forEach { runCatching { it.deleteRecursively() } } }, "SkyWing thumbnail folders"))
    }

    /** Closes the players that are not making a picture now. */
    fun closeIdle() = players.closeIdle()

    private fun open(native: Api): Player {
        val folder = Files.createTempDirectory("skywing-thumb").toFile().also { folders += it }
        val ctx = native.mpv_create()
        if (ctx == null) {
            folder.deleteRecursively()
            folders -= folder
            throw IllegalStateException("mpv_create failed")
        }
        try {
            fun error(code: Int) = native.mpv_error_string(code)?.getString(0) ?: "error $code"
            fun set(name: String, value: String) {
                val code = native.mpv_set_option_string(ctx, name, value)
                if (code < 0) throw IllegalStateException("mpv option $name: ${error(code)}")
            }
            set("config", "no")
            set("terminal", "no")
            set("msg-level", "all=no")
            set("load-scripts", "no")
            set("ytdl", "no")
            set("input-default-bindings", "no")
            set("input-vo-keyboard", "no")
            set("idle", "yes")
            set("vo", "image")
            set("vo-image-format", "jpg")
            set("vo-image-jpeg-quality", "85")
            set("vo-image-outdir", folder.absolutePath)
            set("ao", "null")
            set("aid", "no")
            set("sid", "no")
            set("hwdec", "auto-copy")
            set("frames", "1")
            // Like the Android app: jump to the nearest key frame instead of decoding up to the exact moment, and
            // read only what the decoder needs (no read-ahead), so a network file costs a few small reads.
            set("cache", "no")
            set("demuxer-readahead-secs", "0")
            set("demuxer-max-bytes", "4MiB")
            set("demuxer-max-back-bytes", "0")
            set("vd-lavc-skiploopfilter", "all")
            set("vd-lavc-fast", "yes")
            val initialized = native.mpv_initialize(ctx)
            if (initialized < 0) throw IllegalStateException("mpv_initialize: ${error(initialized)}")
            val hooked = native.mpv_hook_add(ctx, 0, "on_unload", 0)
            if (hooked < 0) throw IllegalStateException("mpv hook: ${error(hooked)}")
        } catch (e: Exception) {
            runCatching { native.mpv_terminate_destroy(ctx) }
            folder.deleteRecursively()
            folders -= folder
            throw e
        }
        started.incrementAndGet()
        return Player(native, ctx, folder)
    }
}

/**
 * The players kept between pictures (B24): at most [maxIdle] wait idle, and those are closed [idleCloseMs] after the
 * last picture, so a finished run keeps no player threads or memory. Players are started and closed outside the lock:
 * one whose video hangs on a network read can take long to close, and the other workers must not wait for it.
 */
internal class KeptPlayers<P : Any>(
    private val maxIdle: Int,
    private val idleCloseMs: Long,
    private val scheduler: ScheduledExecutorService,
    private val start: () -> P,
    private val stop: (P) -> Unit,
) {
    private val idle = ArrayDeque<P>()
    /** Players making a picture now, and players being started for one. */
    private var inUse = 0
    private var closing: ScheduledFuture<*>? = null

    /** Players open now: the idle ones and those making a picture. */
    val count: Int @Synchronized get() = idle.size + inUse

    fun borrow(): P {
        val kept = synchronized(this) {
            closing?.cancel(false)
            closing = null
            inUse++
            idle.removeFirstOrNull()
        }
        if (kept != null) return kept
        return try {
            start()
        } catch (e: Exception) {
            synchronized(this) { returned() }
            throw e
        }
    }

    /** Takes [player] back; one that isn't [healthy], or one more than [maxIdle], is closed. */
    fun giveBack(player: P, healthy: Boolean) {
        val kept = synchronized(this) {
            (healthy && idle.size < maxIdle).also { keep ->
                if (keep) idle.addLast(player)
                returned()
            }
        }
        if (!kept) stop(player)
    }

    /** Closes the players that are not making a picture now. */
    fun closeIdle() {
        val players = synchronized(this) { idle.toList().also { idle.clear() } }
        players.forEach(stop)
    }

    /** One player less is in use; once none is, the idle ones are closed after [idleCloseMs]. Call with the lock. */
    private fun returned() {
        inUse--
        if (inUse == 0 && idle.isNotEmpty()) closing = scheduler.schedule({ closeIdle() }, idleCloseMs, TimeUnit.MILLISECONDS)
    }
}
