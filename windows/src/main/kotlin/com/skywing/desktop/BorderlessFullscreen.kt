package com.skywing.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * Changes the live Windows frame style without disposing the AWT window.
 * Disposing the parent window would also destroy the embedded libmpv HWND.
 */
internal object BorderlessFullscreen {
    private interface User32 : StdCallLibrary {
        fun GetWindowLongW(hwnd: Pointer, index: Int): Int
        fun SetWindowLongW(hwnd: Pointer, index: Int, value: Int): Int
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    }

    private val user32 by lazy { Native.load("user32", User32::class.java) }
    private const val GWL_STYLE = -16
    private const val WS_CAPTION = 0x00C00000
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_MINIMIZEBOX = 0x00020000
    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val WS_SYSMENU = 0x00080000
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_FRAMECHANGED = 0x0020
    private var initialStyle: Int? = null
    private const val decorationMask = WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_SYSMENU

    fun set(window: Window, enabled: Boolean) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { set(window, enabled) }
            return
        }
        if (!window.isDisplayable) return
        val hwnd = Native.getComponentPointer(window)
        if (hwnd == null || Pointer.nativeValue(hwnd) == 0L) return
        val current = user32.GetWindowLongW(hwnd, GWL_STYLE)
        if (initialStyle == null) initialStyle = current
        val updated = if (enabled) current and decorationMask.inv() else initialStyle ?: current
        if (updated != current) user32.SetWindowLongW(hwnd, GWL_STYLE, updated)
        user32.SetWindowPos(hwnd, null, 0, 0, 0, 0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED)
    }
}
