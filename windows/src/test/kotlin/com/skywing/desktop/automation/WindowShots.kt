package com.skywing.desktop.automation

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.math.roundToInt

/**
 * The M3 runner's hold on SkyWing's main window from outside the app: its size in dp, its place on the screens (plan
 * 6.8), pictures of what it shows, and the two back inputs the runner posts (Esc and the mouse back button). Everything
 * is in physical pixels, so the runner makes itself per-monitor DPI aware first ([perMonitorAware]).
 *
 * Windows' own calls only: SetWindowPos and ShowWindow move and size the window as a person dragging it would, and the
 * pictures are a copy of the screen where the window is (BitBlt), so they show what a person sees, covers included.
 */
internal object WindowShots {
    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun interface MonitorProc : Callback { fun invoke(monitor: Pointer, dc: Pointer?, rect: Pointer?, data: Pointer?): Boolean }
        fun SetProcessDpiAwarenessContext(context: Pointer): Boolean
        fun GetDpiForWindow(hwnd: Pointer): Int
        fun GetClientRect(hwnd: Pointer, rect: Win32.Rect): Boolean
        fun ClientToScreen(hwnd: Pointer, point: Pointer): Boolean
        fun ScreenToClient(hwnd: Pointer, point: Pointer): Boolean
        /** POINT by value: 8 bytes, which x64 passes as one 64-bit integer (y in the high half). */
        fun ChildWindowFromPointEx(hwnd: Pointer, point: Long, flags: Int): Pointer?
        fun GetWindowRect(hwnd: Pointer, rect: Win32.Rect): Boolean
        fun IsZoomed(hwnd: Pointer): Boolean
        fun ShowWindow(hwnd: Pointer, command: Int): Boolean
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: Win32.MonitorInfo): Boolean
        fun EnumDisplayMonitors(dc: Pointer?, clip: Pointer?, proc: MonitorProc, data: Pointer?): Boolean
        fun GetSystemMetrics(index: Int): Int
        fun PostMessageW(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Boolean
        fun GetDC(hwnd: Pointer?): Pointer?
        fun ReleaseDC(hwnd: Pointer?, dc: Pointer): Int
    }

    @Suppress("FunctionName")
    private interface Gdi32 : StdCallLibrary {
        fun CreateCompatibleDC(dc: Pointer?): Pointer?
        fun CreateCompatibleBitmap(dc: Pointer, width: Int, height: Int): Pointer?
        fun SelectObject(dc: Pointer, gdiObject: Pointer): Pointer?
        fun BitBlt(dest: Pointer, x: Int, y: Int, width: Int, height: Int, source: Pointer, sx: Int, sy: Int, rop: Int): Boolean
        fun GetDIBits(dc: Pointer, bitmap: Pointer, start: Int, lines: Int, bits: Pointer, info: Pointer, usage: Int): Int
        fun DeleteObject(gdiObject: Pointer): Boolean
        fun DeleteDC(dc: Pointer): Boolean
    }

    @Suppress("FunctionName")
    private interface Dwm : StdCallLibrary {
        fun DwmGetWindowAttribute(hwnd: Pointer, attribute: Int, value: Win32.Rect, size: Int): Int
    }

    private val user32: User32 by lazy { Native.load("user32", User32::class.java) }
    private val gdi32: Gdi32 by lazy { Native.load("gdi32", Gdi32::class.java) }
    private val dwm: Dwm? by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }

    private const val SW_MAXIMIZE = 3
    private const val SW_RESTORE = 9
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val DWMWA_EXTENDED_FRAME_BOUNDS = 9
    private const val SRCCOPY = 0x00CC0020
    private const val CAPTUREBLT = 0x40000000
    private const val WM_XBUTTONDOWN = 0x020B
    private const val CWP_SKIPINVISIBLE = 0x0001
    private const val CWP_SKIPDISABLED = 0x0002
    private const val CWP_SKIPTRANSPARENT = 0x0004
    private const val WM_XBUTTONUP = 0x020C
    private const val MK_XBUTTON1 = 0x0020
    private const val XBUTTON1 = 0x0001
    private const val VK_ESCAPE = 0x1B
    private const val SCAN_ESCAPE = 0x01

    /** Before any window exists: per-monitor (v2) DPI aware, so every rectangle below is in physical pixels. */
    fun perMonitorAware() {
        runCatching { user32.SetProcessDpiAwarenessContext(Pointer(-4L)) }
    }

    fun scale(hwnd: Long): Double = (runCatching { user32.GetDpiForWindow(Pointer(hwnd)) }.getOrDefault(0).takeIf { it > 0 } ?: 96) / 96.0

    fun isMaximised(hwnd: Long): Boolean = user32.IsZoomed(Pointer(hwnd))

    fun restore(hwnd: Long) { user32.ShowWindow(Pointer(hwnd), SW_RESTORE) }

    fun maximise(hwnd: Long) { user32.ShowWindow(Pointer(hwnd), SW_MAXIMIZE) }

    /** The window as a person sees it, without the invisible resize borders (DWM's extended frame), else its rectangle. */
    fun visible(hwnd: Long): Win32.Bounds {
        val rect = Win32.Rect()
        val api = dwm
        if (api != null && api.DwmGetWindowAttribute(Pointer(hwnd), DWMWA_EXTENDED_FRAME_BOUNDS, rect, rect.size()) == 0) return rect.bounds()
        check(user32.GetWindowRect(Pointer(hwnd), rect)) { "GetWindowRect failed" }
        return rect.bounds()
    }

    private fun outer(hwnd: Long): Win32.Bounds = Win32.Rect().also { check(user32.GetWindowRect(Pointer(hwnd), it)) }.bounds()

    /** The window's content in screen pixels. */
    fun client(hwnd: Long): Win32.Bounds {
        val rect = Win32.Rect()
        check(user32.GetClientRect(Pointer(hwnd), rect)) { "GetClientRect failed" }
        val point = Memory(8).apply { setInt(0, 0); setInt(4, 0) }
        check(user32.ClientToScreen(Pointer(hwnd), point)) { "ClientToScreen failed" }
        val x = point.getInt(0)
        val y = point.getInt(4)
        return Win32.Bounds(x, y, x + rect.right - rect.left, y + rect.bottom - rect.top)
    }

    /** The work area (the screen without the taskbar) of the monitor the window is on. */
    fun workArea(hwnd: Long): Win32.Bounds {
        val monitor = checkNotNull(user32.MonitorFromWindow(Pointer(hwnd), 2)) { "no monitor for window" }
        return Win32.MonitorInfo().also { check(user32.GetMonitorInfoW(monitor, it)) }.work.bounds()
    }

    /** Every monitor's work area. */
    fun workAreas(): List<Win32.Bounds> {
        val found = mutableListOf<Win32.Bounds>()
        val proc = User32.MonitorProc { monitor, _, _, _ ->
            Win32.MonitorInfo().takeIf { user32.GetMonitorInfoW(monitor, it) }?.let { found += it.work.bounds() }
            true
        }
        user32.EnumDisplayMonitors(null, null, proc, null)
        return found
    }

    /** The virtual screen: every monitor together. */
    fun virtualScreen(): Win32.Bounds {
        val x = user32.GetSystemMetrics(76)
        val y = user32.GetSystemMetrics(77)
        return Win32.Bounds(x, y, x + user32.GetSystemMetrics(78), y + user32.GetSystemMetrics(79))
    }

    /** Moves and sizes the window's outer rectangle, in screen pixels, without bringing it forward. */
    fun move(hwnd: Long, left: Int, top: Int, width: Int, height: Int) {
        check(user32.SetWindowPos(Pointer(hwnd), null, left, top, width, height, SWP_NOZORDER or SWP_NOACTIVATE)) { "SetWindowPos failed" }
    }

    /**
     * Makes the window's content [widthDp] × [heightDp] dp, centred in its monitor's work area. Restores a maximised
     * window first. Two passes, as the frame's thickness is known only once the window has its new size.
     */
    fun setContentSizeDp(hwnd: Long, widthDp: Int, heightDp: Int) {
        if (isMaximised(hwnd)) {
            restore(hwnd)
            Thread.sleep(400)
        }
        repeat(2) {
            val scale = scale(hwnd)
            val work = workArea(hwnd)
            val frame = outer(hwnd)
            val content = client(hwnd)
            val width = (widthDp * scale).roundToInt() + (frame.right - frame.left) - (content.right - content.left)
            val height = (heightDp * scale).roundToInt() + (frame.bottom - frame.top) - (content.bottom - content.top)
            val left = work.left + ((work.right - work.left) - width) / 2
            val top = work.top + maxOf(0, ((work.bottom - work.top) - height) / 2)
            move(hwnd, left, top, width, height)
            Thread.sleep(250)
        }
    }

    /** The window's placement in dp, against the work area of its monitor (6.8). */
    fun placement(hwnd: Long): Placement {
        val scale = scale(hwnd)
        val window = visible(hwnd)
        val work = workArea(hwnd)
        return Placement(
            maximised = isMaximised(hwnd),
            left = window.left / scale, top = window.top / scale,
            width = (window.right - window.left) / scale, height = (window.bottom - window.top) / scale,
            workLeft = work.left / scale, workTop = work.top / scale,
            workWidth = (work.right - work.left) / scale, workHeight = (work.bottom - work.top) / scale,
        )
    }

    /** How much of the title bar's width, in dp, lies on one monitor's work area (the best one). */
    fun titleOnScreenDp(hwnd: Long): Double {
        val scale = scale(hwnd)
        val window = visible(hwnd)
        val titleTop = window.top
        val titleBottom = window.top + (32 * scale).roundToInt()
        return workAreas().maxOfOrNull { work ->
            val rows = minOf(titleBottom, work.bottom) - maxOf(titleTop, work.top)
            val columns = minOf(window.right, work.right) - maxOf(window.left, work.left)
            if (rows > 0 && columns > 0) columns / scale else 0.0
        } ?: 0.0
    }

    /** Points over the window's content where another program's window would hide part of a picture. */
    fun contentPoints(hwnd: Long): List<Pair<Int, Int>> {
        val c = client(hwnd)
        return listOf(1, 3, 5).flatMap { i -> listOf(1, 3, 5).map { j -> c.left + (c.right - c.left) * i / 6 to c.top + (c.bottom - c.top) * j / 6 } }
    }

    /** What the screen shows over the window's content, as a person sees it. */
    fun capture(hwnd: Long): BufferedImage {
        val c = client(hwnd)
        val width = c.right - c.left
        val height = c.bottom - c.top
        require(width > 0 && height > 0) { "The window has no content to picture" }
        val screen = checkNotNull(user32.GetDC(null)) { "GetDC failed" }
        val memory = checkNotNull(gdi32.CreateCompatibleDC(screen)) { "CreateCompatibleDC failed" }
        val bitmap = checkNotNull(gdi32.CreateCompatibleBitmap(screen, width, height)) { "CreateCompatibleBitmap failed" }
        try {
            val old = gdi32.SelectObject(memory, bitmap)
            check(gdi32.BitBlt(memory, 0, 0, width, height, screen, c.left, c.top, SRCCOPY or CAPTUREBLT)) { "BitBlt failed" }
            // GetDIBits wants the bitmap out of every device context.
            if (old != null) gdi32.SelectObject(memory, old)
            val info = Memory(44).apply {
                clear()
                setInt(0, 40) // BITMAPINFOHEADER.biSize
                setInt(4, width)
                setInt(8, -height) // top-down rows
                setShort(12, 1) // planes
                setShort(14, 32) // bits per pixel
            }
            val pixels = Memory(width.toLong() * height * 4)
            check(gdi32.GetDIBits(memory, bitmap, 0, height, pixels, info, 0) == height) { "GetDIBits failed" }
            return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
                setRGB(0, 0, width, height, pixels.getIntArray(0, width * height), 0, width)
            }
        } finally {
            gdi32.DeleteObject(bitmap)
            gdi32.DeleteDC(memory)
            user32.ReleaseDC(null, screen)
        }
    }

    fun save(image: BufferedImage, file: Path) {
        file.parent.createDirectories()
        check(ImageIO.write(image, "png", file.toFile())) { "No PNG writer" }
    }

    /** Esc, posted to the window: it reaches SkyWing only while SkyWing is the active window. */
    fun postEscape(hwnd: Long) = Win32.postKey(hwnd, VK_ESCAPE, SCAN_ESCAPE)

    /**
     * The mouse back button (XButton1) pressed and let go at the content's centre. A real mouse sends it to the window
     * under the pointer, which is the content's own child window, not the frame, so it is posted there.
     */
    fun postMouseBack(hwnd: Long) {
        val c = client(hwnd)
        val x = (c.left + c.right) / 2
        val y = (c.top + c.bottom) / 2
        val target = windowUnder(Pointer(hwnd), x, y)
        val point = screenToClient(target, x, y)
        val lParam = (point.second.toLong() shl 16) or (point.first.toLong() and 0xFFFF)
        user32.PostMessageW(target, WM_XBUTTONDOWN, ((XBUTTON1 shl 16) or MK_XBUTTON1).toLong(), lParam)
        Thread.sleep(60)
        user32.PostMessageW(target, WM_XBUTTONUP, (XBUTTON1 shl 16).toLong(), lParam)
    }

    /** The deepest shown child of [top] at the screen point, as Windows picks the window a click goes to. */
    private fun windowUnder(top: Pointer, x: Int, y: Int): Pointer {
        var window = top
        while (true) {
            val (cx, cy) = screenToClient(window, x, y)
            val packed = (cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)
            val child = user32.ChildWindowFromPointEx(window, packed, CWP_SKIPINVISIBLE or CWP_SKIPDISABLED or CWP_SKIPTRANSPARENT)
            if (child == null || child == window) return window
            window = child
        }
    }

    private fun screenToClient(window: Pointer, x: Int, y: Int): Pair<Int, Int> {
        val point = Memory(8).apply { setInt(0, x); setInt(4, y) }
        check(user32.ScreenToClient(window, point)) { "ScreenToClient failed" }
        return point.getInt(0) to point.getInt(4)
    }
}
