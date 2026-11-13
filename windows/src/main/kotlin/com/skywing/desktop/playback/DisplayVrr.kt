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
                                  val detail: String = "Display capability unavailable")

/** Read-only, per-display NVIDIA query. Unknown never implies VRR support or activation.
 * ABI: NVIDIA nvapi.h NV_GET_VRR_INFO_V1 and nvapi_interface.h (MIT),
 * https://github.com/NVIDIA/nvapi — bIsVRRPossible is bit 1, distinct from activation. */
internal object DisplayVrr {
    var current by mutableStateOf(VrrCapability())
        private set
    private var queriedAt = Long.MIN_VALUE
    @Synchronized fun refresh(display: String?) {
        val now = System.nanoTime() / 1_000_000
        if (display == current.display && queriedAt != Long.MIN_VALUE && now - queriedAt < 30_000) return
        queriedAt = now
        current = if (display.isNullOrBlank()) VrrCapability() else query(display)
    }
    private fun query(display: String): VrrCapability = runCatching {
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
                else VrrCapability(display, info.getInt(4) and 2 != 0, "NVIDIA per-display capability")
            }
        } finally { unload.invokeInt(emptyArray()) }
    }.getOrElse { VrrCapability(display) }
}
