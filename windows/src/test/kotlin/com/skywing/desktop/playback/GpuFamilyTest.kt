package com.skywing.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Plan 5.8, kept short: which graphics chips get a note in Motion options, told by the name Windows lists. */
class GpuFamilyTest {
    private val struggle = "May struggle on this graphics chip above 1080p"
    private val uhd = SmoothingCapabilities(width = 3840, height = 2160)
    private val qhd = SmoothingCapabilities(width = 2560, height = 1440)
    private val fullHd = SmoothingCapabilities(width = 1920, height = 1080)

    private fun card(name: String, vendor: Int) = GpuAdapters.Adapter(name, vendor, dedicatedBytes = 0, software = false, integrated = false)
    private fun builtIn(name: String, vendor: Int) = GpuAdapters.Adapter(name, vendor, dedicatedBytes = 0, software = false, integrated = true)
    // Before Windows 10 2004 DXCore gives no answer, and the name tells a card from built-in graphics.
    private fun byName(name: String, vendor: Int) = GpuAdapters.Adapter(name, vendor, dedicatedBytes = 0, software = false)

    @Test
    fun graphicsCardsAndTheNewestBuiltInChipsGetNoNote() {
        listOf(
            card("NVIDIA GeForce RTX 5070 Ti Laptop GPU", GpuAdapters.NVIDIA),
            card("NVIDIA GeForce RTX 2060", GpuAdapters.NVIDIA),
            card("NVIDIA GeForce GTX 1660 SUPER", GpuAdapters.NVIDIA),
            card("NVIDIA GeForce GTX 1650", GpuAdapters.NVIDIA),
            card("AMD Radeon RX 7800 XT", GpuAdapters.AMD),
            card("AMD Radeon RX 6600", GpuAdapters.AMD),
            card("Intel(R) Arc(TM) B580 Graphics", GpuAdapters.INTEL),
            card("Intel(R) Arc(TM) A770 Graphics", GpuAdapters.INTEL),
            // Lunar Lake and Panther Lake held 4K at 2× on battery in M0; Strix Halo is a graphics card's size.
            builtIn("Intel(R) Arc(TM) 140V GPU", GpuAdapters.INTEL),
            builtIn("Intel(R) Arc(TM) B390 GPU", GpuAdapters.INTEL),
            builtIn("AMD Radeon(TM) 8060S Graphics", GpuAdapters.AMD),
            byName("Intel(R) Arc(TM) B580 Graphics", GpuAdapters.INTEL),
            byName("Intel(R) Arc(TM) B390 GPU", GpuAdapters.INTEL),
        ).forEach { assertNull(GpuFamily.note(it, uhd), it.name) }
    }

    @Test
    fun olderCardsAndOtherBuiltInGraphicsMayStruggleAbove1080p() {
        listOf(
            card("NVIDIA GeForce GTX 1080", GpuAdapters.NVIDIA),
            card("NVIDIA GeForce GTX 970M", GpuAdapters.NVIDIA),
            card("NVIDIA GeForce GTX 1060 with Max-Q Design", GpuAdapters.NVIDIA),
            card("NVIDIA GeForce GTX 580", GpuAdapters.NVIDIA),
            card("NVIDIA GeForce GTX 295", GpuAdapters.NVIDIA),
            card("NVIDIA GeForce MX450", GpuAdapters.NVIDIA),
            card("NVIDIA GeForce 940MX", GpuAdapters.NVIDIA),
            card("Radeon RX 580 Series", GpuAdapters.AMD),
            card("AMD Radeon RX 570", GpuAdapters.AMD),
            card("AMD Radeon RX 5700 XT", GpuAdapters.AMD),
            card("Radeon RX Vega", GpuAdapters.AMD),
            card("AMD Radeon R9 200 Series", GpuAdapters.AMD),
            builtIn("Intel(R) Iris(R) Xe Graphics", GpuAdapters.INTEL),
            builtIn("Intel(R) UHD Graphics 620", GpuAdapters.INTEL),
            builtIn("Intel(R) HD Graphics 4600", GpuAdapters.INTEL),
            // The names that don't tell the family: still built in, so the same note.
            builtIn("Intel(R) Graphics", GpuAdapters.INTEL),
            builtIn("Intel(R) Arc(TM) Graphics", GpuAdapters.INTEL),
            builtIn("Intel(R) Arc(TM) 140T GPU", GpuAdapters.INTEL),
            builtIn("AMD Radeon(TM) Graphics", GpuAdapters.AMD),
            builtIn("AMD Radeon 780M Graphics", GpuAdapters.AMD),
            builtIn("AMD Radeon(TM) RX Vega 11 Graphics", GpuAdapters.AMD),
            byName("Intel(R) Iris(R) Xe Graphics", GpuAdapters.INTEL),
            byName("AMD Radeon(TM) Graphics", GpuAdapters.AMD),
        ).forEach {
            assertEquals(struggle, GpuFamily.note(it, uhd), it.name)
            assertEquals(struggle, GpuFamily.note(it, qhd), it.name)
            assertNull(GpuFamily.note(it, fullHd), it.name)
            assertNull(GpuFamily.note(it, SmoothingCapabilities()), it.name + ": size not known yet")
        }
    }

    @Test
    fun otherMakersAndVirtualMachinesAreNotTested() {
        listOf(
            GpuAdapters.Adapter("VMware SVGA 3D", 0x15AD, dedicatedBytes = 0, software = false),
            GpuAdapters.Adapter("VirtualBox Graphics Adapter (WDDM)", 0x80EE, dedicatedBytes = 0, software = false),
            GpuAdapters.Adapter("Parallels Display Adapter (WDDM)", 0x1AB8, dedicatedBytes = 0, software = false),
            GpuAdapters.Adapter("Moore Threads MTT S80", 0x1ED5, dedicatedBytes = 0, software = false),
        ).forEach {
            assertEquals("Not tested on this graphics chip", GpuFamily.note(it, fullHd), it.name)
            assertEquals("Not tested on this graphics chip", GpuFamily.note(it, uhd), it.name)
        }
    }

    @Test
    fun noKnownGpuOrTheSoftwareRendererGetsNoNote() {
        assertNull(GpuFamily.note(null, uhd))
        // With WARP drawing, "Install your graphics driver" says it; a hardware GPU's note would describe the wrong chip.
        assertNull(GpuFamily.note(builtIn("Intel(R) UHD Graphics 620", GpuAdapters.INTEL), uhd.copy(softwareRender = true)))
    }
}
