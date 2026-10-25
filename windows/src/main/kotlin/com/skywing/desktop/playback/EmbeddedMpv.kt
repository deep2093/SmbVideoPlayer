package com.skywing.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Canvas
import java.io.Closeable
import java.io.File

/**
 * Experimental native Windows x64 libmpv host.
 * Uses mpv's Windows --wid child-window embedding; GPU-render API integration follows later.
 * Requires libmpv-2.dll and its dependencies to be bundled in the installed application.
 */
class EmbeddedMpv : Closeable {
    private interface Api : Library {
        fun mpv_create(): Pointer?
        fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_command(ctx: Pointer, args: Array<String?>): Int
        fun mpv_terminate_destroy(ctx: Pointer)
    }

    private var api: Api? = null
    private var instance: Pointer? = null
    var lastError: String? = null
        private set

    /** Invoke only once the AWT Canvas has a native peer (isDisplayable). */
    fun attach(canvas: Canvas): Boolean {
        if (instance != null) return true
        if (!canvas.isDisplayable) {
            lastError = "Video surface is not ready"
            return false
        }
        return try {
            val native = Native.load("mpv-2", Api::class.java)
            val context = native.mpv_create() ?: error("mpv_create failed")
            val handle = Native.getComponentPointer(canvas)
            require(handle != null && Pointer.nativeValue(handle) != 0L) { "No native window handle" }
            // Set before mpv_initialize. mpv creates its own video child in the Canvas HWND.
            fun set(name: String, value: String) {
                check(native.mpv_set_option_string(context, name, value) >= 0) { "mpv option: $name" }
            }
            try {
                set("wid", java.lang.Long.toUnsignedString(Pointer.nativeValue(handle)))
                set("force-window", "yes")
                set("keep-open", "yes")
                set("hwdec", "auto-safe")
                check(native.mpv_initialize(context) >= 0) { "mpv_initialize failed" }
            } catch (failure: Throwable) {
                native.mpv_terminate_destroy(context)
                throw failure
            }
            api = native
            instance = context
            lastError = null
            true
        } catch (failure: Throwable) {
            lastError = "Embedded player unavailable: ${failure.message ?: failure.javaClass.simpleName}"
            false
        }
    }

    private fun command(vararg args: String): Boolean {
        val native = api ?: return false
        val context = instance ?: return false
        return native.mpv_command(context, (args.toList() + listOf(null)).toTypedArray()) >= 0
    }

    fun open(file: File): Boolean = file.isFile && command("loadfile", file.absolutePath, "replace")
    fun togglePause(): Boolean = command("cycle", "pause")
    fun seek(seconds: Int): Boolean = command("seek", seconds.toString(), "relative")
    fun stop(): Boolean = command("stop")
    override fun close() {
        val native = api
        val context = instance
        instance = null
        api = null
        if (native != null && context != null) native.mpv_terminate_destroy(context)
    }
}
