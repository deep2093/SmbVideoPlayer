package com.skywing.desktop.automation

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The parts of the M3 runner (M3Runner.kt, docs/modernise/M3-RUNNER.md) that need no SkyWing: what to run, the pages and
 * sizes, the plan's window rule (6.8), the navigation shape by width (6.4, 6.6), the accessibility audit (6.9) and the
 * tables. Unit tests: M3RunnerTest.
 */

/** What the M3 runner checks, by the word that names each check on the command line. */
internal enum class M3Check(val word: String, val about: String) {
    NUMBERS("numbers", "M1's before and after measurements: start time, scan, scrolling frame times, memory"),
    PAGES("pages", "a picture of each page at the design boards' sizes, the width sweep and the accessibility audit"),
    WINDOW("window", "the window's size and place at the first start, and what SkyWing remembers after it (plan 6.8)"),
    SETTINGS("settings", "moving around Settings: the category list, Settings again, Esc, the mouse back button (6.5, B1)"),
    ;

    companion object {
        /** The checks [args] name, in the runner's order; all of them when none is named. "trial" is not a check. */
        fun parse(args: List<String>): List<M3Check> {
            val words = args.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() && it != "trial" }
            val unknown = words.filter { word -> entries.none { it.word == word } }
            require(unknown.isEmpty()) {
                "Unknown word: ${unknown.joinToString()}. The checks are ${entries.joinToString { it.word }}, and \"trial\" makes them short."
            }
            return if (words.isEmpty()) entries.toList() else entries.filter { it.word in words }
        }
    }
}

/** A window size the design boards use (6.7), as the size of SkyWing's window content in dp. */
internal data class BoardSize(val key: String, val label: String, val widthDp: Int, val heightDp: Int) {
    fun fits(workWidthDp: Double, workHeightDp: Double): Boolean = widthDp <= workWidthDp && heightDp <= workHeightDp
}

/** A page the runner pictures: a section of the navigation, or a Settings category inside the Settings section. */
internal data class M3Page(val key: String, val title: String, val section: String, val category: String? = null)

internal object M3Pages {
    val DESKTOP = BoardSize("desktop", "Desktop 1440 × 900", 1440, 900)
    val HANDHELD = BoardSize("handheld", "Handheld 1280 × 800", 1280, 800)
    val COMPACT = BoardSize("compact", "Compact 1024 × 720", 1024, 720)
    /** Below the 720 dp Compact width class (6.6), above the 640 × 480 dp minimum window (6.8 rule 5). */
    val NARROW = BoardSize("narrow", "Narrow 680 × 720", 680, 720)
    val SIZES = listOf(DESKTOP, HANDHELD, COMPACT, NARROW)

    /** The sections, with the names other builds give them; the first is the plan's. Home is new in M3. */
    val SECTIONS: List<List<String>> = listOf(
        listOf("Home"),
        listOf("Folders"),
        listOf("Videos"),
        listOf("Files and drives", "Files"),
        listOf("Settings"),
    )

    /** Settings categories of the plan (6.5) and of the builds before it, in the plan's order. */
    val CATEGORIES = listOf(
        "Appearance", "Library", "Sync & scanning", "Auto grouping", "App layout", "Network drives", "Artwork", "Playback",
        "Controls", "Performance", "About",
    )

    fun key(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun section(names: List<String>): M3Page = M3Page(key(names.first()), names.first(), names.first())

    fun category(name: String): M3Page = M3Page("settings-" + key(name), "Settings › $name", "Settings", name)

    /** The board in design/png that shows [page] at [size], or null. The 6.7 table says which board is which. */
    fun board(size: BoardSize, page: M3Page): String? = when {
        size == DESKTOP && page.key == "home" -> "Main.png"
        size == COMPACT && page.key == "folders" -> "FoldersCompact.png"
        size == DESKTOP && page.key == "settings-playback" -> "SettingsDesktop.png"
        size == HANDHELD && page.key == "home" -> "HandheldHome.png"
        size == HANDHELD && page.key == "settings-performance" -> "SettingsHandheld.png"
        else -> null
    }

    /** The widths of the sweep (6.6): Expanded, just under its 1200 dp edge (wide by 6.4's 1100), Medium, Compact, the minimum. */
    val SWEEP_WIDTHS = listOf(1300, 1150, 1000, 800, 700, 640)
}

/** The visible window and its screen, in dp: what the plan's window rule (6.8) talks about. */
internal data class Placement(
    val maximised: Boolean,
    val left: Double, val top: Double, val width: Double, val height: Double,
    val workLeft: Double, val workTop: Double, val workWidth: Double, val workHeight: Double,
) {
    val widthShare: Double get() = width / workWidth
    val heightShare: Double get() = height / workHeight
    val centreOffset: Double get() = maxOf(abs(left + width / 2 - (workLeft + workWidth / 2)), abs(top + height / 2 - (workTop + workHeight / 2)))

    fun describe(): String = if (maximised) "maximised (work area ${dp(workWidth)} × ${dp(workHeight)} dp)" else
        "${dp(width)} × ${dp(height)} dp, ${pct(widthShare)} × ${pct(heightShare)} of the work area " +
            "(${dp(workWidth)} × ${dp(workHeight)} dp), ${dp(centreOffset)} dp from its centre"

    /** True when the two placements are the same window, within [toleranceDp]. */
    fun sameAs(other: Placement, toleranceDp: Double = 2.0): Boolean = maximised == other.maximised &&
        (maximised || listOf(left - other.left, top - other.top, width - other.width, height - other.height).all { abs(it) <= toleranceDp })

    companion object {
        fun dp(value: Double): String = value.roundToInt().toString()
        fun pct(value: Double): String = "${(value * 100).roundToInt()} %"
    }
}

/** The plan's window rule (6.8), as the runner checks it. Null from a check: it holds. */
internal object WindowRule {
    const val SMALL_WIDTH_DP = 1400.0
    const val SMALL_HEIGHT_DP = 900.0
    const val MIN_WIDTH_DP = 640.0
    const val MIN_HEIGHT_DP = 480.0
    const val TITLE_ON_SCREEN_DP = 64.0

    /** Rule 1: a work area under 1400 × 900 dp starts maximised. */
    fun startsMaximised(workWidthDp: Double, workHeightDp: Double): Boolean = workWidthDp < SMALL_WIDTH_DP || workHeightDp < SMALL_HEIGHT_DP

    /**
     * Rule 1 at the first start. A small screen: maximised. A bigger one: a normal window about 75 % of the work area
     * (70–80 % on the side that sets its size, as the plan doesn't say which side keeps the 16:10 shape), between
     * 960 × 600 and 1600 × 1000 dp, close to 16:10, and centred within 24 dp.
     */
    fun firstStart(p: Placement): String? {
        if (startsMaximised(p.workWidth, p.workHeight)) return if (p.maximised) null else
            "The work area is ${Placement.dp(p.workWidth)} × ${Placement.dp(p.workHeight)} dp, under 1400 × 900, so the window should start maximised; it is ${p.describe()}"
        if (p.maximised) return "The work area is ${Placement.dp(p.workWidth)} × ${Placement.dp(p.workHeight)} dp, so the window should start as a normal window, not maximised"
        val problems = buildList {
            val share = maxOf(p.widthShare, p.heightShare)
            val clampedWide = p.width >= 1600 - 2 || p.height >= 1000 - 2
            val clampedSmall = p.width <= 960 + 2 || p.height <= 600 + 2
            if (!clampedWide && !clampedSmall && share !in 0.70..0.80) add("about 75 % of the work area")
            if (p.width < 960 - 2 || p.height < 600 - 2 || p.width > 1600 + 2 || p.height > 1000 + 2) add("between 960 × 600 and 1600 × 1000 dp")
            if (p.width / p.height !in 1.45..1.75) add("close to 16:10")
            if (p.centreOffset > 24) add("centred")
        }
        return if (problems.isEmpty()) null else "The first window should be ${problems.joinToString(", ")}; it is ${p.describe()}"
    }

    /** Rule 4: restored from a maximised first start, the window is a 75 % window, not one the size of the screen. */
    fun restoredFromMaximised(p: Placement): String? = when {
        p.maximised -> "Restore left the window maximised"
        maxOf(p.widthShare, p.heightShare) > 0.85 -> "Restored, the window should be about 75 % of the work area; it is ${p.describe()}"
        else -> null
    }

    /** Rule 5. */
    fun minimum(p: Placement): String? = if (p.width >= MIN_WIDTH_DP - 2 && p.height >= MIN_HEIGHT_DP - 2) null else
        "Made as small as Windows allows, the window should stay at least 640 × 480 dp; it is ${Placement.dp(p.width)} × ${Placement.dp(p.height)} dp"

    /** Rule 3: at least 64 dp of the title bar on a screen. [visibleTitleDp] is how much of its width is on one. */
    fun backOnScreen(visibleTitleDp: Double): String? = if (visibleTitleDp >= TITLE_ON_SCREEN_DP) null else
        "Reopened after it was moved off the screens, only ${Placement.dp(visibleTitleDp)} dp of the title bar is on a screen; at least 64 dp should be"
}

/** How the main navigation is laid out (6.4, 6.6). */
internal enum class NavShape(val label: String) {
    WIDE_RAIL("rail, wide (labels beside the icons)"),
    NARROW_RAIL("rail, narrow (icons with small labels)"),
    TOP_TABS("tabs along the top"),
    UNKNOWN("not found"),
    ;

    companion object {
        /** The rail is wide from 150 dp: the plan's are 240 and 72 dp. */
        private const val WIDE_FROM_DP = 150.0

        /**
         * The shape from the navigation entries' bounds, in the window content's own units: stacked entries are a rail
         * as wide as their right edge from the content's left; entries side by side are tabs. [contentWidthDp] scales
         * those units to dp.
         */
        fun of(entries: List<Rect4>, content: Rect4, contentWidthDp: Double): Pair<NavShape, Double?> {
            if (entries.size < 2 || content.width <= 0) return UNKNOWN to null
            val xs = entries.map { it.centreX }
            val ys = entries.map { it.centreY }
            val stacked = ys.max() - ys.min() > xs.max() - xs.min()
            if (!stacked) return TOP_TABS to null
            val railDp = (entries.maxOf { it.right } - content.x) * contentWidthDp / content.width
            return (if (railDp >= WIDE_FROM_DP) WIDE_RAIL else NARROW_RAIL) to railDp
        }

        /**
         * What the plan asks for at a content width. 6.4 makes the rail wide from 1100 dp, 6.6 from 1200 dp (the
         * Expanded width class); the owner chose 1200 dp on 2026-11-25, so the rail follows the width class. Handheld
         * mode keeps the narrow rail from 720 dp.
         */
        fun expected(widthDp: Int, handheld: Boolean = false): NavShape = when {
            widthDp < 720 -> TOP_TABS
            widthDp < 1200 || handheld -> NARROW_RAIL
            else -> WIDE_RAIL
        }
    }
}

/** A rectangle as the Access Bridge reports it. */
internal data class Rect4(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centreX: Double get() = x + width / 2.0
    val centreY: Double get() = y + height / 2.0
    fun intersects(other: Rect4): Boolean = x < other.right && other.x < right && y < other.bottom && other.y < bottom
}

/** A node the audit reads: what [JabNode] gives, so tests can make their own. */
internal data class AuditNode(
    val path: String, val name: String, val description: String, val roleName: String, val states: Set<String>,
    val actions: List<String>, val value: String?,
) {
    val label: String get() = name.ifEmpty { description }
    val showing: Boolean get() = "showing" in states || "visible" in states

    companion object {
        fun of(node: JabNode) = AuditNode(node.path, node.name, node.description, node.roleName, node.states, node.actions, node.value)
    }
}

/** One thing a screen reader can't tell its user, on one page. */
internal data class AccessFinding(val page: String, val kind: String, val node: String)

/**
 * The 6.9 audit of one page's Access Bridge tree. It reports what a screen reader user would miss and changes nothing:
 * - a button (icon buttons included) with neither a name nor a description;
 * - a switch or check box, a slider or a text field without a label;
 * - a slider without a value ("Quality: Balanced");
 * - a group of page tabs (rail, top tabs, Settings categories) of which none is selected.
 * Only nodes that are showing and can be used (they have an action, or are a slider or a field) count.
 */
internal object AccessAudit {
    private val NAMED_ROLES = setOf("push button", "toggle button", "check box", "radio button", "page tab", "menu item", "combo box", "slider", "text", "password text")

    fun audit(page: String, nodes: List<AuditNode>): List<AccessFinding> {
        val used = nodes.filter { it.showing && (it.actions.isNotEmpty() || it.roleName in setOf("slider", "text", "password text")) }
        val findings = mutableListOf<AccessFinding>()
        for (node in used) {
            if (node.roleName in NAMED_ROLES && node.label.isBlank()) findings += AccessFinding(page, "no name: ${node.roleName}", node.path)
            if (node.roleName == "slider" && node.value.isNullOrBlank()) findings += AccessFinding(page, "slider without a value", "${node.path} (${node.label})")
        }
        // Tabs that share a parent are one group; the selected one must say so.
        used.filter { it.roleName == "page tab" }.groupBy { it.path.substringBeforeLast('/') }.forEach { (parent, tabs) ->
            if (tabs.size >= 2 && tabs.none { "selected" in it.states }) findings += AccessFinding(page, "no selected tab", "$parent (${tabs.joinToString { it.label }})")
        }
        return findings
    }

    /** Counts per page and kind: the audit's table. */
    fun table(findings: Map<String, List<AccessFinding>>, nodes: Map<String, Int>): String = buildString {
        appendLine("| Page | Controls read | Without a name | Sliders without a value | Tab groups without a selected tab |")
        appendLine("|---|---|---|---|---|")
        for ((page, count) in nodes) {
            val found = findings[page].orEmpty()
            appendLine("| $page | $count | ${found.count { it.kind.startsWith("no name") }} | ${found.count { it.kind == "slider without a value" }} | ${found.count { it.kind == "no selected tab" }} |")
        }
    }
}

/** One line of M3-table.md. Its [Verdict] is the checks runner's. */
internal data class M3Row(val check: String, val image: String, val verdict: Verdict, val detail: String)

/** The runner's summary, M3-table.md. */
internal fun m3Summary(device: String, display: String, builds: Map<String, String>, rows: List<M3Row>): String = buildString {
    appendLine("# SkyWing M3 runner")
    appendLine()
    appendLine("- Device: $device")
    appendLine("- Display: $display")
    builds.forEach { (image, build) -> appendLine("- The $image image: $build") }
    appendLine()
    appendLine("| Check | Image | Verdict | Details |")
    appendLine("|---|---|---|---|")
    rows.forEach { appendLine("| ${it.check} | ${it.image} | ${it.verdict.word} | ${it.detail.replace("|", "\\|").replace("\n", "<br>")} |") }
}

/** One picture of the gallery: an image's page at a size, or a width of the sweep. */
internal data class Shot(val image: String, val size: String, val page: M3Page, val file: String?, val note: String)

/**
 * gallery.html: for each size and page, the before picture, the after picture and the design board beside them, so the
 * owner can compare by eye. Paths are relative to the results folder.
 */
internal fun galleryHtml(device: String, shots: List<Shot>, boards: (String, M3Page) -> String?): String = buildString {
    fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    appendLine("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>SkyWing M3 gallery</title>")
    appendLine("<style>body{font:14px 'Segoe UI',sans-serif;margin:24px;background:#111215;color:#f3f4f6}h2{margin-top:40px}")
    appendLine("table{border-collapse:collapse}td{vertical-align:top;padding:8px}img{max-width:560px;border:1px solid #333}")
    appendLine(".note{color:#a9aeb8;font-size:12px;max-width:560px}</style></head><body>")
    appendLine("<h1>SkyWing M3 gallery</h1><p>${esc(device)}. Click a picture to open it at full size. The boards are the design reference (6.7): match the structure, sizes and tokens, not every pixel.</p>")
    val images = shots.map { it.image }.distinct()
    for ((size, bySize) in shots.groupBy { it.size }) {
        appendLine("<h2>${esc(size)}</h2><table><tr><th>Page</th>" + images.joinToString("") { "<th>${esc(it)}</th>" } + "<th>Board</th></tr>")
        for ((page, byPage) in bySize.groupBy { it.page }) {
            append("<tr><td>${esc(page.title)}</td>")
            for (image in images) {
                val shot = byPage.firstOrNull { it.image == image }
                append("<td>")
                if (shot?.file != null) append("<a href=\"${esc(shot.file)}\"><img src=\"${esc(shot.file)}\" alt=\"${esc("$image: ${page.title}")}\"></a>")
                if (shot != null && shot.note.isNotEmpty()) append("<div class=\"note\">${esc(shot.note)}</div>")
                if (shot == null) append("<div class=\"note\">not pictured</div>")
                append("</td>")
            }
            val board = boards(size, page)
            append(if (board != null) "<td><a href=\"$board\"><img src=\"$board\" alt=\"${esc("Board: ${page.title}")}\"></a></td>" else "<td></td>")
            appendLine("</tr>")
        }
        appendLine("</table>")
    }
    appendLine("<h2>Tokens</h2><p><a href=\"boards/Tokens.png\"><img src=\"boards/Tokens.png\" alt=\"Tokens board\"></a></p>")
    appendLine("</body></html>")
}
