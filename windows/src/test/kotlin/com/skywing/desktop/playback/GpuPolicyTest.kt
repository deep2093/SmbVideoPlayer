package com.skywing.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuPolicyTest {
    private fun gpu(name: String, vendor: Int, luid: Long, integrated: Boolean, memory: Long = 0, detachable: Boolean = false) =
        GpuAdapters.Adapter(name, vendor, memory, software = false, luid = luid, integrated = integrated, detachable = detachable)

    private val intel = gpu("Intel(R) Graphics", GpuAdapters.INTEL, 1, integrated = true, memory = 128L shl 20)
    private val nvidia = gpu("NVIDIA GeForce RTX 5070 Ti Laptop GPU", GpuAdapters.NVIDIA, 2, integrated = false, memory = 12L shl 30)
    private val laptop = GpuInventory(listOf(intel, nvidia))
    private val arc = gpu("Intel(R) Arc(TM) B390 GPU", GpuAdapters.INTEL, 3, integrated = true)

    private fun plan(inventory: GpuInventory, monitor: GpuAdapters.Adapter?, choice: GpuChoice = GpuChoice.AUTOMATIC, forced: GpuVendor? = null) =
        GpuPolicy.plan(inventory, monitor, choice, forced)

    @Test
    fun oneGpuIsUsedForEverything() {
        val handheld = GpuInventory(listOf(arc))
        GpuChoice.entries.forEach { choice ->
            val p = plan(handheld, arc, choice)
            assertEquals(arc, p.adapter, "$choice")
            assertEquals("Only one GPU", p.reason)
        }
    }

    @Test
    fun automaticKeepsTodaysRoutingToTheScreensGpu() {
        val hybrid = plan(laptop, intel)
        assertEquals(intel, hybrid.adapter)
        assertEquals(GpuMode.HYBRID, hybrid.mode)
        assertEquals("The screen is on this GPU", hybrid.reason)
        val dedicated = plan(laptop, nvidia)
        assertEquals(nvidia, dedicated.adapter)
        assertEquals(GpuMode.DEDICATED, dedicated.mode)
    }

    @Test
    fun automaticWithAnUnknownScreenPrefersTheDedicatedGpuAsBefore() {
        // Before M2 SkyWing asked mpv for "NVIDIA" when it couldn't tell the screen's GPU.
        val p = plan(laptop, null)
        assertEquals(nvidia, p.adapter)
        assertEquals("The screen's GPU is unknown", p.reason)
    }

    @Test
    fun performanceAndPowerSavingPickTheirKindOfGpu() {
        assertEquals(nvidia, plan(laptop, intel, GpuChoice.PERFORMANCE).adapter)
        assertEquals("Performance, chosen in Settings", plan(laptop, intel, GpuChoice.PERFORMANCE).reason)
        assertEquals(intel, plan(laptop, nvidia, GpuChoice.POWER_SAVING).adapter)
        assertEquals("Power saving, chosen in Settings", plan(laptop, nvidia, GpuChoice.POWER_SAVING).reason)
    }

    @Test
    fun performancePicksTheLargestDedicatedGpuUnlessTheScreenIsOnOne() {
        val small = gpu("AMD Radeon RX 6500M", GpuAdapters.AMD, 4, integrated = false, memory = 4L shl 30)
        val inventory = GpuInventory(listOf(intel, small, nvidia))
        assertEquals(nvidia, plan(inventory, intel, GpuChoice.PERFORMANCE).adapter)
        assertEquals(small, plan(inventory, small, GpuChoice.PERFORMANCE).adapter)
    }

    @Test
    fun windowsGraphicsSettingIsFollowedOnlyInAutomatic() {
        val windowsHigh = laptop.copy(windowsPreference = WindowsGpuPreference.HIGH_PERFORMANCE)
        assertEquals(nvidia, plan(windowsHigh, intel).adapter)
        assertEquals("Following Windows' graphics setting", plan(windowsHigh, intel).reason)
        val windowsLow = laptop.copy(windowsPreference = WindowsGpuPreference.POWER_SAVING)
        assertEquals(intel, plan(windowsLow, nvidia).adapter)
        // A choice made in SkyWing wins over Windows' setting.
        assertEquals(intel, plan(windowsHigh, intel, GpuChoice.POWER_SAVING).adapter)
    }

    @Test
    fun theTestOverrideStillForcesAVendor() {
        val p = plan(laptop, intel, GpuChoice.POWER_SAVING, forced = GpuVendor.NVIDIA)
        assertEquals(nvidia, p.adapter)
        assertEquals("Forced by SKYWING_GPU", p.reason)
        // A vendor the PC doesn't have falls back to the normal plan.
        assertEquals(intel, plan(laptop, intel, forced = GpuVendor.AMD).adapter)
    }

    @Test
    fun noAdaptersLeavesTheChoiceToWindows() {
        val p = plan(GpuInventory(emptyList()), null)
        assertNull(p.adapter)
        assertEquals("", p.mpvAdapter)
        assertEquals("Using Windows' choice of graphics", p.reason)
    }

    @Test
    fun mpvGetsTheExactNameUnlessAnEarlierAdapterStartsWithIt() {
        assertEquals("NVIDIA GeForce RTX 5070 Ti Laptop GPU", plan(laptop, nvidia).mpvAdapter)
        // mpv takes the first adapter whose name starts with the text it is given.
        val ti = gpu("NVIDIA GeForce RTX 4060 Ti", GpuAdapters.NVIDIA, 5, integrated = false, memory = 8L shl 30)
        val plain = gpu("NVIDIA GeForce RTX 4060", GpuAdapters.NVIDIA, 6, integrated = false, memory = 8L shl 30)
        val desk = GpuInventory(listOf(ti, plain))
        val p = GpuPolicy.plan(desk, plain, GpuChoice.AUTOMATIC)
        assertEquals("", p.mpvAdapter)
        assertTrue(p.reason.endsWith("Windows chooses: another GPU's name starts the same"), p.reason)
        assertEquals("NVIDIA GeForce RTX 4060 Ti", GpuPolicy.plan(desk, ti, GpuChoice.AUTOMATIC).mpvAdapter)
    }

    @Test
    fun twoGpusFromOneVendorAreMatchedByLuid() {
        val card = gpu("Intel(R) Arc(TM) B580 Graphics", GpuAdapters.INTEL, 7, integrated = false, memory = 12L shl 30)
        assertTrue(plan(GpuInventory(listOf(intel, card)), intel).matchByLuid)
        assertFalse(plan(laptop, intel).matchByLuid)
    }

    @Test
    fun theDedicatedOfferAppearsOnlyForAutomaticOnTheIntegratedGpu() {
        assertEquals(nvidia, GpuPolicy.smoothingOffer(laptop, plan(laptop, intel), GpuChoice.AUTOMATIC))
        assertNull(GpuPolicy.smoothingOffer(laptop, plan(laptop, nvidia), GpuChoice.AUTOMATIC))
        assertNull(GpuPolicy.smoothingOffer(laptop, plan(laptop, intel, GpuChoice.POWER_SAVING), GpuChoice.POWER_SAVING))
        assertNull(GpuPolicy.smoothingOffer(GpuInventory(listOf(arc)), plan(GpuInventory(listOf(arc)), arc), GpuChoice.AUTOMATIC))
    }

    @Test
    fun theOfferNamesTheGpuByItsMaker() {
        assertEquals("Use NVIDIA for smoothing", GpuPolicy.offerLabel(nvidia))
        assertEquals("Use AMD Radeon for smoothing", GpuPolicy.offerLabel(gpu("AMD Radeon RX 7600M XT", GpuAdapters.AMD, 8, integrated = false)))
        assertEquals("Use Intel Arc for smoothing", GpuPolicy.offerLabel(gpu("Intel(R) Arc(TM) B580 Graphics", GpuAdapters.INTEL, 9, integrated = false)))
    }

    @Test
    fun theReportLineNamesModeGpuPowerAndReason() {
        assertEquals("Hybrid · drawing and motion on Intel(R) Graphics · plugged in · The screen is on this GPU",
            plan(laptop, intel).describe(onBattery = false))
        assertEquals("Graphics unknown · drawing and motion on Windows' choice · on battery · Using Windows' choice of graphics",
            plan(GpuInventory(emptyList()), null).describe(onBattery = true))
    }

    @Test
    fun theGraphicsChoiceIsSavedAndAVideoClassCanKeepTheDedicatedGpu() {
        val node = java.util.prefs.Preferences.userRoot().node("skywing-test-" + java.util.UUID.randomUUID())
        try {
            val store = MotionPreferences(node)
            assertEquals(GpuChoice.AUTOMATIC, store.gpuChoice)
            store.gpuChoice = GpuChoice.POWER_SAVING
            assertEquals(GpuChoice.POWER_SAVING, MotionPreferences(node).gpuChoice)
            store.gpuChoice = GpuChoice.AUTOMATIC
            // "Use NVIDIA for smoothing" is remembered for that video class only.
            store.useDedicatedFor("4k-sdr")
            assertEquals(GpuChoice.PERFORMANCE, store.gpuChoiceFor("4k-sdr"))
            assertEquals(GpuChoice.AUTOMATIC, store.gpuChoiceFor("1080-sdr"))
            // A choice made in Settings wins over a class's memory.
            store.gpuChoice = GpuChoice.POWER_SAVING
            assertEquals(GpuChoice.POWER_SAVING, store.gpuChoiceFor("4k-sdr"))
            node.put("playback.gpu", "NOT-A-CHOICE")
            assertEquals(GpuChoice.AUTOMATIC, store.gpuChoice)
        } finally { node.removeNode() }
    }

    @Test
    fun aChangeIsTakenOnlyAfterItHoldsForFiveSeconds() {
        val debounce = Debounce<String>(5_000)
        assertEquals("a", debounce.offer("a", 0))
        assertNull(debounce.offer("b", 1_000))
        assertNull(debounce.offer("a", 2_000)) // back to the taken value: nothing to do
        assertNull(debounce.offer("b", 3_000))
        assertNull(debounce.offer("b", 7_999))
        assertEquals("b", debounce.offer("b", 8_000))
        assertNull(debounce.offer("b", 20_000))
    }

    @Test
    fun onlyLibplacebosDeviceLostErrorCountsAsALostDevice() {
        // libplacebo's d3d11/common.c logs this once at PL_ERR when D3D11 says the device was removed, reset or hung.
        assertTrue(DeviceLoss.message("error", "Device lost!\n"))
        assertTrue(DeviceLoss.message("fatal", "Device lost!"))
        assertFalse(DeviceLoss.message("warn", "Device lost!\n"))
        // mpv logs this for any frame it could not show, whatever the cause.
        assertFalse(DeviceLoss.message("error", "Failed presenting frame!\n"))
        assertFalse(DeviceLoss.message("error", "Device lost! Retrying\n"))
    }

    @Test
    fun eachLossWithinAMinuteStepsDownOnceAndThenStops() {
        val loss = DeviceLoss()
        assertEquals("never", loss.summary())
        assertEquals(DeviceLoss.Recovery.REPLAN, loss.lost(0))
        assertEquals(DeviceLoss.Recovery.WINDOWS_CHOICE, loss.lost(10_000))
        assertEquals(DeviceLoss.Recovery.GIVE_UP, loss.lost(20_000))
        assertEquals(DeviceLoss.Recovery.GIVE_UP, loss.lost(30_000))
        assertEquals(4, loss.count)
        assertTrue(loss.summary().startsWith("4 times; last: "), loss.summary())
        // A minute after the last loss the ladder starts again: a driver update days apart is a fresh plan each time.
        assertEquals(DeviceLoss.Recovery.REPLAN, loss.lost(90_000))
        assertEquals(DeviceLoss.Recovery.REPLAN, loss.lost(160_000))
        assertEquals(DeviceLoss.Recovery.WINDOWS_CHOICE, loss.lost(219_999))
    }
}
