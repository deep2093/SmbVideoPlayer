package com.skywing.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

/** Whether the PC runs on its battery, from Windows' power status. A PC without a battery counts as plugged in. */
internal object PowerSource {
    private interface Kernel32 : StdCallLibrary {
        fun GetSystemPowerStatus(status: Pointer): Boolean
    }

    private val kernel: Kernel32? by lazy { runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull() }
    private const val CACHE_MS = 5_000L
    @Volatile private var checkedAt = 0L
    @Volatile private var battery = false

    /** True while running on battery. Asked often, so the answer is kept for a few seconds. */
    fun onBattery(): Boolean {
        val now = System.currentTimeMillis()
        if (now - checkedAt < CACHE_MS) return battery
        battery = runCatching {
            Memory(12).use { status -> // SYSTEM_POWER_STATUS; ACLineStatus is its first byte: 0 battery, 1 mains, 255 unknown
                kernel?.GetSystemPowerStatus(status) == true && status.getByte(0).toInt() == 0
            }
        }.getOrDefault(false)
        checkedAt = now
        return battery
    }
}
