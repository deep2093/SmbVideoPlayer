package com.skywing.desktop.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.ptr.IntByReference
import java.io.File

internal data class VrrCapability(val display: String? = null, val supported: Boolean? = null,
                                  val detail: String = "Display capability unavailable", val nvidia: NvidiaVrr? = null)

/**
 * NV_GET_VRR_INFO_V1's flags, as nvapi.h names them. nvapi.h says [enabled] is "VRR Mode is currently active on given
 * display" and [inVrrMode] is "the display is in VRR Mode". Which one follows SkyWing's fullscreen is still to be
 * measured on real drivers (plan 5.4).
 */
internal data class NvidiaVrr(val enabled: Boolean, val possible: Boolean, val requested: Boolean, val indicator: Boolean,
                              val inVrrMode: Boolean) {
    val label: String get() = "NVIDIA VRR: enabled $enabled, possible $possible, requested $requested, display in VRR mode $inVrrMode"

    companion object {
        fun parse(flags: Int) = NvidiaVrr(flags and 0x01 != 0, flags and 0x02 != 0, flags and 0x04 != 0,
            flags and 0x08 != 0, flags and 0x10 != 0)
    }
}

/** Read-only, per-display NVIDIA query. Unknown never implies VRR support or activation.
 * ABI: NVIDIA nvapi.h NV_GET_VRR_INFO_V1 and nvapi_interface.h (MIT),
 * https://github.com/NVIDIA/nvapi — bIsVRRPossible is bit 1, distinct from activation. */
internal object DisplayVrr {
    var current by mutableStateOf(VrrCapability())
        private set
    private data class Key(val display: String?, val fullscreen: Boolean, val generation: Long)
    private var key: Key? = null
    private var changedAt = 0L
    private var settled = true
    private var queriedAt = 0L

    /**
     * Asks NVIDIA again when the screen, fullscreen or Windows' display configuration changes, and once more 3 s later
     * because VRR engages after the switch. Every 30 s only when display changes can't be heard.
     */
    @Synchronized fun refresh(display: String?, fullscreen: Boolean = false) {
        val now = System.nanoTime() / 1_000_000
        val next = Key(display, fullscreen, DisplayChanges.generation)
        when {
            next != key -> { key = next; changedAt = now; settled = false }
            !settled && now - changedAt >= 3_000 -> settled = true
            !DisplayChanges.listening && now - queriedAt >= 30_000 -> {}
            else -> return
        }
        queriedAt = now
        current = if (display.isNullOrBlank()) VrrCapability() else read(display)
    }

    /** One NVIDIA query for the GDI [display] (`\\.\DISPLAY1`); unknown when NVIDIA doesn't drive it. */
    fun read(display: String): VrrCapability = runCatching {
        if (!System.getProperty("os.name").startsWith("Windows")) return@runCatching VrrCapability(display)
        val system = System.getenv("SystemRoot") ?: return@runCatching VrrCapability(display)
        val library = NativeLibrary.getInstance(File(system, "System32/nvapi64.dll").absolutePath)
        val query = library.getFunction("nvapi_QueryInterface")
        fun function(id: Int): Function = Function.getFunction(checkNotNull(query.invokePointer(arrayOf(id))))
        val initialize = function(0x0150e828)
        val unload = function(0xd22bdd7e.toInt())
        if (initialize.invokeInt(emptyArray()) != 0) return@runCatching VrrCapability(display)
        try {
            val id = IntByReference()
            val mapped = function(0xae457190.toInt()).invokeInt(arrayOf(display, id))
            if (mapped != 0) return@runCatching VrrCapability(display, detail = "Active display not reported by NVIDIA (" + mapped + ")")
            Memory(24).use { info ->
                info.clear(); info.setInt(0, 24 or (1 shl 16))
                val result = function(0xdf8fda57.toInt()).invokeInt(arrayOf(id.value, info))
                if (result != 0) VrrCapability(display, detail = "NVIDIA VRR query unavailable (" + result + ")")
                else NvidiaVrr.parse(info.getInt(4)).let { VrrCapability(display, it.possible, "NVIDIA per-display capability", it) }
            }
        } finally { unload.invokeInt(emptyArray()) }
    }.getOrElse { VrrCapability(display) }
}
