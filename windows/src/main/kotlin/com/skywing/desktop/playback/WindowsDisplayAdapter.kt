package com.skywing.desktop.playback

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary

/** Maps the player's initial monitor to its active Windows display adapter. */
internal object WindowsDisplayAdapter {
    private interface User32 : StdCallLibrary {
        fun MonitorFromWindow(window: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Boolean
        fun EnumDisplayDevicesW(device: WString?, index: Int, info: Pointer, flags: Int): Boolean
    }

    private val user32 by lazy { Native.load("user32", User32::class.java) }
    private const val ACTIVE = 1

    fun vendor(window: Pointer): String? = runCatching { displayName(window)?.let(::attachedVendor) }.getOrNull()

    private fun displayName(window: Pointer): String? {
        val monitor = user32.MonitorFromWindow(window, 2) ?: return null // MONITOR_DEFAULTTONEAREST
        return Memory(104).use { info -> // MONITORINFOEXW
            info.clear()
            info.setInt(0, info.size().toInt())
            if (user32.GetMonitorInfoW(monitor, info)) info.getWideString(40) else null
        }
    }

    private fun attachedVendor(display: String): String? = Memory(840).use { info -> // DISPLAY_DEVICEW
        for (index in 0 until 64) {
            info.clear()
            info.setInt(0, info.size().toInt())
            if (!user32.EnumDisplayDevicesW(null, index, info, 0)) break
            if (info.getInt(324) and ACTIVE != ACTIVE || !display.equals(info.getWideString(4), true)) continue
            val adapter = info.getWideString(68)
            return when {
                adapter.startsWith("Intel", true) -> "Intel"
                adapter.startsWith("NVIDIA", true) -> "NVIDIA"
                else -> null
            }
        }
        null
    }
}
