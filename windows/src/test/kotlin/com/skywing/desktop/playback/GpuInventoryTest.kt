package com.skywing.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuInventoryTest {
    private fun gpu(name: String, vendor: Int, luid: Long, integrated: Boolean? = null, detachable: Boolean = false) =
        GpuAdapters.Adapter(name, vendor, dedicatedBytes = 0, software = false, luid = luid, integrated = integrated, detachable = detachable)

    private val intel = gpu("Intel(R) Arc(TM) 140V GPU (16GB)", GpuAdapters.INTEL, 1, integrated = true)
    private val nvidia = gpu("NVIDIA GeForce RTX 5070 Ti Laptop GPU", GpuAdapters.NVIDIA, 2, integrated = false)

    @Test
    fun theVendorComesFromThePciIdNotTheName() {
        assertEquals(GpuVendor.NVIDIA, GpuVendor.of(0x10DE))
        assertEquals(GpuVendor.AMD, GpuVendor.of(0x1002))
        assertEquals(GpuVendor.INTEL, GpuVendor.of(0x8086))
        assertEquals(GpuVendor.OTHER, GpuVendor.of(0x4D4F4351))
        assertEquals(GpuVendor.AMD, gpu("Some renamed card", 0x1002, 3).gpuVendor)
    }

    @Test
    fun eachVendorNamesTheTextItsOpenClDriverReports() {
        // The patched open-svpflow keeps an OpenCL device whose vendor contains this text (B5).
        assertEquals("NVIDIA", GpuVendor.NVIDIA.openCl)
        assertEquals("Advanced Micro Devices", GpuVendor.AMD.openCl)
        assertEquals("Intel", GpuVendor.INTEL.openCl)
        assertNull(GpuVendor.OTHER.openCl)
    }

    @Test
    fun windowsSayingIntegratedWinsOverTheName() {
        // A desktop Arc card with a plain name, and built-in graphics Windows calls integrated.
        assertTrue(gpu("Intel(R) Arc(TM) Graphics", GpuAdapters.INTEL, 4, integrated = false).dedicated)
        assertFalse(gpu("NVIDIA GeForce RTX 5070 Ti Laptop GPU", GpuAdapters.NVIDIA, 5, integrated = true).dedicated)
        // Without Windows' answer, the name decides as before.
        assertFalse(gpu("Intel(R) Arc(TM) Graphics", GpuAdapters.INTEL, 6).dedicated)
        assertTrue(gpu("NVIDIA GeForce GTX 1650", GpuAdapters.NVIDIA, 7).dedicated)
    }

    @Test
    fun virtualDisplayDriversAreNotCountedAsGpus() {
        // A streaming host's virtual display (Sunshine, SudoMaker) shows in DXGI under the GPU's own name; DXCore,
        // which lists only graphics hardware, leaves it out.
        val virtual = gpu("Intel(R) Graphics", GpuAdapters.INTEL, 30)
        val real = listOf(intel.copy(integrated = null), nvidia.copy(integrated = null), virtual)
        val merged = GpuAdapters.merge(real, mapOf(1L to (true to false), 2L to (false to false)))
        assertEquals(listOf(1L, 2L), merged.map { it.luid })
        assertEquals(listOf(true, false), merged.map { it.integrated })
        // Without DXCore (before Windows 10 2004) every DXGI adapter stays, told apart by name.
        assertEquals(real, GpuAdapters.merge(real, emptyMap()))
    }

    @Test
    fun theModeSaysWhichGpuDrivesTheScreen() {
        assertEquals(GpuMode.INTEGRATED_ONLY, GpuInventory(listOf(intel)).mode(intel))
        assertEquals(GpuMode.DEDICATED_ONLY, GpuInventory(listOf(nvidia)).mode(nvidia))
        assertEquals(GpuMode.HYBRID, GpuInventory(listOf(intel, nvidia)).mode(intel))
        assertEquals(GpuMode.DEDICATED, GpuInventory(listOf(intel, nvidia)).mode(nvidia))
        assertEquals(GpuMode.HYBRID, GpuInventory(listOf(intel, nvidia)).mode(null))
        assertEquals(GpuMode.UNKNOWN, GpuInventory(emptyList()).mode(null))
    }

    @Test
    fun twoGpusFromOneVendorGetTheSameLabelsAsAnyPair() {
        val arc = gpu("Intel(R) Arc(TM) B580 Graphics", GpuAdapters.INTEL, 8, integrated = false)
        val inventory = GpuInventory(listOf(intel, arc))
        assertEquals(GpuMode.HYBRID, inventory.mode(intel))
        assertEquals(GpuMode.DEDICATED, inventory.mode(arc))
        assertEquals(arc, inventory.byLuid(8))
        assertNull(inventory.byLuid(99))
    }

    @Test
    fun anExternalGpuIsDedicatedAndNamed() {
        val dock = gpu("AMD Radeon RX 7600M XT", GpuAdapters.AMD, 9, integrated = false, detachable = true)
        val inventory = GpuInventory(listOf(intel, dock))
        assertTrue(dock.dedicated)
        assertTrue(inventory.externalGpu)
        assertEquals("Hybrid · external GPU", inventory.label(intel))
        assertEquals("Integrated only", GpuInventory(listOf(intel)).label(intel))
    }

    @Test
    fun windowsGraphicsSettingIsReadFromItsRegistryText() {
        assertEquals(WindowsGpuPreference.HIGH_PERFORMANCE, WindowsGraphicsSetting.parse("GpuPreference=2;"))
        assertEquals(WindowsGpuPreference.POWER_SAVING, WindowsGraphicsSetting.parse("SwapEffectUpgradeEnable=1;GpuPreference=1;"))
        assertEquals(WindowsGpuPreference.NONE, WindowsGraphicsSetting.parse("GpuPreference=0;"))
        assertEquals(WindowsGpuPreference.NONE, WindowsGraphicsSetting.parse("SwapEffectUpgradeEnable=1;"))
        assertEquals(WindowsGpuPreference.NONE, WindowsGraphicsSetting.parse(null))
    }

    @Test
    fun windowsListsEachAdapterWithItsLuid() {
        val adapters = GpuAdapters.list()
        assertEquals(adapters.size, adapters.map { it.luid }.distinct().size, "every adapter has its own LUID")
        assertTrue(adapters.all { it.luid != 0L })
        // Windows 10 2004 and later answer for every hardware adapter; a CI machine may list none.
        assertTrue(adapters.isEmpty() || adapters.all { it.integrated != null }, "DXCore answered for $adapters")
    }

    @Test
    fun everyActiveScreenNamesItsAdapterAndRefresh() {
        DisplayPaths.active().forEach {
            assertTrue(it.gdiName.startsWith("\\\\.\\DISPLAY"), it.gdiName)
            assertTrue(it.refreshHz == null || it.refreshHz > 0)
        }
    }
}
