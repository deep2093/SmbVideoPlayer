package com.skywing.desktop.automation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * The M3 runner (docs/modernise/M3-RUNNER.md): M3's checks of two SkyWing app images on one device with nobody at it,
 * the build before M3 ("before") and the one with it ("after"). Like the M1 runner it installs nothing: each image runs
 * from the runner folder on isolated data ([SkyWingTestApp]), so the installed SkyWing, the owner's library and
 * settings and the Windows settings stay as they are. The checks ([M3Check]):
 * - numbers: M1's measurements ([M1Pass]): start time, the scan, scrolling frame times, memory;
 * - pages: a picture of every page at the design boards' sizes, beside the boards (gallery.html); the width sweep
 *   (navigation shape, scroll position kept); the Access Bridge audit of every page (access.md);
 * - window: the window's size and place at the first start, and what SkyWing remembers after it (plan 6.8);
 * - settings: the category list beside a page, Settings again, Esc, the mouse back button, the Back button, and
 *   another section and back (plan 6.5, B1).
 *
 * The after image is judged by the plan's rules; the before image is pictured and measured the same way but not
 * judged, so the owner sees what M3 changed. The runner moves and sizes SkyWing's window with Windows' own calls and
 * pictures the screen where it is ([WindowShots]); keys and the back button are posted to SkyWing's window, not typed.
 * A picture taken while another window covered SkyWing says so.
 *
 * `gradlew -p windows m3KeepBefore` (on the commit before M3's app changes) keeps the before image; `gradlew -p windows
 * m1Media m3Runner` builds the portable folder. SKYWING_M3_BEFORE, SKYWING_M3_AFTER, SKYWING_M3_MEDIA and
 * SKYWING_M3_BOARDS name other folders; SKYWING_M3_ONLY=before (or after) runs one image. Words on the command line
 * name the checks ("Run M3.cmd pages window"); "trial" makes them short, which tests the runner, not SkyWing.
 */
fun main(args: Array<String>) {
    WindowShots.perMonitorAware()
    val trial = args.any { it.equals("trial", ignoreCase = true) }
    // exitProcess: AWT and the Access Bridge client keep threads that would hold the JVM open.
    val code = try {
        val checks = M3Check.parse(args.toList())
        HandheldSystem.keepConsoleRunning()
        HandheldSystem.keepAwake()
        M3Runner(trial, checks, System.getenv(), Path.of("").toAbsolutePath()).runAll()
    } catch (refused: IllegalArgumentException) {
        HandheldSystem.beep(3)
        println()
        println("Stopped: ${refused.message}")
        2
    } catch (error: Throwable) {
        HandheldSystem.beep(3)
        println()
        println("Stopped by an error:")
        error.printStackTrace(System.out)
        3
    }
    exitProcess(code)
}

private class M3Runner(
    private val trial: Boolean,
    private val checks: List<M3Check>,
    private val environment: Map<String, String>,
    private val runner: Path,
) {
    private val product = registryValue(BIOS, "SystemProductName")
    private val device = listOfNotNull(registryValue(BIOS, "SystemManufacturer"), product).joinToString(" ").ifBlank { "this device" }
    private val results = runner.resolve("results").resolve(machineFolder(product, environment["COMPUTERNAME"])).let { if (trial) it.resolve("trial") else it }
    private val media = path("SKYWING_M3_MEDIA") ?: runner.resolve("media")
    private val boards = path("SKYWING_M3_BOARDS") ?: runner.resolve("boards")
    private val images = listOf("before", "after").associateWith { name -> path("SKYWING_M3_${name.uppercase(Locale.ROOT)}") ?: runner.resolve("images").resolve(name) }
    private val only = environment["SKYWING_M3_ONLY"]?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private val rows = mutableListOf<M3Row>()
    private val shots = mutableListOf<Shot>()
    private val findings = linkedMapOf<String, List<AccessFinding>>()
    private val controls = linkedMapOf<String, Int>()
    /** For pictures at a size that is no board's (maximised): the board size of the nearest width. */
    private val nearestBoard = mutableMapOf<String, BoardSize>()

    private fun path(name: String) = environment[name]?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() }

    fun runAll(): Int {
        runnerFolderRefusal(runner, environment, HandheldSystem::removable, runner = "M3")?.let { throw IllegalArgumentException(it) }
        require(only == null || only in images) { "SKYWING_M3_ONLY is before or after, not $only." }
        val todo = images.filterKeys { only == null || it == only }
        for ((name, image) in todo) require(image.resolve("SkyWing.exe").isRegularFile()) { "The $name image isn't in the runner folder (images\\$name\\SkyWing.exe)." }
        val clips = M1Media.clips(media)
        val pictures = clips.map { M1Media.picture(media, it) }
        if (M3Check.NUMBERS in checks || M3Check.PAGES in checks) {
            require(clips.all { it.isRegularFile() }) { "The test clips aren't in the runner folder (media\\clips)." }
            require(pictures.all { it.isRegularFile() }) { "The test pictures aren't in the runner folder (media\\pictures)." }
        }
        require(skyWingProcesses().isEmpty()) { "Close SkyWing first: the runner measures every SkyWing process." }
        results.createDirectories()
        logOutput(results.resolve("runner-log.txt"))
        System.setProperty(SkyWingTestApp.REPORTS_PROPERTY, results.resolve("diagnostics").toString())
        val display = displayText()
        val quietWait = quietWaitMs(HandheldSystem.foregroundLockTimeoutMs())
        println("SkyWing M3 ${if (trial) "trial" else "checks"} on $device")
        println("Display: $display")
        println("Power: ${powerText(Probe.take())}")
        println("Checks: ${checks.joinToString { it.word }}, on ${todo.size} image(s). Don't touch the device until it beeps.")

        for ((index, entry) in todo.entries.withIndex()) {
            val (name, image) = entry
            if (index > 0) {
                println("Next image in ${COOL_DOWN_MS / 1000} s.")
                Thread.sleep(COOL_DOWN_MS)
            }
            for (check in checks) {
                waitUntilQuiet(quietWait)
                println()
                println("The $name image: ${check.about}.")
                val failure = runCatching {
                    when (check) {
                        M3Check.NUMBERS -> numbers(name, image, clips, pictures, quietWait)
                        M3Check.PAGES -> pages(name, image, clips, pictures)
                        M3Check.WINDOW -> window(name, image)
                        M3Check.SETTINGS -> settings(name, image)
                    }
                }.exceptionOrNull()
                if (failure != null) {
                    rows += M3Row(check.word, name, Verdict.FAIL, "Stopped: ${failure.message}")
                    println("The ${check.word} check stopped: ${failure.message}")
                    failure.printStackTrace(System.out)
                    require(skyWingProcesses().isEmpty()) { "A SkyWing the runner started is still running; the other checks would measure it too." }
                }
                writeReports(display)
            }
        }
        HandheldSystem.beep(3)
        println()
        println("Done. The table is in ${results.resolve("M3-table.md")}, the pictures in ${results.resolve("gallery.html")}. Bring back the whole results folder.")
        return if (rows.any { it.image == "after" && it.verdict == Verdict.FAIL }) 1 else 0
    }

    // ---- numbers: M1's measurements on this image ----

    private fun numbers(name: String, image: Path, clips: List<Path>, pictures: List<Path>, quietWait: Long?) {
        val library = runner.resolve("work").resolve("library")
        val videos = M1Library.build(clips, library)
        val folder = results.resolve("numbers").resolve(name)
        var attempt = 1
        while (true) {
            val outcome = runCatching { M1Pass(name, image, library, videos, pictures, folder, if (trial) M1Timing.TRIAL else M1Timing.FULL).run() }
            val failure = outcome.exceptionOrNull()
            if (failure == null) {
                val values = linkedMapOf("build" to buildOf(name), "power" to powerText(Probe.take())) + outcome.getOrThrow()
                folder.resolve("numbers.txt").writeText(values.entries.joinToString("") { "${it.key}=${it.value}\n" })
                rows += M3Row("numbers", name, Verdict.MEASURED, "start ${values["start_everyday_s"]} s, scroll p95 ${values["scroll_p95_ms"]} ms, " +
                    "private memory ${values["memory_private_mb"]} MB; all of them in numbers/M1-table.md")
                return
            }
            if (failure !is M1Pass.Spoiled || attempt == 2) throw failure
            println("That didn't count: ${failure.message}. Trying once more.")
            attempt++
            waitUntilQuiet(quietWait)
        }
    }

    private fun numbersOf(name: String): Map<String, String> {
        val file = results.resolve("numbers").resolve(name).resolve("numbers.txt")
        if (!file.isRegularFile()) return emptyMap()
        return file.readLines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }

    // ---- pages: pictures, the width sweep and the accessibility audit ----

    /** 48 videos in 6 folders, so folder cards have pictures to show. Hard links to the clips, like M1's library. */
    private fun galleryLibrary(clips: List<Path>): List<Path> {
        val root = runner.resolve("work").resolve("gallery")
        return (0 until GALLERY_FOLDERS * GALLERY_PER_FOLDER).map { index ->
            val folder = root.resolve("Folder ${index / GALLERY_PER_FOLDER + 1}").createDirectories()
            val link = folder.resolve(M1Library.linkName(index))
            if (!link.exists()) {
                try {
                    Files.createLink(link, clips[M1Library.clipIndex(index, clips.size)])
                } catch (e: Exception) {
                    throw IllegalArgumentException("The test library can't be made (${e.javaClass.simpleName}). Put the runner folder on the device's own NTFS drive.", e)
                }
            }
            link
        }
    }

    private fun pages(name: String, image: Path, clips: List<Path>, pictures: List<Path>) {
        val videos = galleryLibrary(clips)
        val folder = results.resolve("pages").resolve(name).createDirectories()
        folder.listDirectoryEntries("*.png").forEach { it.deleteIfExists() }
        val log = folder.resolve("perf.log").apply { deleteIfExists() }
        val library = runner.resolve("work").resolve("gallery")
        val app = SkyWingTestApp.launch("m3-$name-pages", image = image, extraEnvironment = mapOf("SKYWING_PERF_LOG" to log.toString())) { root, data ->
            root.node("com/skywing/desktop/local-library-folders").apply {
                putInt("count", 1)
                put("folder.0", library.toString())
            }
            M1Library.seedThumbnails(videos, pictures, data.resolve("LocalAppData"))
        }
        var pictured = 0
        try {
            val hwnd = app.mainWindow()
            if (!awaitLibrary(app, log, videos.size)) println("SkyWing didn't say the library was loaded within ${LIBRARY_WAIT_MS / 1000} s; the pictures may show it loading.")
            val (roomWidth, roomHeight) = roomDp(hwnd)
            val sizes = if (trial) listOf(M3Pages.DESKTOP, M3Pages.NARROW) else M3Pages.SIZES
            val fitting = sizes.filter { it.fits(roomWidth, roomHeight) }
            for (size in sizes - fitting.toSet()) {
                shots += Shot(name, size.label, M3Page("all", "Every page", "-"), null,
                    "Not pictured: a window this big doesn't fit this screen (room for ${roomWidth.roundToInt()} × ${roomHeight.roundToInt()} dp)")
            }
            val first = fitting.firstOrNull()
            if (first != null) WindowShots.setContentSizeDp(hwnd, first.widthDp, first.heightDp) else WindowShots.maximise(hwnd)
            Thread.sleep(RESIZE_SETTLE_MS)
            val pages = pagesOf(app)
            println("Pages: ${pages.joinToString { it.title }}")
            fun pictureAll(label: String, key: String, audited: Boolean) {
                for (page in pages) {
                    if (!open(app, page)) {
                        shots += Shot(name, label, page, null, "Couldn't open it at this size")
                        continue
                    }
                    Thread.sleep(PAGE_SETTLE_MS)
                    shots += picture(app, hwnd, name, label, page, folder.resolve("$key-${page.key}.png"))
                    pictured++
                    if (audited) audit(app, name, page)
                }
            }
            for (size in fitting) {
                WindowShots.setContentSizeDp(hwnd, size.widthDp, size.heightDp)
                Thread.sleep(RESIZE_SETTLE_MS)
                pictureAll(size.label, size.key, audited = size == first)
            }
            // Maximised, as SkyWing starts on a screen under 1400 × 900 dp (6.8 rule 1): on a handheld at 150 % it is
            // the only size there is room for. Beside the boards of the nearest width.
            WindowShots.maximise(hwnd)
            Thread.sleep(RESIZE_SETTLE_MS)
            val content = WindowShots.client(hwnd)
            val scale = WindowShots.scale(hwnd)
            val screenWidth = (content.right - content.left) / scale
            val screenLabel = "This screen, maximised (${screenWidth.roundToInt()} × ${((content.bottom - content.top) / scale).roundToInt()} dp)"
            nearestBoard[screenLabel] = M3Pages.SIZES.minBy { abs(it.widthDp - screenWidth) }
            pictureAll(screenLabel, "maximised", audited = first == null)
            for (missing in M3Pages.SECTIONS.map { M3Pages.section(it) }.filter { s -> pages.none { it.key == s.key } }) {
                shots += Shot(name, screenLabel, missing, null, "Not in this build")
            }
            sweep(app, hwnd, name, folder, roomWidth, roomHeight)
        } finally {
            app.close()
        }
        rows += M3Row("pages", name, if (name == AFTER) Verdict.LOOK else Verdict.BEFORE, "$pictured pictures. Compare them with the boards in gallery.html.")
        val found = findings.filterKeys { it.startsWith("$name: ") }.values.flatten()
        rows += M3Row("accessibility", name, judged(name, if (found.isEmpty()) null else "${found.size} problem(s); access.md lists them"),
            if (found.isEmpty()) "Every button, switch, slider, field and tab group on ${controls.keys.count { it.startsWith("$name: ") }} pages has a name, a value and a selected tab" else "${found.size} problem(s); access.md lists them")
    }

    /** The sections this build has, then Settings' list and each category it shows. */
    private fun pagesOf(app: SkyWingTestApp): List<M3Page> {
        val sections = M3Pages.SECTIONS.filter { names -> names.any { label -> exists(app, label) } }.map { M3Pages.section(it) }
        val pages = sections.filter { it.section != "Settings" }.toMutableList()
        if (sections.any { it.section == "Settings" } && activateFirst(app, listOf("Settings"))) {
            Thread.sleep(PAGE_SETTLE_MS)
            if (!categoryShowing(app)) activateBack(app)
            pages += M3Pages.section(listOf("Settings"))
            val shown = app.withScreen { s -> s.nodes.filter { it.label in M3Pages.CATEGORIES && it.roleName in NAV_ROLES && it.actions.isNotEmpty() }.map { it.label } }
            pages += M3Pages.CATEGORIES.filter { it in shown }.map { M3Pages.category(it) }
        }
        return pages
    }

    private fun open(app: SkyWingTestApp, page: M3Page): Boolean {
        if (page.category == null) {
            val names = M3Pages.SECTIONS.firstOrNull { it.first() == page.title } ?: listOf(page.title)
            val opened = activateFirst(app, names)
            // Settings opens on its list; a page left open there (before B1's fix) closes with its Back button.
            if (opened && page.section == "Settings") {
                Thread.sleep(PAGE_SETTLE_MS)
                if (!categoryShowing(app)) activateBack(app)
            }
            return opened
        }
        if (!activateFirst(app, listOf("Settings"))) return false
        Thread.sleep(PAGE_SETTLE_MS)
        if (activateFirst(app, listOf(page.category))) return true
        // One pane: the list is hidden while a page is open.
        return activateBack(app) && run { Thread.sleep(PAGE_SETTLE_MS); activateFirst(app, listOf(page.category)) }
    }

    private fun picture(app: SkyWingTestApp, hwnd: Long, name: String, size: String, page: M3Page, file: Path): Shot {
        val note = uncovered(app, hwnd)
        WindowShots.save(WindowShots.capture(hwnd), file)
        return Shot(name, size, page, results.relativize(file).toString().replace('\\', '/'), note ?: "")
    }

    /** Null when SkyWing is in front and nothing covers it; otherwise what was wrong, after one try to raise it. */
    private fun uncovered(app: SkyWingTestApp, hwnd: Long): String? {
        fun covered(): Int {
            val ours = app.processIds()
            return WindowShots.contentPoints(hwnd).count { (x, y) -> Win32.processAt(x, y) !in ours }
        }
        if (covered() == 0) return null
        Win32.raise(hwnd)
        Thread.sleep(400)
        val left = covered()
        return if (left == 0) null else "Another window covered part of SkyWing ($left of 9 points)"
    }

    private fun audit(app: SkyWingTestApp, name: String, page: M3Page) {
        val nodes = app.withScreen { s -> s.nodes.map(AuditNode::of) }
        findings["$name: ${page.title}"] = AccessAudit.audit(page.title, nodes)
        controls["$name: ${page.title}"] = nodes.count { it.showing && it.actions.isNotEmpty() }
    }

    /** The width sweep on Videos: navigation shape per width (6.4, 6.6) and whether the scroll position stays (6.6). */
    private fun sweep(app: SkyWingTestApp, hwnd: Long, name: String, folder: Path, roomWidth: Double, roomHeight: Double) {
        if (!activateFirst(app, listOf("Videos"))) {
            rows += M3Row("navigation by width", name, Verdict.NOT_MEASURED, "No Videos page to sweep")
            return
        }
        val widths = (if (trial) listOf(1300, 700) else M3Pages.SWEEP_WIDTHS).filter { it <= roomWidth }
        if (widths.isEmpty()) return
        val height = minOf(720.0, roomHeight).toInt()
        WindowShots.setContentSizeDp(hwnd, widths.first(), height)
        Thread.sleep(RESIZE_SETTLE_MS)
        scrollDown(hwnd, SWEEP_WHEEL_TURNS)
        Thread.sleep(PAGE_SETTLE_MS)
        val anchor = videosShowing(app, hwnd).firstOrNull()
        val shapes = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        var unknown = 0
        val lost = mutableListOf<Int>()
        for (width in widths) {
            WindowShots.setContentSizeDp(hwnd, width, height)
            Thread.sleep(RESIZE_SETTLE_MS)
            val page = M3Page("width-$width", "Videos, $width dp wide", "Videos")
            shots += picture(app, hwnd, name, "Width sweep", page, folder.resolve("sweep-$width.png"))
            val (shape, railDp) = navShape(app, hwnd)
            val expected = NavShape.expected(width)
            shapes += "$width dp: ${shape.label}" + (railDp?.let { " (about ${it.roundToInt()} dp)" } ?: "") + "; the plan: ${expected.label}"
            if (shape == NavShape.UNKNOWN) unknown++ else if (shape != expected) wrong += "$width dp"
            if (anchor != null && anchor !in videosShowing(app, hwnd)) lost += width
        }
        val navProblem = when {
            wrong.isNotEmpty() -> "Not the plan's navigation at ${wrong.joinToString()}"
            unknown == widths.size -> "The navigation entries weren't found"
            else -> null
        }
        rows += M3Row("navigation by width", name, if (name != AFTER) Verdict.BEFORE else if (unknown == widths.size) Verdict.NOT_MEASURED else judged(name, navProblem), shapes.joinToString("\n"))
        rows += when {
            anchor == null -> M3Row("scroll kept across widths", name, Verdict.NOT_MEASURED, "The video cards have no names the Access Bridge can read, so the runner can't tell which one is at the top")
            else -> {
                val problem = if (lost.isEmpty()) null else "\"$anchor\", at the top before, wasn't on the page any more at ${lost.joinToString { "$it dp" }}"
                M3Row("scroll kept across widths", name, judged(name, problem), problem ?: "\"$anchor\" stayed on the page at every width")
            }
        }
    }

    private fun scrollDown(hwnd: Long, turns: Int) {
        val c = WindowShots.client(hwnd)
        repeat(turns) {
            Win32.postWheel(hwnd, c.left + (c.right - c.left) * 6 / 10, c.top + (c.bottom - c.top) * 6 / 10, -120)
            Thread.sleep(80)
        }
    }

    /** The video cards on the page, top to bottom and left to right, by their names. */
    private fun videosShowing(app: SkyWingTestApp, hwnd: Long): List<String> = app.withScreen { s ->
        val tree = s.windows.firstOrNull { it.hwnd == hwnd } ?: s.windows.first()
        val content = tree.nodes.firstOrNull()?.bounds
        tree.nodes.filter { VIDEO_NAME.containsMatchIn(it.label) && "showing" in it.states && (content == null || it.bounds.intersects(content)) }
            .sortedWith(compareBy({ it.bounds.y }, { it.bounds.x })).map { VIDEO_NAME.find(it.label)!!.value }.distinct()
    }

    private fun navShape(app: SkyWingTestApp, hwnd: Long): Pair<NavShape, Double?> = app.withScreen { s ->
        val tree = s.windows.firstOrNull { it.hwnd == hwnd } ?: s.windows.first()
        val content = tree.nodes.firstOrNull()?.bounds ?: return@withScreen NavShape.UNKNOWN to null
        val entries = tree.nodes.filter { it.roleName == "page tab" && it.label in SECTION_NAMES && "showing" in it.states }.map { it.bounds }
        val window = WindowShots.visible(hwnd)
        NavShape.of(entries, content, (window.right - window.left) / WindowShots.scale(hwnd))
    }

    // ---- window: size and place (6.8) ----

    private fun window(name: String, image: Path) {
        fun row(what: String, problem: String?, detail: String) {
            rows += M3Row("window: $what", name, judged(name, problem), problem ?: detail)
        }
        var data: Path? = null
        try {
            val first = SkyWingTestApp.launch("m3-$name-window", image = image)
            data = first.dataDir
            val moved: Placement
            try {
                val hwnd = first.mainWindow()
                Thread.sleep(WINDOW_SETTLE_MS)
                val start = WindowShots.placement(hwnd)
                row("first start", WindowRule.firstStart(start), start.describe())
                if (start.maximised) {
                    WindowShots.restore(hwnd)
                    Thread.sleep(SAVE_WAIT_MS)
                    val restored = WindowShots.placement(hwnd)
                    row("Restore after a maximised first start", WindowRule.restoredFromMaximised(restored), restored.describe())
                }
                // A size and place of the runner's own: 60 % of the work area, 10 % in from its top left corner.
                val work = WindowShots.workArea(hwnd)
                val w = work.right - work.left
                val h = work.bottom - work.top
                WindowShots.move(hwnd, work.left + w / 10, work.top + h / 10, w * 6 / 10, h * 6 / 10)
                Thread.sleep(SAVE_WAIT_MS)
                moved = WindowShots.placement(hwnd)
            } finally {
                first.close(keepData = true)
            }
            if (trial) return
            again(name, image, data, "remembered") { hwnd ->
                val p = WindowShots.placement(hwnd)
                row("reopened as it was closed", if (p.sameAs(moved)) null else "Closed as ${moved.describe()}, it reopened as ${p.describe()}", p.describe())
                WindowShots.maximise(hwnd)
                Thread.sleep(SAVE_WAIT_MS)
            }
            again(name, image, data, "maximised") { hwnd ->
                val p = WindowShots.placement(hwnd)
                row("reopened maximised", if (p.maximised) null else "Closed maximised, it reopened as ${p.describe()}", p.describe())
                WindowShots.restore(hwnd)
                Thread.sleep(SAVE_WAIT_MS)
                val r = WindowShots.placement(hwnd)
                row("then restored to the size before", if (r.sameAs(moved, 4.0)) null else "Restored, it is ${r.describe()}; before it was maximised it was ${moved.describe()}", r.describe())
                // Off every screen: as if the monitor it was on were unplugged.
                val v = WindowShots.virtualScreen()
                val outer = WindowShots.visible(hwnd)
                WindowShots.move(hwnd, v.right + 400, v.top + 100, outer.right - outer.left, outer.bottom - outer.top)
                Thread.sleep(SAVE_WAIT_MS)
            }
            again(name, image, data, "off-screen") { hwnd ->
                val title = WindowShots.titleOnScreenDp(hwnd)
                row("reopened on a screen after it was moved off them", WindowRule.backOnScreen(title), "${title.roundToInt()} dp of the title bar on a screen")
                val work = WindowShots.workArea(hwnd)
                WindowShots.move(hwnd, work.left + 40, work.top + 40, 200, 150)
                Thread.sleep(SAVE_WAIT_MS)
                val small = WindowShots.placement(hwnd)
                row("minimum size", WindowRule.minimum(small), small.describe())
            }
        } finally {
            data?.toFile()?.deleteRecursively()
        }
    }

    private fun again(name: String, image: Path, data: Path, step: String, block: (Long) -> Unit) {
        val app = SkyWingTestApp.launch("m3-$name-window-$step", dataDir = data, image = image)
        try {
            val hwnd = app.mainWindow()
            Thread.sleep(WINDOW_SETTLE_MS)
            block(hwnd)
        } finally {
            app.close(keepData = true)
        }
    }

    // ---- settings: moving around Settings (6.5, B1) ----

    private fun settings(name: String, image: Path) {
        fun row(what: String, verdict: Verdict, detail: String) {
            rows += M3Row("settings: $what", name, if (name != AFTER && verdict != Verdict.NOT_MEASURED) Verdict.BEFORE else verdict, detail)
        }
        val app = SkyWingTestApp.launch("m3-$name-settings", image = image)
        try {
            val hwnd = app.mainWindow()
            Thread.sleep(WINDOW_SETTLE_MS)
            val (roomWidth, roomHeight) = roomDp(hwnd)
            // Two panes from 900 dp (the owner's choice, M3-DESIGN-PREP.md decision O1): Expanded, and Medium above 900 dp.
            for (width in TWO_PANE_WIDTHS.filter { it <= roomWidth }) {
                WindowShots.setContentSizeDp(hwnd, width, minOf(760.0, roomHeight).toInt())
                Thread.sleep(RESIZE_SETTLE_MS)
                val what = "two panes at $width dp: the category list beside the page (B1)"
                if (!openPlayback(app)) row(what, Verdict.NOT_MEASURED, "The Playback page didn't open")
                else if (categoryShowing(app)) row(what, Verdict.PASS, "At $width dp the categories stay on screen while Playback is open")
                else row(what, Verdict.FAIL, "At $width dp the categories went away while Playback was open")
            }
            // One pane under either of the plan's edges: 6.5 has two panes from 900 dp, 6.6 from 720 dp.
            WindowShots.setContentSizeDp(hwnd, 680, minOf(720.0, roomHeight).toInt())
            Thread.sleep(RESIZE_SETTLE_MS)
            val ways = linkedMapOf<String, Pair<Boolean, () -> Boolean>>(
                "Settings again" to (false to { activateFirst(app, listOf("Settings")) }),
                "Esc" to (true to { WindowShots.postEscape(hwnd); true }),
                "the mouse back button" to (true to { WindowShots.postMouseBack(hwnd); true }),
                "the Back button" to (false to { activateBack(app) }),
                "another section and back" to (false to { activateFirst(app, listOf("Folders")) && run { Thread.sleep(PAGE_SETTLE_MS); activateFirst(app, listOf("Settings")) } }),
            )
            for ((way, entry) in ways) {
                val (posted, act) = entry
                val what = "one pane: back to the list with $way"
                if (!openPlayback(app)) {
                    row(what, Verdict.NOT_MEASURED, "The Playback page didn't open")
                    continue
                }
                if (posted && Win32.foregroundProcess() !in app.processIds()) {
                    row(what, Verdict.NOT_MEASURED, "SkyWing wasn't the active window, so a posted key or button can't reach it")
                    continue
                }
                if (!act()) {
                    row(what, Verdict.FAIL, "There was nothing to press")
                    continue
                }
                val back = waitFor(BACK_WAIT_MS) { listShown(app) }
                row(what, if (back) Verdict.PASS else Verdict.FAIL, if (back) "The category list came back" else "Playback stayed open")
            }
        } finally {
            app.close()
        }
    }

    /** Opens Settings › Playback, from wherever SkyWing is. */
    private fun openPlayback(app: SkyWingTestApp): Boolean {
        if (!activateFirst(app, listOf("Settings"))) return false
        Thread.sleep(PAGE_SETTLE_MS)
        if (playbackOpen(app)) return true
        if (!activateFirst(app, listOf("Playback")) && !(activateBack(app) && activateFirst(app, listOf("Playback")))) return false
        return waitFor(BACK_WAIT_MS) { playbackOpen(app) }
    }

    private fun playbackOpen(app: SkyWingTestApp): Boolean = app.withScreen { s ->
        s.nodes.any { it.roleName == "check box" && it.label in PLAYBACK_MARKERS && "showing" in it.states }
    }

    private fun categoryShowing(app: SkyWingTestApp): Boolean = app.withScreen { s ->
        s.nodes.any { it.label in M3Pages.CATEGORIES && it.label != "Playback" && it.roleName in NAV_ROLES && "showing" in it.states }
    }

    private fun listShown(app: SkyWingTestApp): Boolean = !playbackOpen(app) && categoryShowing(app)

    // ---- shared ----

    private fun exists(app: SkyWingTestApp, label: String): Boolean =
        app.withScreen { s -> s.nodes.any { node -> NAV_ROLES.any { ControlQuery.matches(node, label, it) } && node.actions.isNotEmpty() } }

    /** Activates the first of [labels] that a navigation control (tab, button) has. False when none could be. */
    private fun activateFirst(app: SkyWingTestApp, labels: List<String>): Boolean {
        for (label in labels) {
            val role = app.withScreen { s -> NAV_ROLES.firstOrNull { r -> s.nodes.any { ControlQuery.matches(it, label, r) && it.actions.isNotEmpty() } } } ?: continue
            return runCatching { app.activate(label, role) { it.actions.isNotEmpty() } }
                .onFailure { println("Couldn't press \"$label\": ${it.message}") }.isSuccess
        }
        return false
    }

    /** Presses the one Back button a page shows ("Back", "Back to Settings"). */
    private fun activateBack(app: SkyWingTestApp): Boolean {
        val label = app.withScreen { s ->
            s.nodes.filter { it.roleName == "push button" && it.label.startsWith("Back") && it.actions.isNotEmpty() && "showing" in it.states }.map { it.label }.distinct().singleOrNull()
        } ?: return false
        return runCatching { app.activate(label, "push button") { "showing" in it.states } }.isSuccess
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun awaitLibrary(app: SkyWingTestApp, log: Path, videos: Int): Boolean {
        fun loaded() = PerfLog.read(log).mark("library loaded") { (it.values["videos"] ?: 0.0) >= videos } != null
        waitFor(LIBRARY_WAIT_MS) { !app.isRunning || loaded() }
        check(app.isRunning) { "SkyWing closed while it loaded the library." }
        return loaded()
    }

    /** The most window content, in dp, a normal window shows on its screen: the work area less the frame and title bar. */
    private fun roomDp(hwnd: Long): Pair<Double, Double> {
        val scale = WindowShots.scale(hwnd)
        val work = WindowShots.workArea(hwnd)
        return (work.right - work.left) / scale - FRAME_WIDTH_DP to (work.bottom - work.top) / scale - FRAME_HEIGHT_DP
    }

    /** The after image is judged: null [problem] passes. The before image is shown only. */
    private fun judged(name: String, problem: String?): Verdict = when {
        name != AFTER -> Verdict.BEFORE
        problem == null -> Verdict.PASS
        else -> Verdict.FAIL
    }

    private fun writeReports(display: String) {
        val builds = images.keys.associateWith { buildOf(it) }
        results.resolve("M3-table.md").writeText(m3Summary(device, display, builds, rows))
        val measured = images.keys.associateWith(::numbersOf).filterValues { it.isNotEmpty() }
        if (measured.isNotEmpty()) results.resolve("numbers").resolve("M1-table.md").writeText(m1Table(device, display, images.keys.associateWith(::numbersOf)))
        if (shots.isNotEmpty()) {
            copyBoards()
            results.resolve("gallery.html").writeText(galleryHtml(device, shots) { size, page ->
                (M3Pages.SIZES.firstOrNull { it.label == size } ?: nearestBoard[size])?.let { M3Pages.board(it, page) }?.let { "boards/$it" }
            })
        }
        if (controls.isNotEmpty()) results.resolve("access.md").writeText(buildString {
            appendLine("# Accessibility audit (plan 6.9)")
            appendLine()
            appendLine("Read through Java Access Bridge at ${M3Pages.DESKTOP.label}, as a screen reader reads SkyWing. It changes nothing.")
            appendLine()
            append(AccessAudit.table(findings, controls))
            val all = findings.values.flatten()
            if (all.isNotEmpty()) {
                appendLine()
                appendLine("## What a screen reader can't tell")
                appendLine()
                findings.forEach { (page, list) -> list.forEach { appendLine("- $page: ${it.kind}, at ${it.node}") } }
            }
        })
    }

    private fun copyBoards() {
        if (!boards.isDirectory()) return
        val target = results.resolve("boards").createDirectories()
        boards.listDirectoryEntries("*.png").forEach { Files.copy(it, target.resolve(it.name), StandardCopyOption.REPLACE_EXISTING) }
    }

    /** The commit the runner's image was built from (images\<name>-build.txt). */
    private fun buildOf(name: String): String =
        runCatching { runner.resolve("images").resolve("$name-build.txt").readText().trim() }.getOrDefault("unknown")

    private companion object {
        const val AFTER = "after"
        const val BIOS = "HKLM\\HARDWARE\\DESCRIPTION\\System\\BIOS"
        const val COOL_DOWN_MS = 30_000L
        const val GALLERY_FOLDERS = 6
        const val GALLERY_PER_FOLDER = 8
        const val LIBRARY_WAIT_MS = 90_000L
        const val RESIZE_SETTLE_MS = 1_200L
        /** Settings' two-pane widths: Expanded, and Medium above the 900 dp edge. */
        val TWO_PANE_WIDTHS = listOf(1300, 1000)
        const val PAGE_SETTLE_MS = 1_500L
        const val WINDOW_SETTLE_MS = 2_500L
        /** SkyWing saves the window 500 ms after it last moved (6.8 rule 2); the runner waits three times that. */
        const val SAVE_WAIT_MS = 1_500L
        const val BACK_WAIT_MS = 3_000L
        const val SWEEP_WHEEL_TURNS = 15
        /** A normal window's frame and title bar around its content, about: Windows 11 draws 32 dp of title bar. */
        const val FRAME_WIDTH_DP = 16.0
        const val FRAME_HEIGHT_DP = 40.0
        val NAV_ROLES = listOf("page tab", "push button")
        val SECTION_NAMES = M3Pages.SECTIONS.flatten().toSet()
        val PLAYBACK_MARKERS = setOf("Smooth at screen size", "Motion smoothing", "Lower smoothing automatically")
        val VIDEO_NAME = Regex("video \\d{5}")
    }
}
