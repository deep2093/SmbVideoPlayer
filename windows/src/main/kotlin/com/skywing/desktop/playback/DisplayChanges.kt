package com.skywing.desktop.playback

import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.atomic.AtomicLong

/**
 * Hears WM_DISPLAYCHANGE (plan 5.4), so screen state is read again only when Windows says the screens changed.
 * Windows broadcasts it to top-level windows only, not to message-only ones, so a hidden tool window on its own
 * thread receives it. The window is never shown, places nothing and lasts as long as the process.
 */
internal object DisplayChanges {
    private interface User32 : StdCallLibrary {
        fun RegisterClassExW(windowClass: Pointer): Short
        fun CreateWindowExW(exStyle: Int, className: WString, title: WString, style: Int, x: Int, y: Int, width: Int,
                            height: Int, parent: Pointer?, menu: Pointer?, instance: Pointer?, param: Pointer?): Pointer?
        fun DefWindowProcW(window: Pointer, message: Int, wParam: Long, lParam: Long): Long
        fun GetMessageW(message: Pointer, window: Pointer?, first: Int, last: Int): Int
        fun TranslateMessage(message: Pointer): Boolean
        fun DispatchMessageW(message: Pointer): Long
    }

    private interface Kernel32 : StdCallLibrary {
        fun GetModuleHandleW(name: WString?): Pointer?
    }

    /** WNDPROC on x64: WPARAM, LPARAM and LRESULT are 64-bit. */
    internal fun interface WindowProc : StdCallLibrary.StdCallCallback {
        fun callback(window: Pointer, message: Int, wParam: Long, lParam: Long): Long
    }

    private const val WM_DISPLAYCHANGE = 0x007E
    private const val WS_POPUP = 0x80000000.toInt()
    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val CLASS_NAME = "SkyWingDisplayChanges"

    private val user32: User32? by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }
    private val changes = AtomicLong()
    private var started = false
    // Held for the life of the window: JNA frees a callback that is no longer referenced.
    private val proc = WindowProc { window, message, wParam, lParam ->
        if (message == WM_DISPLAYCHANGE) changes.incrementAndGet()
        user32?.DefWindowProcW(window, message, wParam, lParam) ?: 0L
    }

    /** The hidden window once it exists; null until then or when it could not be made. */
    @Volatile var window: Pointer? = null
        private set

    /** True while Windows' display changes reach SkyWing. Without it, callers read the screens again on a timer. */
    val listening: Boolean get() = window != null

    /** How many display changes Windows has reported. Listening starts on first use. */
    val generation: Long
        get() {
            start()
            return changes.get()
        }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        Thread(::listen, "SkyWing display changes").apply { isDaemon = true }.start()
    }

    private fun listen() {
        runCatching {
            val api = user32 ?: return
            val instance = Native.load("kernel32", Kernel32::class.java).GetModuleHandleW(null)
            val className = Memory((CLASS_NAME.length + 1) * 2L).apply { setWideString(0, CLASS_NAME) }
            // WNDCLASSEXW on x64 is 80 bytes: cbSize, style, lpfnWndProc (8), extra bytes, hInstance (24), icons,
            // cursor, brush, menu name, lpszClassName (64), small icon. Windows copies it, and the name, when it
            // registers the class.
            val windowClass = Memory(80).apply {
                clear()
                setInt(0, 80)
                setPointer(8, CallbackReference.getFunctionPointer(proc))
                setPointer(24, instance)
                setPointer(64, className)
            }
            if (api.RegisterClassExW(windowClass).toInt() == 0) return
            window = api.CreateWindowExW(WS_EX_TOOLWINDOW, WString(CLASS_NAME), WString(""), WS_POPUP,
                0, 0, 0, 0, null, null, instance, null) ?: return
            Memory(64).use { message -> // MSG is 48 bytes on x64
                while (api.GetMessageW(message, null, 0, 0) > 0) {
                    api.TranslateMessage(message)
                    api.DispatchMessageW(message)
                }
            }
        }
        window = null
    }
}
