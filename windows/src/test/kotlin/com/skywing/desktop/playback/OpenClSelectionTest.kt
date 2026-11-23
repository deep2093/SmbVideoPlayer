package com.skywing.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun thePlansGpuIsUsedWhenItHasAnOpenClDevice() {
        val engine = OpenClDevice.choose(listOf(intelCl, arcCl), arcCard, byLuid = true)
        assertEquals(arcCl, engine.device)
        assertEquals(OpenClDevice.filter(arcCard, byLuid = true), engine.filter)
        assertEquals("LUID", engine.matchedBy)
        assertFalse(engine.processor)
        assertEquals("maker", OpenClDevice.choose(listOf(intelCl, nvidiaCl), nvidiaGpu, byLuid = false).matchedBy)
        assertEquals("any device", OpenClDevice.choose(listOf(nvidiaCl), null, byLuid = false).matchedBy)
    }

    @Test
    fun aGpuWithoutAnOpenClDeviceSmoothsOnAnotherGpu() {
        // Plan 5.8 rule 4: OpenCL on the plan's GPU, then on any other GPU. The filter names that device exactly.
        val toNvidia = OpenClDevice.choose(listOf(nvidiaCl), intelGpu, byLuid = false)
        assertEquals(nvidiaCl, toNvidia.device)
        assertEquals(mapOf("SKYWING_OPENCL_VENDOR" to "NVIDIA Corporation", "SKYWING_OPENCL_LUID" to ""), toNvidia.filter)
        assertEquals("another GPU (Intel(R) Graphics has no OpenCL device)", toNvidia.matchedBy)
        // Two GPUs from one maker: the other one, by its LUID.
        val toIntegrated = OpenClDevice.choose(listOf(intelCl), arcCard, byLuid = true)
        assertEquals(intelCl, toIntegrated.device)
        assertEquals(mapOf("SKYWING_OPENCL_VENDOR" to "Intel(R) Corporation", "SKYWING_OPENCL_LUID" to "0000000000001001"), toIntegrated.filter)
        assertFalse(toIntegrated.processor)
    }

    @Test
    fun noOpenClDeviceAtAllSmoothsOnTheProcessor() {
        for (adapter in listOf(intelGpu, null)) {
            val engine = OpenClDevice.choose(emptyList(), adapter, byLuid = false)
            assertTrue(engine.processor)
            assertNull(engine.device)
            assertEquals("none (no OpenCL device: the processor)", engine.matchedBy)
            // No vendor contains this name, so the plugins skip any device a failed listing missed.
            assertEquals(mapOf("SKYWING_OPENCL_VENDOR" to "skywing-no-opencl-device", "SKYWING_OPENCL_LUID" to ""), engine.filter)
        }
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
