package com.skywing.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenClSelectionTest {
    private val intelGpu = GpuAdapters.Adapter("Intel(R) Graphics", GpuAdapters.INTEL, 0, false, luid = 0x1001, integrated = true)
    private val arcCard = GpuAdapters.Adapter("Intel(R) Arc(TM) B580 Graphics", GpuAdapters.INTEL, 0, false, luid = 0x2002, integrated = false)
    private val nvidiaGpu = GpuAdapters.Adapter("NVIDIA GeForce RTX 5070 Ti Laptop GPU", GpuAdapters.NVIDIA, 0, false, luid = 0x3003, integrated = false)

    private val intelCl = OpenClDevice("Intel(R) Corporation", "Intel(R) Graphics", "32.0.101", luid = 0x1001)
    private val arcCl = OpenClDevice("Intel(R) Corporation", "Intel(R) Arc(TM) B580 Graphics", "32.0.101", luid = 0x2002)
    private val nvidiaCl = OpenClDevice("NVIDIA Corporation", "NVIDIA GeForce RTX 5070 Ti Laptop GPU", "591.44", luid = null)

    @Test
    fun theMakerPicksTheDeviceWhenOnlyOneGpuIsFromIt() {
        assertEquals(nvidiaCl, OpenClDevice.pick(listOf(intelCl, nvidiaCl), nvidiaGpu, byLuid = false))
        assertEquals(intelCl, OpenClDevice.pick(listOf(nvidiaCl, intelCl), intelGpu, byLuid = false))
    }

    @Test
    fun twoGpusFromOneMakerArePickedByLuid() {
        assertEquals(arcCl, OpenClDevice.pick(listOf(intelCl, arcCl), arcCard, byLuid = true))
        assertEquals(intelCl, OpenClDevice.pick(listOf(arcCl, intelCl), intelGpu, byLuid = true))
    }

    @Test
    fun aDriverWithoutLuidsFallsBackToTheMaker() {
        val noLuids = listOf(intelCl.copy(luid = null), arcCl.copy(luid = null))
        assertEquals(noLuids[0], OpenClDevice.pick(noLuids, arcCard, byLuid = true))
    }

    @Test
    fun aMissingLuidNeverFallsBackToTheOtherGpuOfThatMaker() {
        // Both Intel devices report LUIDs and neither is the card's: smoothing on the other GPU would mix devices.
        assertNull(OpenClDevice.pick(listOf(intelCl), arcCard, byLuid = true))
    }

    @Test
    fun aGpuWithoutAnOpenClDeviceGetsNone() {
        assertNull(OpenClDevice.pick(listOf(intelCl), nvidiaGpu, byLuid = false))
        assertNull(OpenClDevice.pick(emptyList(), intelGpu, byLuid = false))
    }

    @Test
    fun theFilterTellsThePatchedPluginsWhichDeviceToUse() {
        assertEquals(mapOf("SKYWING_OPENCL_VENDOR" to "NVIDIA", "SKYWING_OPENCL_LUID" to ""), OpenClDevice.filter(nvidiaGpu, byLuid = false))
        assertEquals(mapOf("SKYWING_OPENCL_VENDOR" to "Intel", "SKYWING_OPENCL_LUID" to "0000000000002002"), OpenClDevice.filter(arcCard, byLuid = true))
        // Windows' choice: no filter, any device.
        assertEquals(mapOf("SKYWING_OPENCL_VENDOR" to "", "SKYWING_OPENCL_LUID" to ""), OpenClDevice.filter(null, byLuid = false))
    }

    @Test
    fun theOpenClDriverListsItsDevicesWithOrWithoutLuids() {
        // Live: whatever OpenCL drivers this PC has. Each device names its maker; a LUID, when reported, is nonzero.
        val devices = OpenClDevice.list()
        val adapters = GpuAdapters.list().map { it.luid }.toSet()
        devices.forEach {
            assertTrue(it.vendor.isNotBlank(), "$it")
            // A reported LUID is the same one DXGI gives that GPU, or the query reads the wrong field.
            assertTrue(it.luid == null || it.luid in adapters, "$it LUID not among DXGI's adapters")
        }
        println("OpenCL devices: " + devices.joinToString { "${it.vendor} / LUID ${if (it.luid == null) "not reported" else "reported"}" })
    }
}
