package com.skywing.desktop.playback

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan 5.6: verify.ps1 puts the display state in its report. This test reads every active screen as Windows and the
 * driver report it now, off screen and read-only, and writes `build/reports/tests/test/display-state.txt` for it.
 */
class DisplayStateReportTest {
    private fun screen(name: String, hz: Double?, hdr: HdrState, edid: EdidRefresh = EdidRefresh(null, null)) =
        ScreenState(DisplayPath("\\\\.\\DISPLAY1", 1L, 1, hz, name, ""), hdr, edid)

    @Test fun eachScreenLineNamesRefreshVrrAndHdr() {
        val nvidia = VrrCapability("\\\\.\\DISPLAY1", true, "NVIDIA per-display capability", NvidiaVrr.parse(0x03))
        assertEquals(
            "Screen 1 (Panel A): 165 Hz · Variable refresh 48–165 Hz (detected) · HDR on · VRR active: yes " +
                "(NVIDIA VRR: enabled true, possible true, requested false, display in VRR mode false)",
            ScreenSummary.state(0, screen("Panel A", 165.0, HdrState(true, true, "test"),
                EdidRefresh(VrrRange(48, 165, "Adaptive-Sync"), null)), nvidia),
        )
        assertEquals(
            "Screen 2: Refresh not reported · Variable refresh not detected · HDR not reported · VRR active: not measured " +
                "(only NVIDIA reports it; Display capability unavailable)",
            ScreenSummary.state(1, screen("", null, AdvancedColor.UNKNOWN), VrrCapability()),
        )
    }

    @Test fun writesTheDisplayStateForVerify() {
        val lines = Screens.report()
        assertTrue(lines.isNotEmpty())
        val out = File("build/reports/tests/test/display-state.txt")
        out.parentFile.mkdirs()
        out.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        println(lines.joinToString("\n"))
    }
}
