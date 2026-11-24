package com.skywing.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
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
        // Plan 5.8 rule 7: each hardware adapter's driver version, as Device Manager shows it.
        val version = Regex("""\d+\.\d+\.\d+\.\d+""")
        adapters.forEach { assertTrue(it.driver?.let(version::matches) == true, "${it.name}: driver ${it.driver}") }
        println("Graphics drivers: " + adapters.joinToString { "${it.name} ${it.driver}" })
    }

    @Test
    fun aDriverVersionReadsAsDeviceManagerShowsIt() {
        // DXGI packs the user-mode driver version as four 16-bit parts, highest first.
        assertEquals("32.0.15.6094", GpuAdapters.driverVersion((32L shl 48) or (0L shl 32) or (15L shl 16) or 6094L))
        assertEquals("32.0.101.6881", GpuAdapters.driverVersion((32L shl 48) or (101L shl 16) or 6881L))
        assertEquals("65535.0.0.1", GpuAdapters.driverVersion((0xffffL shl 48) or 1L))
    }

    @Test
    fun microsoftsSoftwareRendererAndRemoteDesktopAreSoftwareRendering() {
        // Plan 5.8 rule 3: mpv's last step is WARP (d3d11-warp=auto), which DXGI names Microsoft Basic Render Driver.
        listOf("Microsoft Basic Render Driver", "Microsoft Basic Display Adapter", "Microsoft Remote Display Adapter",
            "microsoft basic render driver").forEach { assertTrue(GpuAdapters.softwareRenderer(it), it) }
        listOf("NVIDIA GeForce RTX 5070 Ti Laptop GPU", "Intel(R) Arc(TM) 140V GPU (16GB)", "", null)
            .forEach { assertFalse(GpuAdapters.softwareRenderer(it), "$it") }
    }
}
