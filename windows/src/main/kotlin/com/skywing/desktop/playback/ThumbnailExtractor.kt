package com.skywing.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Grabs one picture from a video with a short-lived, windowless libmpv (the same DLL that plays videos),
 * so cards can show a real thumbnail like the Android app. No extra program is needed.
 */
internal object ThumbnailExtractor {
    private interface Api : Library {
        fun mpv_create(): Pointer?
        fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_command(ctx: Pointer, args: Array<String?>): Int
        fun mpv_get_property(ctx: Pointer, name: String, format: Int, value: DoubleByReference): Int
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
    data class Result(val success: Boolean, val reason: String, val elapsedMs: Long, val timedOut: Boolean = false)

    fun extract(video: File, target: File, start: String = "10%", exact: Boolean = false, width: Int = 480): Boolean =
        extractDetailed(video, target, start, exact, width).success

    fun extractDetailed(video: File, target: File, start: String = "10%", exact: Boolean = false, width: Int = 480, timeoutMs: Long = 45_000): Result {
        val begun = System.nanoTime()
        fun result(success: Boolean, reason: String, timedOut: Boolean = false) =
            Result(success, reason, (System.nanoTime() - begun) / 1_000_000, timedOut)
        val native = api ?: return result(false, "libmpv-2.dll could not be loaded")
        if (!video.isFile) return result(false, "Video file is unavailable")
        val work = runCatching { Files.createTempDirectory("skywing-thumb").toFile() }.getOrNull()
            ?: return result(false, "Could not create temporary thumbnail folder")
        var context: Pointer? = null
        var failure = "mpv produced no JPEG"
        var timedOut = false
        try {
            val ctx: Pointer = native.mpv_create() ?: throw IllegalStateException("mpv_create failed")
            context = ctx
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
            set("vo", "image")
            set("vo-image-format", "jpg")
            set("vo-image-jpeg-quality", "85")
            set("vo-image-outdir", work.absolutePath)
            set("ao", "null")
            set("aid", "no")
            set("sid", "no")
            set("hwdec", "no")
            set("frames", "1")
            set("start", start)
            // Like the Android app: jump to the nearest key frame instead of decoding up to the exact moment, and
            // read only what the decoder needs (no read-ahead), so a network file costs a few small reads.
            set("hr-seek", if (exact) "yes" else "no")
            set("cache", "no")
            set("demuxer-readahead-secs", "0")
            set("demuxer-max-bytes", "4MiB")
            set("demuxer-max-back-bytes", "0")
            set("vd-lavc-skiploopfilter", "all")
            set("vd-lavc-fast", "yes")
            set("vf", "lavfi=[scale=${width.coerceIn(64, 1920)}:-2]")
            val initialized = native.mpv_initialize(ctx)
            if (initialized < 0) throw IllegalStateException("mpv_initialize: ${error(initialized)}")
            val loaded = native.mpv_command(ctx, arrayOf("loadfile", video.absolutePath, "replace", null))
            if (loaded < 0) throw IllegalStateException("mpv loadfile: ${error(loaded)}")
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val event = native.mpv_wait_event(ctx, 0.25) ?: continue
                val id = event.getInt(0)
                if (id == EVENT_END_FILE || id == EVENT_SHUTDOWN) {
                    val code = event.getInt(4)
                    if (code < 0) failure = "mpv playback: ${error(code)}"
                    break
                }
            }
            timedOut = System.nanoTime() >= deadline
            if (timedOut) failure = "Timed out after ${timeoutMs / 1000}s"
        } catch (e: Throwable) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            val handle = context
            if (handle != null) runCatching { native.mpv_terminate_destroy(handle) }
        }
        return try {
            val picture = work.listFiles()?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                ?.minByOrNull { it.name }
            if (picture != null && picture.length() > 0) {
                target.parentFile?.mkdirs()
                Files.move(picture.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                result(true, "ok")
            } else result(false, failure, timedOut)
        } catch (e: Exception) {
            result(false, "Could not save JPEG: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }
}
