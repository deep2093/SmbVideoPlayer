package com.skywing.desktop.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B24: telling a graphics card from graphics built into the processor, by the adapter Windows lists. */
class GpuAdaptersTest {
    private fun adapter(name: String, vendor: Int, software: Boolean = false) =
        GpuAdapters.Adapter(name, vendor, dedicatedBytes = 0, software = software)

    @Test
    fun nvidiaCardsAreDedicated() {
        assertTrue(adapter("NVIDIA GeForce RTX 5070 Ti Laptop GPU", GpuAdapters.NVIDIA).dedicated)
        assertTrue(adapter("NVIDIA GeForce GTX 1650", GpuAdapters.NVIDIA).dedicated)
    }

    @Test
    fun intelArcCardsAreDedicatedButArcGraphicsInTheProcessorIsNot() {
        assertTrue(adapter("Intel(R) Arc(TM) A770 Graphics", GpuAdapters.INTEL).dedicated)
        assertTrue(adapter("Intel(R) Arc(TM) A380 Graphics", GpuAdapters.INTEL).dedicated)
        assertTrue(adapter("Intel(R) Arc(TM) B580 Graphics", GpuAdapters.INTEL).dedicated)
        assertTrue(adapter("Intel(R) Arc(TM) Pro B50 Graphics", GpuAdapters.INTEL).dedicated)
        assertFalse(adapter("Intel(R) Arc(TM) 140V GPU (16GB)", GpuAdapters.INTEL).dedicated)
        assertFalse(adapter("Intel(R) Arc(TM) B390 GPU", GpuAdapters.INTEL).dedicated)
        assertFalse(adapter("Intel(R) Arc(TM) Graphics", GpuAdapters.INTEL).dedicated)
        assertFalse(adapter("Intel(R) Iris(R) Xe Graphics", GpuAdapters.INTEL).dedicated)
        assertFalse(adapter("Intel(R) UHD Graphics 770", GpuAdapters.INTEL).dedicated)
    }

    @Test
    fun radeonRxAndProCardsAreDedicatedButRadeonGraphicsInTheProcessorIsNot() {
        assertTrue(adapter("AMD Radeon RX 7600M XT", GpuAdapters.AMD).dedicated)
        assertTrue(adapter("AMD Radeon(TM) RX 9070 XT", GpuAdapters.AMD).dedicated)
        assertTrue(adapter("AMD Radeon PRO W7600", GpuAdapters.AMD).dedicated)
        assertFalse(adapter("AMD Radeon(TM) 890M Graphics", GpuAdapters.AMD).dedicated)
        assertFalse(adapter("AMD Radeon(TM) Graphics", GpuAdapters.AMD).dedicated)
        assertFalse(adapter("AMD Radeon RX Vega 11 Graphics", GpuAdapters.AMD).dedicated)
    }

    @Test
    fun theSoftwareRendererAndOtherMakersAreNotDedicated() {
        assertFalse(adapter("Microsoft Basic Render Driver", 0x1414, software = true).dedicated)
        assertFalse(adapter("NVIDIA GeForce RTX 4090", GpuAdapters.NVIDIA, software = true).dedicated)
        assertFalse(adapter("Qualcomm(R) Adreno(TM) X1-85 GPU", 0x4D4F4351).dedicated)
    }

    @Test
    fun windowsListsItsAdaptersWithoutTheSoftwareRenderer() {
        val adapters = GpuAdapters.list()
        assertTrue(adapters.none { it.software }, "the software renderer is left out")
        assertTrue(adapters.all { it.name.isNotBlank() })
    }
}
