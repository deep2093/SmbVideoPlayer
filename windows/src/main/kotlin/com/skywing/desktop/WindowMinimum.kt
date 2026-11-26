package com.skywing.desktop

import com.skywing.desktop.playback.DisplayChanges
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Dimension
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Keeps the main window at least 640 × 480 dp at any Windows scaling (plan 6.8 rule 5). Java gives Windows its
 * minimum in raw screen pixels (JDK-8221452), so at 150 % the window could be dragged to about 415 × 314 dp. The
 * window's own minimum-size message (WM_GETMINMAXINFO, the one change M3 brief 5.6 rule 7 allows) is answered here
 * instead: Java answers first, then the minimum is raised to 640 × 480 dp at the window's scale. Nothing here moves
 * or sizes the window; any failure leaves Java's answer as it was.
 */
internal object WindowMinimum {
    private interface User32 : StdCallLibrary {
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Pointer?
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Pointer): Pointer?
        fun CallWindowProcW(previous: Pointer, hwnd: Pointer, message: Int, wParam: Long, lParam: Long): Long
        fun DefWindowProcW(hwnd: Pointer, message: Int, wParam: Long, lParam: Long): Long
        fun GetDpiForWindow(hwnd: Pointer): Int
        fun GetWindowRect(hwnd: Pointer, rect: Pointer): Boolean
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Boolean
    }

    private interface Dwm : StdCallLibrary {
        fun DwmGetWindowAttribute(hwnd: Pointer, attribute: Int, value: Pointer, size: Int): Int
    }

    private const val GWLP_WNDPROC = -4
    private const val WM_GETMINMAXINFO = 0x0024
    private const val WM_NCDESTROY = 0x0082
    private const val MONITOR_DEFAULTTONEAREST = 2
    private const val DWMWA_EXTENDED_FRAME_BOUNDS = 9

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val user32: User32? by lazy { if (windows) runCatching { Native.load("user32", User32::class.java) }.getOrNull() else null }
    private val dwm: Dwm? by lazy { if (windows) runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() else null }

    /** Each hooked window's previous window procedure, by HWND. */
    private val previous = ConcurrentHashMap<Long, Pointer>()

    // Held for the life of the process: JNA frees a callback that is no longer referenced.
    private val proc = DisplayChanges.WindowProc { hwnd, message, wParam, lParam -> handle(hwnd, message, wParam, lParam) }
    private val procPointer: Pointer by lazy { CallbackReference.getFunctionPointer(proc) }

    /**
     * The smallest outer window Windows should allow, in screen pixels: the visible window at 640 × 480 dp for
     * [scale], rounded up, plus the invisible resize borders ([borderWidth], [borderHeight]), never larger than the
     * work area ([workWidth], [workHeight]), so maximising and full screen are never blocked.
     */
    fun trackSize(scale: Double, borderWidth: Int, borderHeight: Int, workWidth: Int, workHeight: Int): Dimension =
        Dimension(
            minOf(ceil(WindowSizing.MIN_WIDTH * scale).toInt() + borderWidth, workWidth),
            minOf(ceil(WindowSizing.MIN_HEIGHT * scale).toInt() + borderHeight, workHeight),
        )

    /**
     * Hooks [window]'s window procedure once. True when the hook is in place, also when it already was; false, with
     * nothing changed, off Windows or when the window has no HWND yet.
     */
    @Synchronized
    fun install(window: Window): Boolean {
        val api = user32 ?: return false
        val hwnd = hwndOf(window) ?: return false
        val key = Pointer.nativeValue(hwnd)
        if (previous.containsKey(key)) return true
        // Recorded before the swap: Windows can send the first message before SetWindowLongPtrW returns.
        previous[key] = api.GetWindowLongPtrW(hwnd, GWLP_WNDPROC) ?: return false
        val replaced = api.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, procPointer)
        if (replaced == null) {
            previous.remove(key)
            return false
        }
        previous[key] = replaced
        return true
    }

    /** True while [window]'s window procedure is hooked. */
    internal fun installed(window: Window): Boolean = hwndOf(window)?.let { previous.containsKey(Pointer.nativeValue(it)) } == true

    private fun hwndOf(window: Window): Pointer? =
        runCatching { Native.getWindowPointer(window) }.getOrNull()?.takeIf { Pointer.nativeValue(it) != 0L }

    private fun handle(hwnd: Pointer, message: Int, wParam: Long, lParam: Long): Long {
        val api = user32 ?: return 0L
        val key = Pointer.nativeValue(hwnd)
        val before = previous[key] ?: return api.DefWindowProcW(hwnd, message, wParam, lParam)
        if (message == WM_NCDESTROY) {
            // The window is going: give it back its own procedure for the last message.
            runCatching { api.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, before) }
            previous.remove(key)
            return api.CallWindowProcW(before, hwnd, message, wParam, lParam)
        }
        val result = api.CallWindowProcW(before, hwnd, message, wParam, lParam)
        if (message == WM_GETMINMAXINFO && lParam != 0L) runCatching { raise(api, hwnd, Pointer(lParam)) }
        return result
    }

    /** Raises MINMAXINFO.ptMinTrackSize (offset 24) to [trackSize] for the window's screen, keeping Java's if larger. */
    private fun raise(api: User32, hwnd: Pointer, info: Pointer) {
        val dpi = api.GetDpiForWindow(hwnd).takeIf { it > 0 } ?: return
        val monitor = api.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: return
        // MONITORINFO is 40 bytes: cbSize, rcMonitor (4), rcWork (20), dwFlags.
        val work = Memory(40).use { m ->
            m.clear()
            m.setInt(0, 40)
            if (!api.GetMonitorInfoW(monitor, m)) return
            Dimension(m.getInt(28) - m.getInt(20), m.getInt(32) - m.getInt(24))
        }
        val borders = borders(api, hwnd)
        val wanted = trackSize(dpi / 96.0, borders.width, borders.height, work.width, work.height)
        info.setInt(24, maxOf(info.getInt(24), wanted.width))
        info.setInt(28, maxOf(info.getInt(28), wanted.height))
    }

    /** The invisible resize borders: the outer window less its visible frame. None when DWM can't say. */
    private fun borders(api: User32, hwnd: Pointer): Dimension = Memory(32).use { m ->
        m.clear()
        val visible = m.share(16)
        if (!api.GetWindowRect(hwnd, m)) return Dimension()
        if (dwm?.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, visible, 16) != 0) return Dimension()
        Dimension(
            ((m.getInt(8) - m.getInt(0)) - (visible.getInt(8) - visible.getInt(0))).coerceAtLeast(0),
            ((m.getInt(12) - m.getInt(4)) - (visible.getInt(12) - visible.getInt(4))).coerceAtLeast(0),
        )
    }
}
