package com.skywing.desktop.playback

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Frame
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A variable refresh range declared in a screen's EDID, and the block that declares it. A null [max] means up to the mode's own refresh (HDMI VRRmax 0). */
internal data class VrrRange(val min: Int, val max: Int?, val source: String) {
    val label: String get() = if (max != null) "$min–$max Hz" else "from $min Hz"
}

/**
 * What a screen's EDID says about refresh. [limits] is the base block's Display Range Limits. On its own it is not a
 * VRR signal, because most fixed-refresh screens carry it too.
 */
internal data class EdidRefresh(val vrr: VrrRange?, val limits: IntRange?)

/**
 * Reads the refresh parts of an EDID (plan 5.4), at the offsets edid-decode uses (parse-base-block.cpp,
 * parse-cta-block.cpp, parse-displayid-block.cpp):
 * - the EDID 1.4 Range Limits offsets
 * - AMD's FreeSync VSDB (OUI 00-00-1A, reverse-engineered there)
 * - the HDMI Forum VSDB/SCDB VRRmin/VRRmax
 * - the DisplayID 2.x Adaptive-Sync block (tag 0x2B)
 */
internal object Edid {
    private val HEADER = byteArrayOf(0, -1, -1, -1, -1, -1, -1, 0)
    private const val AMD = 0x00001A
    private const val HDMI_FORUM = 0xC45DD8
    private val PREFERENCE = listOf("Adaptive-Sync", "FreeSync", "HDMI VRR")
    private val NAME_PART = Regex("[A-Za-z0-9&_.-]+")

    private fun ByteArray.u(i: Int): Int = if (i in indices) this[i].toInt() and 0xFF else 0

    fun parse(edid: ByteArray): EdidRefresh {
        if (edid.size < 128 || !edid.copyOf(8).contentEquals(HEADER)) return EdidRefresh(null, null)
        val found = mutableListOf<VrrRange>()
        for (n in 1..minOf(edid.u(126), edid.size / 128 - 1)) {
            val at = n * 128
            when (edid.u(at)) {
                0x02 -> cta(edid, at, found)
                0x70 -> displayId(edid, at, found)
            }
        }
        val vrr = PREFERENCE.firstNotNullOfOrNull { source -> found.firstOrNull { it.source == source } }
        return EdidRefresh(vrr, limits(edid))
    }

    private fun limits(e: ByteArray): IntRange? {
        val minor = e.u(19)
        for (at in listOf(54, 72, 90, 108)) {
            if (e.u(at) != 0 || e.u(at + 1) != 0 || e.u(at + 3) != 0xFD) continue
            val flags = if (minor >= 4) e.u(at + 4) else 0
            val max = e.u(at + 6) + if (flags and 0x02 != 0) 255 else 0
            val min = e.u(at + 5) + if (flags and 0x03 == 0x03) 255 else 0
            return if (min in 1..max) min..max else null
        }
        return null
    }

    /** CTA-861: data blocks run from byte 4 to the DTD offset in byte 2. */
    private fun cta(e: ByteArray, at: Int, found: MutableList<VrrRange>) {
        val end = at + minOf(e.u(at + 2), 127)
        var i = at + 4
        while (i < end) {
            val tag = e.u(i) shr 5
            val length = e.u(i) and 0x1F
            if (i + length >= end) break
            val oui = if (tag == 3 && length >= 3) e.u(i + 1) or (e.u(i + 2) shl 8) or (e.u(i + 3) shl 16) else -1
            when {
                oui == AMD && length >= 7 -> freeSync(e, i, length)?.let(found::add)
                oui == HDMI_FORUM && length >= 10 -> hdmiVrr(e, i)?.let(found::add)
                tag == 7 && length >= 10 && e.u(i + 1) == 0x79 -> hdmiVrr(e, i)?.let(found::add) // HF-SCDB
            }
            i += 1 + length
        }
    }

    /** Version 1–2 keep the maximum in byte 7; version 3 in 10 bits at bytes 14–15. */
    private fun freeSync(e: ByteArray, i: Int, length: Int): VrrRange? {
        val min = e.u(i + 6)
        val max = if (e.u(i + 4) >= 3 && length >= 15) e.u(i + 14) or ((e.u(i + 15) and 0x03) shl 8) else e.u(i + 7)
        return if (min in 1..max) VrrRange(min, max, "FreeSync") else null
    }

    /** VRRmin is 6 bits; VRRmax 10 bits, its top two in VRRmin's byte. The VSDB and the SCDB use the same offsets. */
    private fun hdmiVrr(e: ByteArray, i: Int): VrrRange? {
        val min = e.u(i + 9) and 0x3F
        val max = ((e.u(i + 9) and 0xC0) shl 2) or e.u(i + 10)
        return if (min > 0 && (max == 0 || max >= min)) VrrRange(min, max.takeIf { it > 0 }, "HDMI VRR") else null
    }

    /** DisplayID 2.x: blocks of tag, revision and length from byte 5. The panel's native range goes first. */
    private fun displayId(e: ByteArray, at: Int, found: MutableList<VrrRange>) {
        if (e.u(at + 1) < 0x20) return
        val end = at + 5 + minOf(e.u(at + 2), 121)
        var i = at + 5
        while (i + 3 <= end) {
            val length = e.u(i + 2)
            if (i + 3 + length > end) break
            if (e.u(i) == 0x2B) {
                val size = 6 + ((e.u(i + 1) shr 4) and 0x07)
                val ranges = (0 until length / size).mapNotNull { k ->
                    val d = i + 3 + k * size
                    val min = e.u(d + 2)
                    val max = 1 + e.u(d + 3) + (e.u(d + 4) and 0x03) * 256
                    if (min in 1..max) (e.u(d) and 0x01 == 1) to VrrRange(min, max, "Adaptive-Sync") else null
                }
                (ranges.firstOrNull { it.first } ?: ranges.firstOrNull())?.let { found += it.second }
            }
            i += 3 + length
        }
    }

    /**
     * The registry key that keeps the EDID for a monitor device path (`\\?\DISPLAY#<model>#<instance>#{class}`);
     * null for anything else.
     */
    fun registryKey(devicePath: String): String? {
        val parts = devicePath.removePrefix("\\\\?\\").split('#')
        if (parts.size < 3 || !parts[0].equals("DISPLAY", ignoreCase = true)) return null
        val (model, instance) = parts[1] to parts[2]
        if (!NAME_PART.matches(model) || !NAME_PART.matches(instance) || ".." in model || ".." in instance) return null
        return "SYSTEM\\CurrentControlSet\\Enum\\DISPLAY\\$model\\$instance\\Device Parameters"
    }

    private interface Advapi32 : StdCallLibrary {
        fun RegGetValueW(key: Pointer, subKey: WString, value: WString, flags: Int, type: IntByReference?, data: Pointer?, size: IntByReference): Int
    }

    private val advapi: Advapi32? by lazy { runCatching { Native.load("advapi32", Advapi32::class.java) }.getOrNull() }
    // HKEY_LOCAL_MACHINE is (HKEY)(LONG)0x80000002, sign-extended on x64.
    private val LOCAL_MACHINE = Pointer(0x80000002.toInt().toLong())
    private const val BINARY_ONLY = 0x00000008 // RRF_RT_REG_BINARY

    /** The monitor's EDID as Windows keeps it (read-only); nothing when it can't be read. */
    fun read(devicePath: String): EdidRefresh = runCatching {
        val key = registryKey(devicePath) ?: return@runCatching EdidRefresh(null, null)
        val api = advapi ?: return@runCatching EdidRefresh(null, null)
        Memory(4096).use { buffer ->
            val size = IntByReference(buffer.size().toInt())
            val status = api.RegGetValueW(LOCAL_MACHINE, WString(key), WString("EDID"), BINARY_ONLY, null, buffer, size)
            if (status == 0) parse(buffer.getByteArray(0, size.value)) else EdidRefresh(null, null)
        }
    }.getOrDefault(EdidRefresh(null, null))
}

/** Whether a screen shows HDR now, and the Windows packet that said so. Null: nothing reported it. */
internal data class HdrState(val supported: Boolean?, val on: Boolean?, val source: String) {
    val label: String get() = when (on) { true -> "on"; false -> "off"; null -> "not reported" }
}

/** Windows' advanced colour packets (wingdi.h). */
internal object AdvancedColor {
    val UNKNOWN = HdrState(null, null, "not reported")

    /** DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO_2 (Windows 11): HDR is activeColorMode 2; bit 4 is highDynamicRangeSupported. */
    fun fromInfo2(flags: Int, mode: Int) = HdrState(flags and 0x10 != 0, mode == 2, "Windows advanced colour info 2")

    /** DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO: enabled (bit 1) is HDR, unless wide colour is enforced on an SDR screen (bit 2). */
    fun fromInfo(flags: Int): HdrState {
        val enforced = flags and 0x04 != 0
        return HdrState(if (enforced) null else flags and 0x01 != 0, flags and 0x02 != 0 && !enforced, "Windows advanced colour info")
    }
}

/** Settings → Playback's "Screen" line, e.g. "240 Hz · Variable refresh 48–240 Hz (detected) · HDR off" (plan 5.4). */
internal object ScreenSummary {
    fun hz(value: Double): String =
        if (abs(value - value.roundToInt()) < 0.01) value.roundToInt().toString() else String.format(Locale.ROOT, "%.2f", value)

    /**
     * A VRR block in the EDID gives the range. When NVIDIA says VRR is possible, the EDID's range limits are the range,
     * as DisplayPort Adaptive-Sync declares it.
     */
    fun variableRefresh(edid: EdidRefresh, nvidiaPossible: Boolean?): String = when {
        edid.vrr != null -> "Variable refresh ${edid.vrr.label} (detected)"
        nvidiaPossible == true -> edid.limits?.let { "Variable refresh ${it.first}–${it.last} Hz (detected)" }
            ?: "Variable refresh (range not reported)"
        nvidiaPossible == false -> "No variable refresh"
        else -> "Variable refresh not detected"
    }

    fun line(refreshHz: Double?, edid: EdidRefresh, nvidiaPossible: Boolean?, hdr: HdrState): String = listOf(
        refreshHz?.takeIf { it.isFinite() && it > 0 }?.let { hz(it) + " Hz" } ?: "Refresh not reported",
        variableRefresh(edid, nvidiaPossible),
        "HDR " + hdr.label,
    ).joinToString(" · ")
}

/** One screen as last read: its display path, HDR state and EDID refresh. */
internal data class ScreenState(val path: DisplayPath, val hdr: HdrState, val edid: EdidRefresh)

/**
 * The screens, read again only after WM_DISPLAYCHANGE ([DisplayChanges]). When that can't be heard, they are read
 * again once a minute. A new video reads HDR and EDID again ([forget]).
 */
internal object Screens {
    private var paths: List<DisplayPath> = emptyList()
    private var readGeneration = -1L
    private var readAt = 0L
    private val states = HashMap<String, ScreenState>()

    @Synchronized
    fun paths(): List<DisplayPath> {
        val generation = DisplayChanges.generation
        val now = System.nanoTime() / 1_000_000
        if (generation != readGeneration || (!DisplayChanges.listening && now - readAt >= 60_000)) {
            paths = DisplayPaths.active()
            states.clear()
            readGeneration = generation
            readAt = now
        }
        return paths
    }

    @Synchronized
    fun forget() = states.clear()

    /** The screen showing [window], or null when Windows' display paths can't place it. */
    @Synchronized
    fun forWindow(window: Pointer): ScreenState? {
        val gdi = DisplayPaths.monitorName(window) ?: return null
        val path = paths().firstOrNull { it.gdiName.equals(gdi, ignoreCase = true) } ?: return null
        return states.getOrPut(path.devicePath.ifEmpty { path.gdiName }) {
            ScreenState(path, DisplayPaths.hdr(path), Edid.read(path.devicePath))
        }
    }

    /** SkyWing's main window (read on the UI thread). */
    fun appWindow(): Pointer? = Frame.getFrames().firstOrNull { it.isShowing }
        ?.let { runCatching { Native.getWindowPointer(it) }.getOrNull() }

    /** The Settings line for the screen showing [window]. It asks NVIDIA, so call it off the UI thread. */
    fun describe(window: Pointer): String? = forWindow(window)?.let {
        ScreenSummary.line(it.path.refreshHz, it.edid, DisplayVrr.read(it.path.gdiName).supported, it.hdr)
    }
}
