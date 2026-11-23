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

    /**
     * One adapter. [luid] is the ID Windows uses for it everywhere (DXGI, DXCore, the display paths, OpenCL);
     * [integrated] and [detachable] are Windows' own answers from DXCore, null and false where DXCore can't say.
     */
    data class Adapter(
        val name: String, val vendor: Int, val dedicatedBytes: Long, val software: Boolean,
        val luid: Long = 0, val integrated: Boolean? = null, val detachable: Boolean = false,
    ) {
        /**
         * A graphics card of its own rather than graphics built into the processor. Windows' answer when it gives one;
         * otherwise told by name, because built-in graphics may report video memory of their own too.
         */
        val dedicated: Boolean
            get() = !software && (integrated?.not() ?: when (vendor) {
                NVIDIA -> true
                INTEL -> INTEL_CARD.containsMatchIn(name)
                AMD -> AMD_CARD.containsMatchIn(name)
                else -> false
            })

        /** The maker, from the PCI vendor ID. */
        val gpuVendor: GpuVendor get() = GpuVendor.of(vendor)
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
    fun list(): List<Adapter> =
        merge(runCatching { enumerate() }.getOrDefault(emptyList()), runCatching { DxCore.flags() }.getOrDefault(emptyMap()))

    /**
     * DXGI's adapters with DXCore's answers, by LUID. When DXCore answers, an adapter it leaves out is no graphics
     * hardware: a virtual display driver (a game-streaming host's screen) that DXGI shows under the GPU's own name.
     * DXCore needs Windows 10 2004 or later; without it every adapter stays and the names decide ([Adapter.dedicated]).
     */
    internal fun merge(adapters: List<Adapter>, flags: Map<Long, Pair<Boolean?, Boolean>>): List<Adapter> =
        if (flags.isEmpty()) adapters
        else adapters.mapNotNull { a -> flags[a.luid]?.let { (integrated, detachable) -> a.copy(integrated = integrated, detachable = detachable) } }

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
                            // AdapterLuid (LowPart, HighPart) at 296 reads as one little-endian 64-bit value.
                            if (!software) found += Adapter(desc.getWideString(0).trim(), vendor, desc.getLong(272), software = false, luid = desc.getLong(296))
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

    internal fun call(self: Pointer, slot: Int, vararg args: Any): Int {
        val method = self.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(method).invokeInt(arrayOf(self, *args))
    }

    internal fun guid(memory: Memory, data1: Int, data2: Int, data3: Int, vararg data4: Int): Memory = memory.apply {
        setInt(0, data1)
        setShort(4, data2.toShort())
        setShort(6, data3.toShort())
        write(8, ByteArray(8) { data4[it].toByte() }, 0, 8)
    }

    /**
     * Windows' own "integrated" and "detachable" answers for each D3D11 adapter, by LUID. Layouts and IDs from
     * Microsoft's DirectX-Headers `dxcore_interface.h` (MIT).
     */
    private object DxCore {
        // IDXCoreAdapterFactory: CreateAdapterList (3). IDXCoreAdapterList: GetAdapter (3), GetAdapterCount (4).
        // IDXCoreAdapter: IsValid (3), IsAttributeSupported (4), IsPropertySupported (5), GetProperty (6).
        private const val CREATE_ADAPTER_LIST = 3
        private const val GET_ADAPTER = 3
        private const val GET_ADAPTER_COUNT = 4
        private const val GET_PROPERTY = 6
        // DXCoreAdapterProperty
        private const val INSTANCE_LUID = 0
        private const val IS_INTEGRATED = 12
        private const val IS_DETACHABLE = 13

        fun flags(): Map<Long, Pair<Boolean?, Boolean>> {
            val create = NativeLibrary.getInstance("dxcore").getFunction("DXCoreCreateAdapterFactory")
            val factoryOut = PointerByReference()
            val result = HashMap<Long, Pair<Boolean?, Boolean>>()
            Memory(16).use { factoryIid ->
                guid(factoryIid, 0x78ee5945, 0xc36e, 0x4b13, 0xa6, 0x69, 0x00, 0x5d, 0xd1, 0x1c, 0x0f, 0x06)
                if (create.invokeInt(arrayOf(factoryIid, factoryOut)) < 0) return result
            }
            val factory = factoryOut.value ?: return result
            try {
                val listOut = PointerByReference()
                Memory(16).use { attribute ->
                    Memory(16).use { listIid ->
                        guid(attribute, 0x8c47866b.toInt(), 0x7583, 0x450d, 0xf0, 0xf0, 0x6b, 0xad, 0xa8, 0x95, 0xaf, 0x4b) // D3D11_GRAPHICS
                        guid(listIid, 0x526c7776, 0x40e9, 0x459b, 0xb7, 0x11, 0xf3, 0x2a, 0xd7, 0x6d, 0xfc, 0x28)
                        if (call(factory, CREATE_ADAPTER_LIST, 1, attribute, listIid, listOut) < 0) return result
                    }
                }
                val list = listOut.value ?: return result
                try {
                    val count = call(list, GET_ADAPTER_COUNT)
                    Memory(16).use { adapterIid ->
                        guid(adapterIid, 0xf0db4c7f.toInt(), 0xfe5a, 0x42a2, 0xbd, 0x62, 0xf2, 0xa6, 0xcf, 0x6f, 0xc8, 0x3e)
                        for (index in 0 until count.coerceIn(0, 16)) {
                            val adapterOut = PointerByReference()
                            if (call(list, GET_ADAPTER, index, adapterIid, adapterOut) < 0) continue
                            val adapter = adapterOut.value ?: continue
                            try {
                                Memory(8).use { value ->
                                    value.clear()
                                    if (call(adapter, GET_PROPERTY, INSTANCE_LUID, 8L, value) < 0) return@use
                                    val luid = value.getLong(0)
                                    fun flag(property: Int): Boolean? {
                                        value.clear()
                                        return if (call(adapter, GET_PROPERTY, property, 1L, value) >= 0) value.getByte(0).toInt() != 0 else null
                                    }
                                    result[luid] = flag(IS_INTEGRATED) to (flag(IS_DETACHABLE) == true)
                                }
                            } finally {
                                call(adapter, RELEASE)
                            }
                        }
                    }
                } finally {
                    call(list, RELEASE)
                }
            } finally {
                call(factory, RELEASE)
            }
            return result
        }
    }
}
