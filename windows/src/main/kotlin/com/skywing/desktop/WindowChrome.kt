package com.skywing.desktop

import androidx.compose.ui.graphics.Color
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * Colours the normal Windows title bar like the app theme (Windows 11; Windows 10 only takes the dark or light
 * frame). Snapping, dragging and resizing stay the system's own.
 */
internal object WindowChrome {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }

    private const val USE_DARK_MODE_OLD = 19
    private const val USE_DARK_MODE = 20
    private const val BORDER_COLOR = 34
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    /** Windows wants 0x00BBGGRR. */
    private fun colorRef(color: Color): Int {
        val argb = color.copy(alpha = 1f).let { (it.value shr 32).toLong() }
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return ((b shl 16) or (g shl 8) or r).toInt()
    }

    fun apply(window: java.awt.Window, theme: SkyTheme) {
        val api = dwm ?: return
        if (!window.isDisplayable) return
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
        fun set(attribute: Int, value: Int) {
            runCatching { api.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4) }
        }
        val dark = if (theme.light) 0 else 1
        set(USE_DARK_MODE, dark)
        set(USE_DARK_MODE_OLD, dark)
        set(CAPTION_COLOR, colorRef(theme.background))
        set(BORDER_COLOR, colorRef(theme.background))
        set(TEXT_COLOR, colorRef(theme.ink))
    }
}
