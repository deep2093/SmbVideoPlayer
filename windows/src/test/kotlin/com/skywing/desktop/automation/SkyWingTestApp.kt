package com.skywing.desktop.automation

import com.skywing.testsupport.IsolatedPreferencesFactory
import com.sun.jna.Callback
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/** The controls of every SkyWing window at one moment, with the trees that own their handles. */
internal class Screen(val windows: List<JabTree>) {
    val nodes: List<JabNode> get() = windows.flatMap { it.nodes }
    fun treeOf(node: JabNode): JabTree = windows.first { node in it.nodes }
    fun dump(): String = windows.joinToString("\n\n") { "window ${it.hwnd}:\n" + it.dump() }
}

/**
 * A SkyWing test image started with its own data: java.util.prefs go to a file ([IsolatedPreferencesFactory]),
 * LOCALAPPDATA, APPDATA and user.home point into a temporary directory, and Java Access Bridge is enabled for this
 * process only. The user's installed SkyWing, registry preferences, library and Windows settings are not touched.
 *
 * Controls are found by [ControlQuery] (label and role, unique match). Lookups are retried until a timeout because
 * the UI changes asynchronously; actions are never retried, so a click cannot happen twice. Failures write the
 * current accessibility trees to `build/reports/automation`.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
internal class SkyWingTestApp private constructor(
    val dataDir: Path,
    val preferencesFile: Path,
    private val process: Process,
    private val bridge: AccessBridgeClient,
    private val testName: String,
) : AutoCloseable {
    val pid: Long get() = process.pid()

    /** After [close]: true when every process of the launched tree exited by itself after WM_CLOSE. */
    var exitedGracefully: Boolean? = null
        private set

    /** A fresh view of the isolated preferences file, as the app last wrote it. */
    fun preferences(): Preferences = IsolatedPreferencesFactory.openUserRoot(preferencesFile)

    /** Visible top-level Java windows owned by the launched process or its children (the launcher starts a child). */
    fun windows(): List<Long> {
        val pids = buildSet {
            add(process.pid())
            process.descendants().forEach { add(it.pid()) }
        }
        return Win32.visibleWindows().filter { (hwnd, owner) -> owner in pids && bridge.isJavaWindow(hwnd) }.map { it.first }
    }

    /** Waits for SkyWing's main window and returns its handle. */
    fun mainWindow(timeoutMs: Long = LAUNCH_TIMEOUT_MS): Long = poll("the SkyWing main window", timeoutMs) {
        windows().firstOrNull { Win32.title(it) == MAIN_TITLE }
    }

    /** Runs [block] on a snapshot of all SkyWing windows and releases the snapshot afterwards. */
    fun <T> withScreen(block: (Screen) -> T): T {
        val trees = mutableListOf<JabTree>()
        try {
            windows().forEach { hwnd -> runCatching { bridge.snapshot(hwnd) }.onSuccess { trees += it } }
            return block(Screen(trees))
        } finally {
            trees.forEach { runCatching { bridge.release(it) } }
        }
    }

    /** Waits until exactly one control matches; returns a detached copy of it (its handle must not be used). */
    fun find(label: String, role: String, timeoutMs: Long = UI_TIMEOUT_MS, where: (JabNode) -> Boolean = { true }): JabNode =
        retryQuery("$role '$label'", timeoutMs) { screen -> ControlQuery.single(screen.nodes, label, role, where) }

    /** True when no control matches now. Never waits: callers use [waitUntil] for disappearance. */
    fun isAbsent(label: String, role: String): Boolean = withScreen { s -> s.nodes.none { ControlQuery.matches(it, label, role) } }

    /**
     * Finds exactly one enabled control with an action and performs its first action once. The lookup may wait; the
     * action is not repeated even if the UI does not respond, so callers confirm the outcome with [waitUntil].
     */
    fun activate(label: String, role: String, timeoutMs: Long = UI_TIMEOUT_MS, where: (JabNode) -> Boolean = { true }): JabNode =
        retryQuery("$role '$label'", timeoutMs) { screen ->
            val node = ControlQuery.single(screen.nodes, label, role, where)
            if (!node.enabled) throw RefusedAction("Refusing to activate a disabled control: $node")
            if (node.actions.isEmpty()) throw RefusedAction("Control has no accessible action: $node")
            bridge.doFirstAction(screen.treeOf(node), node)
            node
        }

    /**
     * Replaces the text of one field. Compose fields do not accept the bridge's setTextContents, so the field is
     * focused with its accessible click action and the text is posted to SkyWing's own main window as WM_CHAR
     * messages, after [erase] backspaces. No global input, no coordinates.
     */
    fun typeInto(label: String, text: String, erase: Int = 0) {
        // A filled Compose field takes its text as its accessible name; its label stays in the description.
        val field: (JabNode) -> Boolean = { it.roleName == "text" && it.description == label }
        retryQuery("text field '$label'", UI_TIMEOUT_MS) { screen ->
            val matches = screen.nodes.filter(field)
            if (matches.size != 1) throw AssertionError("Expected one text field described '$label', found ${matches.size}")
            val node = matches.single()
            if (!node.enabled || node.actions.isEmpty()) throw RefusedAction("Refusing to focus $node")
            bridge.doFirstAction(screen.treeOf(node), node)
        }
        waitUntil("'$label' to have focus") { s -> s.nodes.any { field(it) && "focused" in it.states } }
        val window = mainWindow()
        repeat(erase) { Win32.postKey(window, Win32.VK_BACK, Win32.SCAN_BACK) }
        Win32.postText(window, text)
    }

    /** Polls the screen until [condition] holds. */
    fun waitUntil(what: String, timeoutMs: Long = UI_TIMEOUT_MS, condition: (Screen) -> Boolean) {
        retryQuery(what, timeoutMs) { screen -> if (!condition(screen)) throw AssertionError("Still waiting for $what") }
    }

    /** Writes the current trees to the report directory and returns the file. */
    fun saveDiagnostics(reason: String): Path {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss-SSS"))
        val file = REPORTS.resolve("$testName-$stamp.txt")
        REPORTS.createDirectories()
        val trees = runCatching { withScreen { it.dump() } }.getOrElse { "Trees unavailable: $it" }
        file.writeText("$reason\n\npid=$pid alive=${process.isAlive}\n\n$trees\n")
        return file
    }

    private fun <T> retryQuery(what: String, timeoutMs: Long, query: (Screen) -> T): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var last: Throwable? = null
        while (true) {
            check(process.isAlive) { "SkyWing exited (code ${process.exitValue()}) while waiting for $what" }
            try {
                return withScreen(query)
            } catch (refused: RefusedAction) {
                fail(refused.message!!, refused)
            } catch (ambiguous: AmbiguousMatchError) {
                fail(ambiguous.message!!, ambiguous)
            } catch (e: AssertionError) {
                last = e
            } catch (e: IllegalStateException) {
                last = e
            }
            if (System.nanoTime() > deadline) fail("Timed out after $timeoutMs ms waiting for $what: ${last?.message}", last)
            Thread.sleep(POLL_MS)
        }
    }

    private fun fail(message: String, cause: Throwable?): Nothing {
        val report = saveDiagnostics(message)
        throw AssertionError("$message\nAccessibility trees: $report", cause)
    }

    private fun <T : Any> poll(what: String, timeoutMs: Long, probe: () -> T?): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            check(process.isAlive) { "SkyWing exited (code ${process.exitValue()}) while waiting for $what" }
            probe()?.let { return it }
            if (System.nanoTime() > deadline) fail("Timed out after $timeoutMs ms waiting for $what", null)
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * Closes the app the way a user would (WM_CLOSE to its windows), then force-stops what is left. Only the process
     * tree this object launched is touched. The temporary data directory is deleted unless [keepData].
     */
    fun close(keepData: Boolean) {
        if (exitedGracefully != null) {
            if (!keepData) deleteData()
            return
        }
        val tree = buildList { add(process.toHandle()); addAll(process.descendants().toList()) }
        runCatching { windows().forEach(Win32::close) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_TIMEOUT_MS)
        while (tree.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        val survivors = tree.filter { it.isAlive }
        exitedGracefully = survivors.isEmpty()
        survivors.forEach { it.destroyForcibly() }
        tree.forEach { runCatching { it.onExit().get(10, TimeUnit.SECONDS) } }
        if (!keepData) deleteData()
    }

    /** Windows can hold a file briefly after its process exits, so deletion is retried before it is reported. */
    private fun deleteData() {
        var error: Throwable? = null
        repeat(20) {
            error = runCatching { dataDir.deleteRecursively() }.exceptionOrNull() ?: return
            Thread.sleep(250)
        }
        System.err.println("SkyWing test data could not be deleted: $dataDir ($error)")
    }

    override fun close() = close(keepData = false)

    private class RefusedAction(message: String) : RuntimeException(message)

    companion object {
        const val MAIN_TITLE = "SkyWing for Windows (Development Preview)"
        private const val LAUNCH_TIMEOUT_MS = 60_000L
        const val UI_TIMEOUT_MS = 10_000L
        private const val CLOSE_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 150L

        /**
         * The app image the tests launch: the one `createDistributable` builds, or the one the SKYWING_TEST_IMAGE
         * environment variable names (`verify.ps1 -Image` sets it to test an existing build). An environment variable,
         * because Gradle passes its own -D options to the build JVM, not to the test JVM.
         */
        val image: Path = Path.of(System.getenv("SKYWING_TEST_IMAGE")?.takeIf { it.isNotBlank() } ?: "build/compose/binaries/main/app/SkyWing")
            .toAbsolutePath().normalize()

        private val REPORTS: Path = Path.of("build/reports/automation").toAbsolutePath()

        /** Where the test JVM compiled [IsolatedPreferencesFactory]; the app loads it from its boot class path. */
        private val supportClasses: Path =
            Path.of(IsolatedPreferencesFactory::class.java.protectionDomain.codeSource.location.toURI())

        /**
         * Starts the test image with isolated data. [seed] writes preferences before launch. Pass the [dataDir] of an
         * earlier session (closed with `keepData = true`) to relaunch on the same data. [args] go to SkyWing (a video
         * path opens it in the player). The caller owns the returned
         * app and must close it (use `use { }`).
         */
        fun launch(
            testName: String,
            dataDir: Path? = null,
            args: List<String> = emptyList(),
            seed: (Preferences, Path) -> Unit = { _, _ -> },
        ): SkyWingTestApp {
            val launcher = image.resolve("SkyWing.exe")
            check(Files.isRegularFile(launcher)) {
                "No test image at $image. Run `gradlew -p windows createDistributable` first, or set SKYWING_TEST_IMAGE to an app image."
            }
            val bridge = AccessBridgeClient.forImage(image)
            val dataDir = dataDir ?: Files.createTempDirectory("skywing-ui-test-")
            val home = dataDir.resolve("home").createDirectories()
            val local = dataDir.resolve("LocalAppData").createDirectories()
            val roaming = dataDir.resolve("AppData").createDirectories()
            val preferencesFile = dataDir.resolve("preferences.properties")
            val root = IsolatedPreferencesFactory.openUserRoot(preferencesFile)
            root.node("com/skywing/desktop").putBoolean("automation.isolated", true)
            seed(root, dataDir)
            val options = listOf(
                "-Xbootclasspath/a:${supportClasses.absolutePathString()}",
                "-Djava.util.prefs.PreferencesFactory=${IsolatedPreferencesFactory::class.java.name}",
                "-D${IsolatedPreferencesFactory.FILE_PROPERTY}=${preferencesFile.absolutePathString()}",
                "-Djavax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge",
                "-Duser.home=${home.absolutePathString()}",
            )
            require(options.none { it.contains(' ') }) { "Test paths must not contain spaces: $options" }
            val process = ProcessBuilder(listOf(launcher.toString()) + args)
                .directory(dataDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dataDir.resolve("app-output.txt").toFile())
                .apply {
                    environment()["JAVA_TOOL_OPTIONS"] = options.joinToString(" ")
                    environment()["LOCALAPPDATA"] = local.absolutePathString()
                    environment()["APPDATA"] = roaming.absolutePathString()
                    environment()["USERPROFILE"] = home.absolutePathString()
                }
                .start()
            val app = SkyWingTestApp(dataDir, preferencesFile, process, bridge, testName)
            try {
                app.mainWindow()
            } catch (e: Throwable) {
                app.close(keepData = true)
                throw AssertionError("SkyWing did not start; output kept in ${dataDir.resolve("app-output.txt")}", e)
            }
            return app
        }
    }
}

/** The few user32 calls needed to find and close SkyWing's windows. */
internal object Win32 {
    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun interface EnumProc : Callback { fun invoke(hwnd: Pointer, data: Pointer?): Boolean }
        fun EnumWindows(proc: EnumProc, data: Pointer?): Boolean
        fun GetWindowThreadProcessId(hwnd: Pointer, pid: IntByReference): Int
        fun IsWindowVisible(hwnd: Pointer): Boolean
        fun GetWindowTextW(hwnd: Pointer, text: CharArray, max: Int): Int
        fun PostMessageW(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Boolean
        fun GetWindowRect(hwnd: Pointer, rect: Rect): Boolean
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfo): Boolean
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    open class Rect : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0
        fun bounds() = Bounds(left, top, right, bottom)
    }

    @Structure.FieldOrder("cbSize", "monitor", "work", "flags")
    class MonitorInfo : Structure() {
        @JvmField var cbSize = 0
        @JvmField var monitor = Rect()
        @JvmField var work = Rect()
        @JvmField var flags = 0
        init { cbSize = size() }
    }

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private val user32: User32 = Native.load("user32", User32::class.java)
    private const val WM_CLOSE = 0x0010
    private const val WM_KEYDOWN = 0x0100
    private const val WM_KEYUP = 0x0101
    private const val WM_CHAR = 0x0102
    const val VK_BACK = 0x08
    const val SCAN_BACK = 0x0E
    const val VK_SPACE = 0x20
    const val SCAN_SPACE = 0x39
    const val VK_F = 0x46
    const val SCAN_F = 0x21
    const val VK_SHIFT = 0x10
    const val SCAN_SHIFT = 0x2A

    /** (hwnd, owning pid) of every visible top-level window. */
    fun visibleWindows(): List<Pair<Long, Long>> {
        val found = mutableListOf<Pair<Long, Long>>()
        user32.EnumWindows({ hwnd, _ ->
            if (user32.IsWindowVisible(hwnd)) {
                val pid = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, pid)
                found += Pointer.nativeValue(hwnd) to pid.value.toLong()
            }
            true
        }, null)
        return found
    }

    fun title(hwnd: Long): String {
        val buffer = CharArray(512)
        val length = user32.GetWindowTextW(Pointer(hwnd), buffer, buffer.size)
        return String(buffer, 0, length)
    }

    fun close(hwnd: Long) { user32.PostMessageW(Pointer(hwnd), WM_CLOSE, 0, 0) }

    /**
     * Posts one key press (down, then up) to [hwnd]'s message queue. It goes to that window only: no global input,
     * no focus change and no coordinates. Used for shortcuts that have no on-screen control (hidden full-screen chrome).
     */
    fun postKey(hwnd: Long, virtualKey: Int, scanCode: Int) {
        val down = 1L or (scanCode.toLong() shl 16)
        user32.PostMessageW(Pointer(hwnd), WM_KEYDOWN, virtualKey.toLong(), down)
        user32.PostMessageW(Pointer(hwnd), WM_KEYUP, virtualKey.toLong(), down or 0xC0000000L)
    }

    /** Posts [text] to [hwnd] as WM_CHAR messages, one per UTF-16 unit. */
    fun postText(hwnd: Long, text: String) = text.forEach { user32.PostMessageW(Pointer(hwnd), WM_CHAR, it.code.toLong(), 1L) }

    /** The window's screen rectangle, read without moving or focusing it. */
    fun bounds(hwnd: Long): Bounds = Rect().also { check(user32.GetWindowRect(Pointer(hwnd), it)) }.bounds()

    /** The full rectangle of the monitor the window is on (MONITOR_DEFAULTTONEAREST). */
    fun monitorBounds(hwnd: Long): Bounds {
        val monitor = checkNotNull(user32.MonitorFromWindow(Pointer(hwnd), 2)) { "no monitor for window" }
        return MonitorInfo().also { check(user32.GetMonitorInfoW(monitor, it)) }.monitor.bounds()
    }
}
