package com.skywing.desktop.playback

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan 5.4: what a screen says about its refresh, variable refresh and HDR. EDIDs here are synthetic. */
class ScreenStateTest {
    private fun base(minor: Int = 4, range: IntArray? = null, extensions: Int = 0): ByteArray {
        val edid = ByteArray(128)
        byteArrayOf(0, -1, -1, -1, -1, -1, -1, 0).copyInto(edid)
        edid[18] = 1
        edid[19] = minor.toByte()
        // Display Range Limits descriptor in the first of the four 18-byte slots: 00 00 00 FD, offsets, min, max.
        range?.let { (flags, min, max) ->
            edid[57] = 0xFD.toByte(); edid[58] = flags.toByte(); edid[59] = min.toByte(); edid[60] = max.toByte()
        }
        edid[126] = extensions.toByte()
        return edid
    }

    private fun block(vararg bytes: Int) = bytes

    /** A CTA-861 extension holding [blocks] as its data block collection. */
    private fun cta(vararg blocks: IntArray): ByteArray {
        val extension = ByteArray(128)
        extension[0] = 0x02; extension[1] = 0x03
        var at = 4
        blocks.forEach { b -> b.forEachIndexed { i, v -> extension[at + i] = v.toByte() }; at += b.size }
        extension[2] = at.toByte()
        return extension
    }

    /** A DisplayID 2.0 extension holding [blocks]. */
    private fun displayId(vararg blocks: IntArray): ByteArray {
        val extension = ByteArray(128)
        extension[0] = 0x70; extension[1] = 0x20
        var at = 5
        blocks.forEach { b -> b.forEachIndexed { i, v -> extension[at + i] = v.toByte() }; at += b.size }
        extension[2] = (at - 5).toByte()
        return extension
    }

    private val freeSync = block(0x68, 0x1A, 0x00, 0x00, 2, 0, 48, 144, 0)

    @Test
    fun rangeLimitsAloneAreNotVariableRefresh() {
        val edid = Edid.parse(base(range = intArrayOf(0, 56, 76)))
        assertNull(edid.vrr)
        assertEquals(56..76, edid.limits)
    }

    @Test
    fun edidOnePointFourAddsTwoHundredFiftyFiveWhenTheFlagsSaySo() {
        assertEquals(48..260, Edid.parse(base(range = intArrayOf(0x02, 48, 5))).limits)
        assertEquals(256..260, Edid.parse(base(range = intArrayOf(0x03, 1, 5))).limits)
        // EDID 1.3 has no offsets: the same flags mean nothing there.
        assertEquals(50..75, Edid.parse(base(minor = 3, range = intArrayOf(0x02, 50, 75))).limits)
    }

    @Test
    fun theFreeSyncBlockGivesTheRange() {
        val edid = Edid.parse(base(range = intArrayOf(0, 48, 144), extensions = 1) + cta(freeSync))
        assertEquals(VrrRange(48, 144, "FreeSync"), edid.vrr)
    }

    @Test
    fun freeSyncVersionThreeKeepsItsMaximumInTenBits() {
        val v3 = block(0x6F, 0x1A, 0x00, 0x00, 3, 0, 48, 0xFF, 0, 0, 0, 0, 0, 0, 0x2C, 0x01)
        assertEquals(VrrRange(48, 300, "FreeSync"), Edid.parse(base(extensions = 1) + cta(v3)).vrr)
    }

    @Test
    fun hdmiForumVrrReadsFromTheVsdbAndTheScdb() {
        // VRRmax 0: up to the mode's own refresh.
        val vsdb = block(0x6A, 0xD8, 0x5D, 0xC4, 1, 0x78, 0, 0, 0, 40, 0)
        assertEquals(VrrRange(40, null, "HDMI VRR"), Edid.parse(base(extensions = 1) + cta(vsdb)).vrr)
        // VRRmax 300 keeps its top two bits in VRRmin's byte.
        val scdb = block(0xEA, 0x79, 0, 0, 1, 0x78, 0, 0, 0, 48 or 0x40, 0x2C)
        assertEquals(VrrRange(48, 300, "HDMI VRR"), Edid.parse(base(extensions = 1) + cta(scdb)).vrr)
    }

    @Test
    fun theDisplayIdAdaptiveSyncBlockGoesFirst() {
        val adaptiveSync = block(0x2B, 0x00, 6, 0x01, 0, 48, 239, 0, 0)
        val edid = Edid.parse(base(extensions = 2) + cta(freeSync) + displayId(adaptiveSync))
        assertEquals(VrrRange(48, 240, "Adaptive-Sync"), edid.vrr)
    }

    @Test
    fun aDamagedOrShortEdidSaysNothing() {
        assertEquals(EdidRefresh(null, null), Edid.parse(ByteArray(10)))
        assertEquals(EdidRefresh(null, null), Edid.parse(ByteArray(128)))
        // An extension count larger than the data stops at the data.
        assertEquals(56..76, Edid.parse(base(range = intArrayOf(0, 56, 76), extensions = 3)).limits)
    }

    @Test
    fun theMonitorDevicePathNamesItsRegistryKey() {
        assertEquals("SYSTEM\\CurrentControlSet\\Enum\\DISPLAY\\XYZ0A1B\\5&1a2b3c4d&0&UID4352\\Device Parameters",
            Edid.registryKey("\\\\?\\DISPLAY#XYZ0A1B#5&1a2b3c4d&0&UID4352#{e6f07b5f-ee97-4a90-b076-33f57bf4eaa7}"))
        assertNull(Edid.registryKey(""))
        assertNull(Edid.registryKey("\\\\?\\USB#VID_0000#1#{guid}"))
        assertNull(Edid.registryKey("\\\\?\\DISPLAY#..\\..#1#{guid}"))
    }

    @Test
    fun windowsAdvancedColourSaysWhetherHdrIsOn() {
        // DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO_2: activeColorMode 2 is HDR; 1 is wide colour (ACM) on an SDR screen.
        assertEquals(true, AdvancedColor.fromInfo2(flags = 0x13, mode = 2).on)
        assertEquals(true, AdvancedColor.fromInfo2(flags = 0x11, mode = 0).supported)
        assertEquals(false, AdvancedColor.fromInfo2(flags = 0x11, mode = 0).on)
        assertEquals(false, AdvancedColor.fromInfo2(flags = 0x03, mode = 1).on)
        assertEquals(false, AdvancedColor.fromInfo2(flags = 0x03, mode = 1).supported)
        // The older DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO: enabled is HDR unless wide colour is enforced.
        assertEquals(true, AdvancedColor.fromInfo(flags = 0x03).on)
        assertEquals(false, AdvancedColor.fromInfo(flags = 0x07).on)
        assertNull(AdvancedColor.fromInfo(flags = 0x07).supported)
        assertNull(AdvancedColor.UNKNOWN.on)
    }

    @Test
    fun nvidiaVrrBitsFollowNvapiH() {
        val bits = NvidiaVrr.parse(0b10011)
        assertEquals(NvidiaVrr(enabled = true, possible = true, requested = false, indicator = false, inVrrMode = true), bits)
        assertEquals(NvidiaVrr(enabled = false, possible = false, requested = true, indicator = true, inVrrMode = false), NvidiaVrr.parse(0b01100))
    }

    @Test
    fun theScreenLineSaysRefreshVariableRefreshAndHdr() {
        val hdrOff = HdrState(supported = true, on = false, source = "test")
        assertEquals("240 Hz · Variable refresh 48–240 Hz (detected) · HDR off",
            ScreenSummary.line(240.0002, EdidRefresh(VrrRange(48, 240, "FreeSync"), 48..240), null, hdrOff))
        assertEquals("59.94 Hz · Variable refresh not detected · HDR not reported",
            ScreenSummary.line(59.94, EdidRefresh(null, 56..76), null, AdvancedColor.UNKNOWN))
        // NVIDIA says VRR is possible: the EDID's range limits are then the range (DisplayPort Adaptive-Sync).
        assertEquals("144 Hz · Variable refresh 48–144 Hz (detected) · HDR on",
            ScreenSummary.line(143.9998, EdidRefresh(null, 48..144), true, HdrState(true, true, "test")))
        assertEquals("120 Hz · Variable refresh (range not reported) · HDR off",
            ScreenSummary.line(120.0, EdidRefresh(null, null), true, hdrOff))
        assertEquals("Refresh not reported · No variable refresh · HDR not reported",
            ScreenSummary.line(null, EdidRefresh(null, 48..144), false, AdvancedColor.UNKNOWN))
        assertEquals("60 Hz · Variable refresh from 40 Hz (detected) · HDR off",
            ScreenSummary.line(60.0, EdidRefresh(VrrRange(40, null, "HDMI VRR"), null), null, hdrOff))
    }

    @Test
    fun thisPcsScreensReportTheirHdrStateAndEdid() {
        // Read-only: Windows' display paths, advanced colour packets and the EDIDs it keeps in the registry. A wrong
        // packet size makes Windows refuse the call, which would show here as "not reported".
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val paths = DisplayPaths.active()
        assumeTrue("no active screens (a hosted runner)", paths.isNotEmpty())
        val states = paths.map { ScreenState(it, DisplayPaths.hdr(it), Edid.read(it.devicePath)) }
        states.forEach {
            val nvidia = DisplayVrr.read(it.path.gdiName)
            println("screen: " + ScreenSummary.line(it.path.refreshHz, it.edid, nvidia.supported, it.hdr) +
                " [" + it.hdr.source + "; EDID " + it.edid + "; " + nvidia.detail + "; " + (nvidia.nvidia?.label ?: "no NVIDIA bits") + "]")
        }
        // Type 9 exists since Windows 10 1709, so every active screen answers one of the two packets.
        states.forEach { assertNotNull(it.hdr.on, "HDR state of " + it.path.gdiName) }
        // Plan 5.7 item 4: each screen's desktop size, in physical pixels, from its source mode.
        paths.forEach { println("screen size: " + it.desktop) }
        paths.forEach { assertNotNull(it.desktop, "desktop size of " + it.gdiName) }
        // A virtual screen (a hosted runner's Hyper-V monitor) has no EDID for Windows to keep; a real one does.
        val real = states.filterNot { VIRTUAL_SCREEN.containsMatchIn(it.path.friendlyName) }
        if (real.isNotEmpty()) assertTrue(real.any { it.edid.limits != null || it.edid.vrr != null }, "at least one screen's EDID is readable")
        else println("EDID not checked: only virtual screens, which have none")
    }

    private interface User32 : StdCallLibrary {
        fun SendMessageW(window: Pointer, message: Int, wParam: Long, lParam: Long): Long
    }

    @Test
    fun aDisplayChangeMessageCountsOnce() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        DisplayChanges.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (DisplayChanges.window == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val window = assertNotNull(DisplayChanges.window, "the hidden display-change window")
        val before = DisplayChanges.generation
        // WM_DISPLAYCHANGE (0x7E): wParam bits per pixel, lParam the packed resolution. Sent only to SkyWing's own window.
        Native.load("user32", User32::class.java).SendMessageW(window, 0x7E, 32, (1080L shl 16) or 1920L)
        assertEquals(before + 1, DisplayChanges.generation)
    }

    private companion object {
        /** The monitor names virtual machines give their screens, such as a hosted runner's "HyperVMonitor". */
        val VIRTUAL_SCREEN = Regex("Hyper-?V|Virtual|Remote", RegexOption.IGNORE_CASE)
    }
}
