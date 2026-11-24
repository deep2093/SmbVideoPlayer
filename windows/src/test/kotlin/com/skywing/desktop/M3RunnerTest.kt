package com.skywing.desktop

import com.skywing.desktop.automation.AccessAudit
import com.skywing.desktop.automation.AuditNode
import com.skywing.desktop.automation.M3Check
import com.skywing.desktop.automation.M3Page
import com.skywing.desktop.automation.M3Pages
import com.skywing.desktop.automation.M3Row
import com.skywing.desktop.automation.NavShape
import com.skywing.desktop.automation.Placement
import com.skywing.desktop.automation.Rect4
import com.skywing.desktop.automation.Shot
import com.skywing.desktop.automation.Verdict
import com.skywing.desktop.automation.WindowRule
import com.skywing.desktop.automation.galleryHtml
import com.skywing.desktop.automation.m3Summary
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parts of the M3 runner that need no SkyWing: the checks it runs, the plan's rules it judges by and its tables. */
class M3RunnerTest {
    @Test
    fun theCommandLineNamesTheChecksInTheRunnersOrder() {
        assertEquals(M3Check.entries.toList(), M3Check.parse(emptyList()))
        assertEquals(M3Check.entries.toList(), M3Check.parse(listOf("trial")))
        assertEquals(listOf(M3Check.PAGES, M3Check.WINDOW), M3Check.parse(listOf("window", "Pages", "trial")))
        val refused = assertFailsWith<IllegalArgumentException> { M3Check.parse(listOf("pages", "colours")) }
        assertContains(refused.message.orEmpty(), "colours")
    }

    private fun placement(left: Double, top: Double, width: Double, height: Double, workWidth: Double, workHeight: Double, maximised: Boolean = false) =
        Placement(maximised, left, top, width, height, 0.0, 0.0, workWidth, workHeight)

    /** A normal window centred in the work area. */
    private fun centred(width: Double, height: Double, workWidth: Double, workHeight: Double) =
        placement((workWidth - width) / 2, (workHeight - height) / 2, width, height, workWidth, workHeight)

    @Test
    fun aSmallScreenStartsMaximisedAndABigOneWithAThreeQuarterWindow() {
        // 1280 × 800 handheld at 100 %: under 1400 × 900, so maximised.
        assertNull(WindowRule.firstStart(placement(0.0, 0.0, 1280.0, 752.0, 1280.0, 752.0, maximised = true)))
        assertNotNull(WindowRule.firstStart(centred(960.0, 600.0, 1280.0, 752.0)))
        // 1920 × 1080 at 100 %: the height sets the size, 75 % of 1032 dp, at 16:10.
        assertNull(WindowRule.firstStart(centred(1238.0, 774.0, 1920.0, 1032.0)))
        assertNotNull(WindowRule.firstStart(placement(0.0, 0.0, 1920.0, 1032.0, 1920.0, 1032.0, maximised = true)))
        // As wide as 75 % but 87 % of the height: too big.
        assertNotNull(WindowRule.firstStart(centred(1440.0, 900.0, 1920.0, 1032.0)))
        // Off centre by more than 24 dp.
        assertNotNull(WindowRule.firstStart(placement(0.0, 129.0, 1238.0, 774.0, 1920.0, 1032.0)))
        // A 4K screen at 100 %: the size stops at 1600 × 1000 dp, under 75 %.
        assertNull(WindowRule.firstStart(centred(1600.0, 1000.0, 3840.0, 2112.0)))
    }

    @Test
    fun restoreMinimumAndOffScreenFollowThePlan() {
        assertNull(WindowRule.restoredFromMaximised(centred(960.0, 600.0, 1280.0, 752.0)))
        assertNotNull(WindowRule.restoredFromMaximised(centred(1270.0, 740.0, 1280.0, 752.0)))
        assertNotNull(WindowRule.restoredFromMaximised(placement(0.0, 0.0, 1280.0, 752.0, 1280.0, 752.0, maximised = true)))
        assertNull(WindowRule.minimum(centred(640.0, 480.0, 1920.0, 1032.0)))
        assertNotNull(WindowRule.minimum(centred(200.0, 150.0, 1920.0, 1032.0)))
        assertNull(WindowRule.backOnScreen(64.0))
        assertNotNull(WindowRule.backOnScreen(10.0))
    }

    @Test
    fun aReopenedWindowIsTheSameWithinTwoDp() {
        val closed = centred(1238.0, 774.0, 1920.0, 1032.0)
        assertTrue(closed.sameAs(closed.copy(left = closed.left + 1.5, height = closed.height - 1.0)))
        assertTrue(!closed.sameAs(closed.copy(width = closed.width + 8.0)))
        assertTrue(!closed.sameAs(closed.copy(maximised = true)))
    }

    @Test
    fun theNavigationShapeFollowsTheWidthClass() {
        assertEquals(NavShape.TOP_TABS, NavShape.expected(680))
        assertEquals(NavShape.NARROW_RAIL, NavShape.expected(720))
        assertEquals(NavShape.NARROW_RAIL, NavShape.expected(1000))
        // The owner chose 6.6's edge: wide from 1200 dp, not 6.4's 1100.
        assertEquals(NavShape.NARROW_RAIL, NavShape.expected(1150))
        assertEquals(NavShape.WIDE_RAIL, NavShape.expected(1200))
        assertEquals(NavShape.WIDE_RAIL, NavShape.expected(1300))
        assertEquals(NavShape.NARROW_RAIL, NavShape.expected(1300, handheld = true))
    }

    @Test
    fun entriesStackedAreARailAndSideBySideAreTabs() {
        // The window content is 2600 units wide and 1300 dp: two units a dp, as on a 200 % screen.
        val content = Rect4(100, 50, 2600, 1440)
        val wide = (0 until 5).map { Rect4(116, 150 + it * 112, 448, 96) }
        val (shape, railDp) = NavShape.of(wide, content, 1300.0)
        assertEquals(NavShape.WIDE_RAIL, shape)
        assertEquals(232.0, assertNotNull(railDp), 0.5)
        val narrow = (0 until 5).map { Rect4(108, 150 + it * 140, 128, 128) }
        assertEquals(NavShape.NARROW_RAIL, NavShape.of(narrow, content, 1300.0).first)
        val tabs = (0 until 5).map { Rect4(200 + it * 300, 60, 260, 80) }
        assertEquals(NavShape.TOP_TABS, NavShape.of(tabs, content, 1300.0).first)
        assertEquals(NavShape.UNKNOWN, NavShape.of(tabs.take(1), content, 1300.0).first)
    }

    private fun node(path: String, role: String, name: String = "", description: String = "", states: Set<String> = setOf("showing", "enabled"),
                     actions: List<String> = listOf("click"), value: String? = null) = AuditNode(path, name, description, role, states, actions, value)

    @Test
    fun theAuditFindsWhatAScreenReaderCantTellAndOnlyOnScreen() {
        val nodes = listOf(
            node("0/1", "push button"),
            node("0/2", "push button", description = "Search"),
            node("0/3", "slider", name = "Quality", actions = emptyList()),
            node("0/4", "slider", name = "Strength", actions = emptyList(), value = "Balanced"),
            node("0/5", "check box", name = "", states = setOf("enabled")),
            node("0/6/0", "page tab", name = "Folders"),
            node("0/6/1", "page tab", name = "Videos"),
            node("0/7/0", "page tab", name = "Appearance", states = setOf("showing", "enabled", "selected")),
            node("0/7/1", "page tab", name = "Library"),
            node("0/8", "label", name = ""),
        )
        val findings = AccessAudit.audit("Videos", nodes)
        assertEquals(listOf("no name: push button", "slider without a value", "no selected tab"), findings.map { it.kind })
        assertEquals("0/1", findings.first().node)
        assertContains(findings.last().node, "0/6 (Folders, Videos)")
        val table = AccessAudit.table(mapOf("after: Videos" to findings), mapOf("after: Videos" to 8))
        assertContains(table, "| after: Videos | 8 | 1 | 1 | 1 |")
    }

    @Test
    fun theBoardsAreThoseThePlansTableNames() {
        assertEquals("Main.png", M3Pages.board(M3Pages.DESKTOP, M3Pages.section(listOf("Home"))))
        assertEquals("FoldersCompact.png", M3Pages.board(M3Pages.COMPACT, M3Pages.section(listOf("Folders"))))
        assertEquals("SettingsDesktop.png", M3Pages.board(M3Pages.DESKTOP, M3Pages.category("Playback")))
        assertEquals("SettingsHandheld.png", M3Pages.board(M3Pages.HANDHELD, M3Pages.category("Performance")))
        assertNull(M3Pages.board(M3Pages.NARROW, M3Pages.section(listOf("Home"))))
        assertEquals("settings-sync-scanning", M3Pages.category("Sync & scanning").key)
        assertEquals("files-and-drives", M3Pages.section(listOf("Files and drives", "Files")).key)
        assertTrue(M3Pages.NARROW.fits(800.0, 752.0))
        assertTrue(!M3Pages.DESKTOP.fits(1280.0, 752.0))
    }

    @Test
    fun theSummaryKeepsEachRowOnOneLine() {
        val table = m3Summary("Test device", "1920 × 1080 at 60 Hz", mapOf("before" to "abc1234", "after" to "def5678"), listOf(
            M3Row("navigation by width", "after", Verdict.PASS, "1300 dp: rail | wide\n700 dp: tabs"),
            M3Row("pages", "before", Verdict.BEFORE, "12 pictures"),
        ))
        assertContains(table, "- The after image: def5678")
        assertContains(table, "| navigation by width | after | Pass | 1300 dp: rail \\| wide<br>700 dp: tabs |")
        assertContains(table, "| pages | before | Before M3: shown, not judged | 12 pictures |")
    }

    @Test
    fun theGalleryPutsEachImageBesideTheBoard() {
        val home = M3Pages.section(listOf("Home"))
        val shots = listOf(
            Shot("before", M3Pages.DESKTOP.label, home, "pages/before/desktop-home.png", ""),
            Shot("after", M3Pages.DESKTOP.label, home, "pages/after/desktop-home.png", "Another window covered part of SkyWing (2 of 9 points)"),
            Shot("after", M3Pages.DESKTOP.label, M3Page("videos", "Videos <all>", "Videos"), null, "Couldn't open it at this size"),
        )
        val html = galleryHtml("Test device", shots) { size, page ->
            M3Pages.SIZES.firstOrNull { it.label == size }?.let { M3Pages.board(it, page) }?.let { "boards/$it" }
        }
        assertContains(html, "<img src=\"pages/after/desktop-home.png\"")
        assertContains(html, "<img src=\"boards/Main.png\"")
        assertContains(html, "Another window covered part of SkyWing")
        assertContains(html, "Videos &lt;all&gt;")
        assertContains(html, "not pictured")
    }
}
