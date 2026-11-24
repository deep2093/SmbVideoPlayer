package com.skywing.desktop.playback

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * The graphics adapters Windows lists through DXGI, and whether the PC has a graphics card of its own (B24). DXGI
 * lists every adapter, also a laptop's graphics card that drives no screen, which the display list would leave out.
 */
internal object GpuAdapters {
    const val NVIDIA = 0x10DE
    const val AMD = 0x1002
    const val INTEL = 0x8086
    private const val MICROSOFT = 0x1414

    data class Adapter(val name: String, val vendor: Int, val dedicatedBytes: Long, val software: Boolean) {
        /**
         * A graphics card of its own rather than graphics built into the processor. Told by name, because built-in
         * graphics may report video memory of their own too.
         */
        val dedicated: Boolean
            get() = !software && when (vendor) {
                NVIDIA -> true
                INTEL -> INTEL_CARD.containsMatchIn(name)
                AMD -> AMD_CARD.containsMatchIn(name)
                else -> false
            }
    }

    // Arc A-series cards, Arc B5xx and later cards and Arc Pro cards. Arc 140V, Arc B3xx and plain "Arc Graphics" are
    // built into the processor.
    private val INTEL_CARD = Regex("""Arc(\(TM\))?\s+(Pro\s+[AB]\d|A\d{3}|B[5-9]\d{2})""", RegexOption.IGNORE_CASE)
    // Radeon RX and Radeon Pro cards. Radeon 780M, 890M, "Radeon Graphics" and the older "RX Vega" built-in graphics
    // are not.
    private val AMD_CARD = Regex("""Radeon(\(TM\))?\s+(RX\s+\d|PRO\s+\w)""", RegexOption.IGNORE_CASE)

    /** True when any adapter is a graphics card of its own. Read once; adapters do not change while SkyWing runs. */
    val hasDedicated: Boolean by lazy { list().any { it.dedicated } }

    /** The hardware adapters, without the software renderer. Empty when DXGI is unavailable. */
    fun list(): List<Adapter> = runCatching { enumerate() }.getOrDefault(emptyList())

    // COM method slots: IUnknown (0-2), IDXGIObject (3-6), IDXGIFactory (7-11), IDXGIFactory1.EnumAdapters1 (12);
    // IDXGIAdapter (7-9), IDXGIAdapter1.GetDesc1 (10).
    private const val RELEASE = 2
    private const val ENUM_ADAPTERS1 = 12
    private const val GET_DESC1 = 10
    private const val SOFTWARE_FLAG = 2 // DXGI_ADAPTER_FLAG_SOFTWARE
    private const val DESC1_SIZE = 320L // DXGI_ADAPTER_DESC1 is 312 bytes on x64

    private fun enumerate(): List<Adapter> {
        val create = NativeLibrary.getInstance("dxgi").getFunction("CreateDXGIFactory1")
        val factoryOut = PointerByReference()
        val created = Memory(16).use { iid -> // IID_IDXGIFactory1 {770aae78-f26f-4dba-a829-253c83d1b387}
            iid.setInt(0, 0x770aae78)
            iid.setShort(4, 0xf26f.toShort())
            iid.setShort(6, 0x4dba)
            iid.write(8, byteArrayOf(0xa8.toByte(), 0x29, 0x25, 0x3c, 0x83.toByte(), 0xd1.toByte(), 0xb3.toByte(), 0x87.toByte()), 0, 8)
            create.invokeInt(arrayOf(iid, factoryOut))
        }
        if (created < 0) return emptyList()
        val factory = factoryOut.value ?: return emptyList()
        try {
            val found = ArrayList<Adapter>()
            for (index in 0 until 16) {
                val adapterOut = PointerByReference()
                // DXGI_ERROR_NOT_FOUND (negative) ends the list.
                if (call(factory, ENUM_ADAPTERS1, index, adapterOut) < 0) break
                val adapter = adapterOut.value ?: break
                try {
                    Memory(DESC1_SIZE).use { desc ->
                        desc.clear()
                        if (call(adapter, GET_DESC1, desc) >= 0) {
                            val vendor = desc.getInt(256)
                            val software = desc.getInt(304) and SOFTWARE_FLAG != 0 || vendor == MICROSOFT
                            if (!software) found += Adapter(desc.getWideString(0).trim(), vendor, desc.getLong(272), software = false)
                        }
                    }
                } finally {
                    call(adapter, RELEASE)
                }
            }
            return found
        } finally {
            call(factory, RELEASE)
        }
    }

    private fun call(self: Pointer, slot: Int, vararg args: Any): Int {
        val method = self.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(method).invokeInt(arrayOf(self, *args))
    }
}
