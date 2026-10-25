package com.skywing.desktop

import com.sun.jna.Native
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import javax.swing.SwingUtilities

/** One controller per live frame. Never combine with Compose/AWT exclusive fullscreen. */
internal class BorderlessFullscreen(private val window: Window) {
    private interface User32 : StdCallLibrary {
        fun GetWindowLongW(hwnd: Pointer, index: Int): Int
        fun SetWindowLongW(hwnd: Pointer, index: Int, value: Int): Int
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetWindowPlacement(hwnd: Pointer, placement: Pointer): Boolean
        fun SetWindowPlacement(hwnd: Pointer, placement: Pointer): Boolean
    }

    private data class Restore(val decorations: Int, val bounds: Rectangle, val extendedState: Int, val placement: Memory)
    private var saved: Restore? = null
    val isFullscreen: Boolean get() = saved != null

    /** Synchronous on the EDT: publish UI state only after the native transition succeeds. */
    fun set(enabled: Boolean): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (enabled == isFullscreen) return isFullscreen
        check(window.isDisplayable) { "Fullscreen requires a live window" }
        val hwnd = Native.getComponentPointer(window)
        check(hwnd != null && Pointer.nativeValue(hwnd) != 0L) { "No frame HWND" }
        if (enabled) {
            // WINDOWPLACEMENT is 44 bytes on both Win32 and Win64: UINT length,
            // flags, showCmd; two POINTs; RECT normalPosition. Keep native coordinates
            // opaque so maximized -> fullscreen -> restore retains the normal rect.
            val placement = Memory(44).apply { clear(); setInt(0, 44) }
            check(user32.GetWindowPlacement(hwnd, placement)) { "GetWindowPlacement failed" }
            val snapshot = Restore(user32.GetWindowLongW(hwnd, GWL_STYLE) and decorationMask,
                Rectangle(window.bounds), (window as? Frame)?.extendedState ?: Frame.NORMAL, placement)
            saved = snapshot
            try {
                (window as? Frame)?.extendedState = Frame.NORMAL
                style(hwnd, 0)
                // AWT uses logical coordinates. Native SetWindowPos coordinates would
                // double-scale bounds on mixed-DPI displays.
                window.bounds = window.graphicsConfiguration.bounds
                refresh(hwnd)
                showAndValidate()
            } catch (failure: Throwable) {
                try { restore(hwnd, snapshot); saved = null }
                catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                throw failure
            }
        } else {
            restore(hwnd, checkNotNull(saved))
            saved = null
        }
        return isFullscreen
    }

    private fun restore(hwnd: Pointer, snapshot: Restore) {
        style(hwnd, snapshot.decorations)
        (window as? Frame)?.extendedState = Frame.NORMAL
        val restoredBounds = visibleBounds(snapshot.bounds)
        window.bounds = restoredBounds
        refresh(hwnd)
        if (restoredBounds == snapshot.bounds) {
            check(user32.SetWindowPlacement(hwnd, snapshot.placement)) { "SetWindowPlacement failed: ${Native.getLastError()}" }
        }
        (window as? Frame)?.extendedState = snapshot.extendedState and Frame.ICONIFIED.inv()
        showAndValidate()
    }

    private fun style(hwnd: Pointer, decorations: Int) {
        val current = user32.GetWindowLongW(hwnd, GWL_STYLE)
        // Never restore a whole cached style: it can clear WS_VISIBLE and newer
        // AWT state. Only decoration bits belong to this controller.
        val updated = (current and decorationMask.inv()) or decorations
        if (updated != current) {
            Native.setLastError(0)
            val previous = user32.SetWindowLongW(hwnd, GWL_STYLE, updated)
            check(previous != 0 || Native.getLastError() == 0) { "SetWindowLongW failed: ${Native.getLastError()}" }
        }
    }

    private fun refresh(hwnd: Pointer) {
        check(user32.SetWindowPos(hwnd, null, 0, 0, 0, 0,
            0x0002 or 0x0001 or 0x0004 or 0x0010 or 0x0020 or 0x0040)) {
            "SetWindowPos failed: ${Native.getLastError()}"
        } // NOMOVE | NOSIZE | NOZORDER | NOACTIVATE | FRAMECHANGED | SHOWWINDOW
    }

    private fun showAndValidate() {
        window.isVisible = true
        window.invalidate()
        window.validate()
        window.repaint()
        window.toFront()
        window.requestFocus()
    }

    private fun visibleBounds(bounds: Rectangle): Rectangle {
        val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
        if (screens.any { it.bounds.intersects(bounds) }) return Rectangle(bounds)
        // Recover to the current screen if the original monitor was unplugged.
        val config = window.graphicsConfiguration
        val area = Rectangle(config.bounds)
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        area.setBounds(area.x + insets.left, area.y + insets.top,
            area.width - insets.left - insets.right, area.height - insets.top - insets.bottom)
        return Rectangle(area.x, area.y, bounds.width.coerceAtMost(area.width), bounds.height.coerceAtMost(area.height))
    }

    companion object {
        private val user32 by lazy { Native.load("user32", User32::class.java) }
        private const val GWL_STYLE = -16
        private const val decorationMask = 0x00C00000 or 0x00040000 or 0x00020000 or 0x00010000 or 0x00080000
    }
}
