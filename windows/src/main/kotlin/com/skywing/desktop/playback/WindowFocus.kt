package com.skywing.desktop.playback

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

/**
 * Whether a window's top-level window is the one in front (plan 5.9). Windows may give a window that is not in front
 * less processor time, or move its threads to slower cores on a hybrid processor, so the per-minute log records it
 * beside the drops.
 */
internal object WindowFocus {
    private interface User32 : StdCallLibrary {
        fun GetForegroundWindow(): Pointer?
        fun GetAncestor(window: Pointer, flags: Int): Pointer?
    }

    private const val GA_ROOT = 2
    private val user32: User32? by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }

    /** True when [window]'s top-level window is in front; null when Windows names no window in front or can't be asked. */
    fun inFront(window: Pointer): Boolean? = runCatching {
        val user32 = user32 ?: return null
        val front = user32.GetForegroundWindow() ?: return null
        user32.GetAncestor(window, GA_ROOT)?.let { it == front }
    }.getOrNull()
}
