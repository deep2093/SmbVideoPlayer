package com.skywing.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.IntByReference
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
        fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: DoubleByReference): Int
        fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: IntByReference): Int
        fun mpv_command(ctx: Pointer, args: Array<String?>): Int
        fun mpv_terminate_destroy(ctx: Pointer)
    }

    private var api: Api? = null
    private var instance: Pointer? = null
    private var surface: Long? = null
    private var closed = false
    var lastError: String? = null
        private set

    /** Invoke only once the AWT Canvas has a native peer (isDisplayable). */
    @Synchronized
    fun attach(canvas: Canvas): Boolean {
        if (closed) return false
        if (!canvas.isDisplayable) {
            lastError = "Video surface is not ready"
            return false
        }
        return try {
            val handle = Native.getComponentPointer(canvas)
            require(handle != null && Pointer.nativeValue(handle) != 0L) { "No native window handle" }
            val hwnd = Pointer.nativeValue(handle)
            if (instance != null) {
                check(surface == hwnd) { "Video HWND changed while mpv is attached" }
                return true
            }
            val resources = System.getProperty("compose.application.resources.dir")
            val packaged = resources?.let { File(it, "libmpv-2.dll") }
            val native = if (packaged?.isFile == true)
                Native.load(packaged.absolutePath, Api::class.java)
            else Native.load("mpv-2", Api::class.java)
            val context = native.mpv_create() ?: error("mpv_create failed")
            // Set before mpv_initialize. mpv creates its own video child in the Canvas HWND.
            fun set(name: String, value: String) {
                check(native.mpv_set_option_string(context, name, value) >= 0) { "mpv option: $name" }
            }
            try {
                // SkyWing owns keyboard/fullscreen/quit. Native mpv defaults must
                // not independently fullscreen or close its embedded child window.
                set("config", "no")
                set("input-default-bindings", "no")
                set("input-vo-keyboard", "no")
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
            surface = hwnd
            lastError = null
            true
        } catch (failure: Throwable) {
            lastError = "Embedded player unavailable: ${failure.message ?: failure.javaClass.simpleName}"
            false
        }
    }

    @Synchronized
    private fun command(vararg args: String): Boolean {
        val native = api ?: return false
        val context = instance ?: return false
        return native.mpv_command(context, (args.toList() + listOf(null)).toTypedArray()) >= 0
    }

    // This measures only how quickly mpv accepts the load request, not first-frame time.
    var lastLoadCommandMillis: Long = -1
        private set

    fun open(file: File): Boolean {
        if (!file.isFile) return false
        val start = System.nanoTime()
        val accepted = command("loadfile", file.absolutePath, "replace")
        lastLoadCommandMillis = (System.nanoTime() - start) / 1_000_000
        return accepted
    }
    // MPV_FORMAT_DOUBLE = 5. Null is returned while no file is loaded.
    @Synchronized
    private fun numberProperty(name: String): Double? {
        val native = api ?: return null
        val ctx = instance ?: return null
        val value = DoubleByReference()
        return if (native.mpv_get_property(ctx, name, 5, value) >= 0) {
            value.value.takeIf { it.isFinite() }
        } else null
    }
    // MPV_FORMAT_FLAG = 3. True or false once the player exists, otherwise null.
    @Synchronized
    private fun flagProperty(name: String): Boolean? {
        val native = api ?: return null
        val ctx = instance ?: return null
        val value = IntByReference()
        return if (native.mpv_get_property(ctx, name, 3, value) >= 0) value.value != 0 else null
    }
    fun isPaused(): Boolean? = flagProperty("pause")
    fun isMuted(): Boolean? = flagProperty("mute")
    fun playbackPosition(): Double? = numberProperty("time-pos")
    fun playbackDuration(): Double? = numberProperty("duration")
    fun seekAbsolute(seconds: Double): Boolean =
        seconds.isFinite() && command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute")

    fun togglePause(): Boolean = command("cycle", "pause")
    fun pause(): Boolean = command("set", "pause", "yes")
    fun seek(seconds: Int): Boolean = command("seek", seconds.toString(), "relative")
    fun stop(): Boolean = command("stop")
    fun volumeUp(): Boolean = command("add", "volume", "5")
    fun volumeDown(): Boolean = command("add", "volume", "-5")
    fun toggleMute(): Boolean = command("cycle", "mute")
    fun nextAudioTrack(): Boolean = command("cycle", "aid")
    fun nextSubtitleTrack(): Boolean = command("cycle", "sid")
    fun subtitleOff(): Boolean = command("set", "sid", "no")
    fun seekPercent(percent: Int): Boolean = command("seek", percent.coerceIn(0, 100).toString(), "absolute-percent")
    @Synchronized
    override fun close() {
        closed = true
        val native = api
        val context = instance
        instance = null
        api = null
        surface = null
        if (native != null && context != null) native.mpv_terminate_destroy(context)
    }
}
