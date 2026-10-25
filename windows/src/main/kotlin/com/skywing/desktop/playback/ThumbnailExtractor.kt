package com.skywing.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
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
        fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
        fun mpv_terminate_destroy(ctx: Pointer)
    }

    private val api: Api? by lazy {
        runCatching {
            val resources = System.getProperty("compose.application.resources.dir")
            val packaged = resources?.let { File(it, "libmpv-2.dll") }
            if (packaged?.isFile == true) Native.load(packaged.absolutePath, Api::class.java)
            else Native.load("mpv-2", Api::class.java)
        }.getOrNull()
    }

    private const val EVENT_SHUTDOWN = 1
    private const val EVENT_END_FILE = 7

    /** Saves a JPEG of the video (about a tenth of the way in) to [target]. Returns true when it was written. */
    fun extract(video: File, target: File): Boolean {
        val native = api ?: return false
        if (!video.isFile) return false
        val work = runCatching { Files.createTempDirectory("skywing-thumb").toFile() }.getOrNull() ?: return false
        var context: Pointer? = null
        try {
            val ctx: Pointer = native.mpv_create() ?: return false
            context = ctx
            fun set(name: String, value: String) {
                native.mpv_set_option_string(ctx, name, value)
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
            set("start", "10%")
            set("hr-seek", "yes")
            set("vf", "lavfi=[scale=480:-2]")
            if (native.mpv_initialize(ctx) < 0) return false
            if (native.mpv_command(ctx, arrayOf("loadfile", video.absolutePath, "replace", null)) < 0) return false
            val deadline = System.nanoTime() + 25_000_000_000L
            while (System.nanoTime() < deadline) {
                val event = native.mpv_wait_event(ctx, 0.25) ?: break
                val id = event.getInt(0)
                if (id == EVENT_END_FILE || id == EVENT_SHUTDOWN) break
            }
        } catch (_: Throwable) {
            return false
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
                true
            } else false
        } catch (_: Exception) {
            false
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }
}
