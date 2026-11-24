package com.skywing.desktop.playback

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/** A graphics maker, from the PCI vendor ID (plan 5.2). [openCl] is text its OpenCL driver's vendor string contains. */
internal enum class GpuVendor(val label: String, val openCl: String?) {
    NVIDIA("NVIDIA", "NVIDIA"),
    // AMD's OpenCL vendor is "Advanced Micro Devices, Inc."; "AMD" would match no device (B5).
    AMD("AMD", "Advanced Micro Devices"),
    INTEL("Intel", "Intel"),
    OTHER("Other", null);

    companion object {
        fun of(pciVendorId: Int): GpuVendor = when (pciVendorId) {
            GpuAdapters.NVIDIA -> NVIDIA
            GpuAdapters.AMD -> AMD
            GpuAdapters.INTEL -> INTEL
            else -> OTHER
        }
    }
}

/** How the PC's graphics are wired to the screen SkyWing is on (plan 5.2). */
internal enum class GpuMode(val label: String) {
    /** A dedicated GPU drives the screen, and the PC also has integrated graphics. */
    DEDICATED("Dedicated"),
    /** A dedicated GPU is present, but the screen is on the integrated one. */
    HYBRID("Hybrid"),
    INTEGRATED_ONLY("Integrated only"),
    DEDICATED_ONLY("Dedicated only"),
    UNKNOWN("Graphics unknown"),
}

/** Windows' graphics setting for SkyWing (Settings → System → Display → Graphics). */
internal enum class WindowsGpuPreference { NONE, POWER_SAVING, HIGH_PERFORMANCE }

/** The PC's graphics adapters and Windows' graphics setting for SkyWing, read once per plan. */
internal data class GpuInventory(
    val adapters: List<GpuAdapters.Adapter>,
    val windowsPreference: WindowsGpuPreference = WindowsGpuPreference.NONE,
) {
    val externalGpu: Boolean get() = adapters.any { it.detachable }

    fun byLuid(luid: Long): GpuAdapters.Adapter? = adapters.firstOrNull { it.luid == luid }

    /** The mode for the screen driven by [monitor], or by an adapter SkyWing couldn't tell (null). */
    fun mode(monitor: GpuAdapters.Adapter?): GpuMode {
        val dedicated = adapters.any { it.dedicated }
        val integrated = adapters.any { !it.dedicated }
        return when {
            dedicated && integrated -> if (monitor?.dedicated == true) GpuMode.DEDICATED else GpuMode.HYBRID
            dedicated -> GpuMode.DEDICATED_ONLY
            integrated -> GpuMode.INTEGRATED_ONLY
            else -> GpuMode.UNKNOWN
        }
    }

    fun label(monitor: GpuAdapters.Adapter?): String = mode(monitor).label + if (externalGpu) " · external GPU" else ""

    companion object {
        fun read(): GpuInventory = GpuInventory(GpuAdapters.list(), WindowsGraphicsSetting.forThisApp())
    }
}

/** Reads Windows' per-app graphics setting, stored as "GpuPreference=<n>;" under the app's full path. */
internal object WindowsGraphicsSetting {
    private interface Advapi32 : StdCallLibrary {
        fun RegGetValueW(key: Pointer, subKey: WString, value: WString, flags: Int, type: IntByReference?, data: Pointer?, size: IntByReference): Int
    }

    private val advapi: Advapi32? by lazy { runCatching { Native.load("advapi32", Advapi32::class.java) }.getOrNull() }
    // HKEY_CURRENT_USER is (HKEY)(LONG)0x80000001, sign-extended on x64.
    private val CURRENT_USER = Pointer(0x80000001.toInt().toLong())
    private const val STRING_ONLY = 0x00000002 // RRF_RT_REG_SZ

    fun parse(data: String?): WindowsGpuPreference =
        when (Regex("""GpuPreference=(\d+);""").find(data.orEmpty())?.groupValues?.get(1)) {
            "1" -> WindowsGpuPreference.POWER_SAVING
            "2" -> WindowsGpuPreference.HIGH_PERFORMANCE
            else -> WindowsGpuPreference.NONE
        }

    /** The setting for the running SkyWing.exe; NONE when there is none, or in a development run (java.exe). */
    fun forThisApp(): WindowsGpuPreference = runCatching {
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return@runCatching WindowsGpuPreference.NONE
        val api = advapi ?: return@runCatching WindowsGpuPreference.NONE
        Memory(1024).use { buffer ->
            val size = IntByReference(buffer.size().toInt())
            val status = api.RegGetValueW(CURRENT_USER, WString("Software\\Microsoft\\DirectX\\UserGpuPreferences"),
                WString(exe), STRING_ONLY, null, buffer, size)
            if (status == 0) parse(buffer.getWideString(0)) else WindowsGpuPreference.NONE
        }
    }.getOrDefault(WindowsGpuPreference.NONE)
}
