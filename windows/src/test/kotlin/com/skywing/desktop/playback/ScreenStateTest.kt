package com.skywing.desktop.playback

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun aRangeLimitsOnlyDescriptorIsMarked() {
        // Byte 10 of the descriptor: 1 is "range limits only" in EDID 1.4, the one variant Linux's DRM takes as a VRR range.
        fun limitsOnly(minor: Int, timing: Int) = Edid.parse(base(minor, intArrayOf(0, 48, 240)).also { it[64] = timing.toByte() }).limitsOnly
        assertTrue(limitsOnly(minor = 4, timing = 0x01))
        assertFalse(limitsOnly(minor = 4, timing = 0x00), "default GTF")
        assertFalse(limitsOnly(minor = 4, timing = 0x04), "CVT")
        assertFalse(limitsOnly(minor = 3, timing = 0x01), "EDID 1.3 has no such variant")
    }

    @Test
    fun aScreenCanVaryItsRefreshByItsVrrBlockOrByRangeLimitsOnADisplayPortLink() {
        val internal = VrrScreen.INTERNAL
        val panel = EdidRefresh(null, 48..240, limitsOnly = true)
        assertTrue(VrrScreen.capable(panel, internal), "a laptop or handheld's own screen")
        assertTrue(VrrScreen.capable(panel, 10), "DisplayPort")
        assertTrue(VrrScreen.capable(panel, 11), "embedded DisplayPort")
        assertTrue(VrrScreen.capable(panel, 18), "DisplayPort over USB4")
        assertFalse(VrrScreen.capable(panel, 5), "HDMI needs its own VRR block")
        assertFalse(VrrScreen.capable(panel, null), "a link Windows didn't name")
        assertFalse(VrrScreen.capable(EdidRefresh(null, 50..60, limitsOnly = true), internal), "10 Hz is no range")
        assertTrue(VrrScreen.capable(EdidRefresh(null, 49..60, limitsOnly = true), internal))
        assertFalse(VrrScreen.capable(EdidRefresh(null, 48..240), internal), "other range descriptors")
        assertTrue(VrrScreen.capable(EdidRefresh(VrrRange(48, 144, "FreeSync"), null), 5))
        assertFalse(VrrScreen.capable(EdidRefresh(null, null), 10), "no EDID says nothing")
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
    fun theScreenLineSaysRefreshVariableRefreshAndHdr() {
        val hdrOff = HdrState(supported = true, on = false, source = "test")
        assertEquals("240 Hz · Variable refresh 48–240 Hz (detected) · HDR off",
            ScreenSummary.line(240.0002, EdidRefresh(VrrRange(48, 240, "FreeSync"), 48..240), hdrOff))
        assertEquals("59.94 Hz · Variable refresh not detected · HDR not reported",
            ScreenSummary.line(59.94, EdidRefresh(null, 56..76), AdvancedColor.UNKNOWN))
        assertEquals("Refresh not reported · Variable refresh not detected · HDR not reported",
            ScreenSummary.line(null, EdidRefresh(null, null), AdvancedColor.UNKNOWN))
        assertEquals("60 Hz · Variable refresh from 40 Hz (detected) · HDR off",
            ScreenSummary.line(60.0, EdidRefresh(VrrRange(40, null, "HDMI VRR"), null), hdrOff))
        // A built-in screen whose EDID gives its range limits only (the laptop's and the Claw's kind).
        val panel = EdidRefresh(null, 48..240, limitsOnly = true)
        assertEquals("240 Hz · Variable refresh 48–240 Hz (detected) · HDR off",
            ScreenSummary.line(240.0, panel, hdrOff, VrrScreen.INTERNAL))
        assertEquals("240 Hz · Variable refresh not detected · HDR off", ScreenSummary.line(240.0, panel, hdrOff, 5))
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
            println("screen: " + ScreenSummary.line(it.path.refreshHz, it.edid, it.hdr, it.path.outputTechnology) +
                " [" + it.hdr.source + "; EDID " + it.edid + "; link " + it.path.outputTechnology + "]")
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
        // The same target-name packet as the monitor's name, so a real screen always has its link type.
        real.forEach { assertNotNull(it.path.outputTechnology, "link type of " + it.path.gdiName) }
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
