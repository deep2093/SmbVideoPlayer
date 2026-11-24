package com.skywing.desktop.playback

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * One active screen as Windows' display configuration describes it: its GDI name (`\\.\DISPLAY1`), the adapter that
 * drives it (by LUID), its current refresh rate and the monitor's device path.
 */
internal data class DisplayPath(
    val gdiName: String,
    val adapterLuid: Long,
    val targetId: Int,
    val refreshHz: Double?,
    val friendlyName: String,
    val devicePath: String,
    /** The adapter Windows names for the monitor itself; target-device packets (HDR) use it. */
    val targetAdapterLuid: Long = adapterLuid,
    /** The desktop's size on this screen (its source mode), in physical pixels; null when Windows did not say. */
    val desktop: PixelSize? = null,
)

/**
 * Windows' active display paths (QueryDisplayConfig). Struct layouts from Microsoft Learn's wingdi.h pages:
 * DISPLAYCONFIG_PATH_INFO is 72 bytes, DISPLAYCONFIG_MODE_INFO 64.
 */
internal object DisplayPaths {
    private interface User32 : StdCallLibrary {
        fun GetDisplayConfigBufferSizes(flags: Int, paths: IntByReference, modes: IntByReference): Int
        fun QueryDisplayConfig(flags: Int, paths: IntByReference, pathArray: Pointer, modes: IntByReference, modeArray: Pointer, topology: Pointer?): Int
        fun DisplayConfigGetDeviceInfo(packet: Pointer): Int
        fun MonitorFromWindow(window: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Boolean
    }

    private val user32: User32? by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }
    private const val ONLY_ACTIVE_PATHS = 0x2
    private const val INSUFFICIENT_BUFFER = 122
    private const val PATH_SIZE = 72L
    private const val MODE_SIZE = 64L
    private const val GET_SOURCE_NAME = 1
    private const val SOURCE_MODE = 1
    private const val GET_TARGET_NAME = 2
    private const val GET_ADVANCED_COLOR_INFO = 9
    private const val GET_ADVANCED_COLOR_INFO_2 = 15

    /** The active screens; empty when Windows can't say. */
    fun active(): List<DisplayPath> = runCatching { query() }.getOrDefault(emptyList())

    /** The screen showing [window] (the one Windows counts it on, or the nearest), or null. */
    fun forWindow(window: Pointer): DisplayPath? = runCatching {
        val gdi = monitorName(window) ?: return@runCatching null
        active().firstOrNull { it.gdiName.equals(gdi, ignoreCase = true) }
    }.getOrNull()

    /** The GDI device name of the monitor showing [window] (MONITORINFOEXW.szDevice). */
    fun monitorName(window: Pointer): String? {
        val api = user32 ?: return null
        val monitor = api.MonitorFromWindow(window, 2) ?: return null // MONITOR_DEFAULTTONEAREST
        return Memory(104).use { info ->
            info.clear()
            info.setInt(0, info.size().toInt())
            if (api.GetMonitorInfoW(monitor, info)) info.getWideString(40) else null
        }
    }

    private fun query(): List<DisplayPath> {
        val api = user32 ?: return emptyList()
        // The configuration can change between the two calls; ask again then.
        repeat(3) {
            val pathCount = IntByReference()
            val modeCount = IntByReference()
            if (api.GetDisplayConfigBufferSizes(ONLY_ACTIVE_PATHS, pathCount, modeCount) != 0) return emptyList()
            if (pathCount.value <= 0) return emptyList()
            val paths = Memory(pathCount.value * PATH_SIZE)
            val modes = Memory(maxOf(1, modeCount.value) * MODE_SIZE)
            val status = api.QueryDisplayConfig(ONLY_ACTIVE_PATHS, pathCount, paths, modeCount, modes, null)
            if (status == INSUFFICIENT_BUFFER) return@repeat
            if (status != 0) return emptyList()
            return (0 until pathCount.value).mapNotNull { i -> path(api, paths, i * PATH_SIZE, modes, modeCount.value) }
        }
        return emptyList()
    }

    private fun path(api: User32, paths: Memory, at: Long, modes: Memory, modeCount: Int): DisplayPath? {
        // DISPLAYCONFIG_PATH_SOURCE_INFO: adapterId (0), id (8), modeInfoIdx (12). DISPLAYCONFIG_PATH_TARGET_INFO from
        // 20: adapterId (20), id (28), refreshRate numerator (48) and denominator (52).
        val sourceAdapter = paths.getLong(at)
        val sourceId = paths.getInt(at + 8)
        val desktop = sourceSize(modes, modeCount, paths.getInt(at + 12))
        val targetAdapter = paths.getLong(at + 20)
        val targetId = paths.getInt(at + 28)
        val numerator = paths.getInt(at + 48).toUInt().toDouble()
        val denominator = paths.getInt(at + 52).toUInt().toDouble()
        val gdi = Memory(84).use { name -> // DISPLAYCONFIG_SOURCE_DEVICE_NAME: header (20) + WCHAR[32]
            header(name, GET_SOURCE_NAME, sourceAdapter, sourceId)
            if (api.DisplayConfigGetDeviceInfo(name) == 0) name.getWideString(20) else null
        } ?: return null
        // DISPLAYCONFIG_TARGET_DEVICE_NAME: header (20), flags, outputTechnology, EDID IDs, connectorInstance,
        // monitorFriendlyDeviceName WCHAR[64] at 36, monitorDevicePath WCHAR[128] at 164.
        val (friendly, device) = Memory(420).use { name ->
            header(name, GET_TARGET_NAME, targetAdapter, targetId)
            if (api.DisplayConfigGetDeviceInfo(name) == 0) name.getWideString(36) to name.getWideString(164) else "" to ""
        }
        return DisplayPath(gdi, sourceAdapter, targetId,
            if (numerator > 0 && denominator > 0) numerator / denominator else null, friendly, device, targetAdapter, desktop)
    }

    /**
     * The size of the source mode at [index] in a DISPLAYCONFIG_MODE_INFO array of [count]: the desktop's resolution on
     * that screen, in physical pixels and as rotated. Without QDC_VIRTUAL_MODE_AWARE a path's modeInfoIdx is a plain
     * index, DISPLAYCONFIG_PATH_MODE_IDX_INVALID (-1) when there is none. A mode has infoType at 0 (1 for a source)
     * and, for a source, width at 16 and height at 20.
     */
    internal fun sourceSize(modes: Pointer, count: Int, index: Int): PixelSize? {
        if (index !in 0 until count) return null
        val at = index * MODE_SIZE
        if (modes.getInt(at) != SOURCE_MODE) return null
        return PixelSize.of(modes.getInt(at + 16), modes.getInt(at + 20))
    }

    /**
     * The screen's HDR state from Windows' advanced colour packets (wingdi.h). DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO_2
     * (type 15, 36 bytes, Windows 11) goes first; it falls back to DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO (type 9,
     * 32 bytes). In both, the flags sit at 20; the newer one adds activeColorMode at 32.
     */
    fun hdr(path: DisplayPath): HdrState = runCatching {
        val api = user32 ?: return@runCatching AdvancedColor.UNKNOWN
        Memory(36).use { info ->
            header(info, GET_ADVANCED_COLOR_INFO_2, path.targetAdapterLuid, path.targetId)
            if (api.DisplayConfigGetDeviceInfo(info) == 0) return@runCatching AdvancedColor.fromInfo2(info.getInt(20), info.getInt(32))
        }
        Memory(32).use { info ->
            header(info, GET_ADVANCED_COLOR_INFO, path.targetAdapterLuid, path.targetId)
            if (api.DisplayConfigGetDeviceInfo(info) == 0) AdvancedColor.fromInfo(info.getInt(20)) else AdvancedColor.UNKNOWN
        }
    }.getOrDefault(AdvancedColor.UNKNOWN)

    /** DISPLAYCONFIG_DEVICE_INFO_HEADER: type, size, adapterId, id. */
    internal fun header(packet: Memory, type: Int, adapter: Long, id: Int) {
        packet.clear()
        packet.setInt(0, type)
        packet.setInt(4, packet.size().toInt())
        packet.setLong(8, adapter)
        packet.setInt(16, id)
    }
}
