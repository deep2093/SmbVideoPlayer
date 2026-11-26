package com.skywing.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.google.gson.JsonObject
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/** A rectangle in dp: AWT's units on Windows, which follow each screen's scaling. */
internal data class Area(val x: Double, val y: Double, val width: Double, val height: Double) {
    val right: Double get() = x + width
    val bottom: Double get() = y + height
}

/** A screen, named by its device, resolution and scaling, and its work area (the screen less the taskbar). */
internal data class ScreenInfo(val id: String, val work: Area)

/** How the window opens: maximised or not, and the normal window, which Restore gives when it opens maximised. */
internal data class WindowStart(val maximised: Boolean, val normal: Area)

/** What the window remembers: the screen it was last on, and its placement on each screen. */
internal data class SavedWindow(val last: String?, val byScreen: Map<String, WindowStart>)

/** "Window at start" in Appearance (plan 6.8 rule 6). [share] is the part of the work area a sized choice takes. */
internal enum class StartMode(val id: String, val label: String, val share: Double) {
    REMEMBER("remember", "Remember last", 0.0),
    MAXIMISED("maximised", "Always maximised", 0.0),
    SMALL("small", "Small", 0.60),
    MEDIUM("medium", "Medium", 0.75),
    LARGE("large", "Large", 0.90),
    ;

    companion object {
        fun of(id: String?): StartMode = entries.firstOrNull { it.id == id } ?: REMEMBER
    }
}

/** The window rules of plan 6.8 (M3 design brief 5.6), apart from the screens and the window themselves. */
internal object WindowSizing {
    const val MIN_WIDTH = 640.0
    const val MIN_HEIGHT = 480.0
    private const val RATIO = 1.6
    private const val SMALL_WIDTH = 1400.0
    private const val SMALL_HEIGHT = 900.0
    private const val TITLE_HEIGHT = 32.0
    private const val TITLE_ON_SCREEN = 64.0

    /**
     * Rule 1: a work area under 1400 × 900 dp opens maximised, a bigger one as a centred window at 75 % of it, between
     * 960 × 600 and 1600 × 1000 dp, in 16:10. The normal window is that 75 % one either way, so Restore after a
     * maximised start gives it (rule 4).
     */
    fun firstStart(work: Area): WindowStart =
        WindowStart(work.width < SMALL_WIDTH || work.height < SMALL_HEIGHT, centred(work, 0.75, 960.0, 1600.0))

    /** A centred 16:10 window [share] of [work] on its tighter side, its width between [least] and [most], inside [work]. */
    fun centred(work: Area, share: Double, least: Double = MIN_WIDTH, most: Double = Double.MAX_VALUE): Area {
        val wanted = minOf(work.width * share, work.height * share * RATIO).coerceIn(least, most)
        val width = maxOf(minOf(wanted, work.width), MIN_WIDTH)
        val height = maxOf(minOf(wanted / RATIO, work.height), MIN_HEIGHT)
        return Area(work.x + (work.width - width) / 2, work.y + (work.height - height) / 2, width, height)
    }

    /**
     * Where the window opens. Remember last (rule 3): the placement saved for the screen it was last on, kept at the
     * minimum size, when that screen is still there, the window fits its work area and at least 64 dp of the title
     * bar is on a screen; otherwise rule 1. The other choices of [mode] size the window afresh.
     */
    fun choose(saved: SavedWindow, screens: List<ScreenInfo>, primary: ScreenInfo, mode: StartMode = StartMode.REMEMBER): WindowStart {
        val screen = screens.firstOrNull { it.id == saved.last }
        when (mode) {
            StartMode.REMEMBER -> Unit
            StartMode.MAXIMISED -> return firstStart((screen ?: primary).work).copy(maximised = true)
            else -> return WindowStart(false, centred((screen ?: primary).work, mode.share))
        }
        val start = screen?.let { saved.byScreen[it.id] } ?: return firstStart(primary.work)
        val normal = start.normal.copy(width = maxOf(start.normal.width, MIN_WIDTH), height = maxOf(start.normal.height, MIN_HEIGHT))
        val fits = normal.width <= screen.work.width + 1 && normal.height <= screen.work.height + 1
        if (!fits || titleOnScreen(normal, screens) < TITLE_ON_SCREEN) return firstStart(screen.work)
        return start.copy(normal = normal)
    }

    /**
     * What to save for [screen]: the normal window when its centre is on that screen and it fits the work area,
     * otherwise the screen's first-start window (a window maximised after a move to another screen, or a maximised
     * frame read as the normal window, would come back in the wrong place or too big).
     */
    fun forScreen(start: WindowStart, screen: ScreenInfo): WindowStart {
        val normal = start.normal
        val work = screen.work
        val centreX = normal.x + normal.width / 2
        val centreY = normal.y + normal.height / 2
        val onScreen = centreX in work.x..work.right && centreY in work.y..work.bottom
        val fits = normal.width <= work.width + 1 && normal.height <= work.height + 1
        return if (onScreen && fits) start else start.copy(normal = firstStart(work).normal)
    }

    /** How much of the title bar's width is on the screen that shows most of it. */
    private fun titleOnScreen(window: Area, screens: List<ScreenInfo>): Double = screens.maxOfOrNull { screen ->
        val work = screen.work
        val titleY = window.y + TITLE_HEIGHT / 2
        if (titleY < work.y || titleY > work.bottom) 0.0 else (minOf(window.right, work.right) - maxOf(window.x, work.x)).coerceAtLeast(0.0)
    } ?: 0.0
}

/**
 * Where the window was, per screen, and the "Window at start" choice, in `window.json` beside the library (rule 2).
 * A file another program holds is left alone.
 */
internal class WindowPlacementStore(private val file: JsonFile = JsonFile(libraryFile("window.json"), background = true)) {
    fun load(): SavedWindow {
        val root = (file.read() as? JsonFile.Read.Found)?.root ?: return SavedWindow(null, emptyMap())
        val screens = root.get(SCREENS) as? JsonObject
        val byScreen = screens?.entrySet()?.mapNotNull { (id, value) ->
            runCatching {
                val entry = value.asJsonObject
                id to WindowStart(
                    entry.get("maximised").asBoolean,
                    Area(entry.get("x").asDouble, entry.get("y").asDouble, entry.get("width").asDouble, entry.get("height").asDouble),
                )
            }.getOrNull()
        }?.toMap().orEmpty()
        return SavedWindow(runCatching { root.get(LAST)?.asString }.getOrNull(), byScreen)
    }

    fun mode(): StartMode = StartMode.of(runCatching { (file.read() as? JsonFile.Read.Found)?.root?.get(MODE)?.asString }.getOrNull())

    fun save(screen: String, start: WindowStart) = update { root ->
        val screens = root.get(SCREENS) as? JsonObject ?: JsonObject().also { root.add(SCREENS, it) }
        screens.add(screen, JsonObject().apply {
            addProperty("maximised", start.maximised)
            addProperty("x", start.normal.x)
            addProperty("y", start.normal.y)
            addProperty("width", start.normal.width)
            addProperty("height", start.normal.height)
        })
        root.addProperty(LAST, screen)
    }

    fun setMode(mode: StartMode) = update { it.addProperty(MODE, mode.id) }

    /** "Reset window size": the saved placements go; the start choice stays. */
    fun forget() = update { root ->
        root.remove(SCREENS)
        root.remove(LAST)
    }

    private fun update(change: (JsonObject) -> Unit) = synchronized(file.lock) {
        val root = when (val read = file.read()) {
            is JsonFile.Read.Found -> read.root
            JsonFile.Read.Missing -> JsonObject()
            JsonFile.Read.Locked -> return@synchronized
        }
        change(root)
        runCatching { file.write(root) }.onFailure { System.err.println("SkyWing could not save the window placement: ${it.message}") }
    }

    private companion object {
        const val SCREENS = "screens"
        const val LAST = "last"
        const val MODE = "start"
    }
}

/** The screens as AWT sees them, in dp. */
internal object WindowScreens {
    private val fallback = ScreenInfo("unknown", Area(0.0, 0.0, 1600.0, 1000.0))

    fun all(): List<ScreenInfo> =
        runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { of(it.defaultConfiguration) } }.getOrDefault(emptyList())

    fun primary(): ScreenInfo =
        runCatching { of(GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration) }.getOrDefault(fallback)

    fun of(config: GraphicsConfiguration): ScreenInfo {
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        val mode = config.device.displayMode
        val scale = (config.defaultTransform.scaleX * 100).roundToInt()
        return ScreenInfo(
            "${config.device.iDstring} ${mode.width}x${mode.height} $scale%",
            Area(
                (bounds.x + insets.left).toDouble(), (bounds.y + insets.top).toDouble(),
                (bounds.width - insets.left - insets.right).toDouble(), (bounds.height - insets.top - insets.bottom).toDouble(),
            ),
        )
    }
}

/** The window state SkyWing opens with, by the rules above. */
internal fun startWindowState(store: WindowPlacementStore): Pair<WindowStart, WindowState> {
    val start = WindowSizing.choose(store.load(), WindowScreens.all(), WindowScreens.primary(), store.mode())
    val state = WindowState(
        placement = if (start.maximised) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = WindowPosition(start.normal.x.dp, start.normal.y.dp),
        size = DpSize(start.normal.width.dp, start.normal.height.dp),
    )
    return start to state
}

/**
 * Keeps the normal window and the maximised state of the main window (rule 2). The full-screen player is never
 * saved: [fullscreen] is set by its owner, and the bounds of the moments around a change are not taken either.
 */
internal class WindowPlacementSaver(val store: WindowPlacementStore, start: WindowStart) {
    private var normal = start.normal

    /** The main window, once it is shown. */
    var window: Window? = null
    private var changedAt: Long? = null

    var fullscreen = false
        set(value) {
            field = value
            changedAt = System.nanoTime()
        }

    private val settling: Boolean
        get() = fullscreen || changedAt?.let { System.nanoTime() - it < SETTLE_NS } == true

    /** Takes the window's bounds as the normal window while it is a normal, shown window. */
    fun observe(state: WindowState) {
        if (settling || state.isMinimized || state.placement != WindowPlacement.Floating) return
        // Compose can still say Floating for a moment after Windows has maximised the frame.
        if ((window as? Frame)?.let { it.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH } == true) return
        val position = state.position as? WindowPosition.Absolute ?: return
        normal = Area(position.x.value.toDouble(), position.y.value.toDouble(), state.size.width.value.toDouble(), state.size.height.value.toDouble())
    }

    /** What to save now, or null when nothing should be. */
    fun pending(state: WindowState): Pair<String, WindowStart>? {
        if (settling || state.isMinimized || state.placement == WindowPlacement.Fullscreen) return null
        val screen = window?.graphicsConfiguration?.let { runCatching { WindowScreens.of(it) }.getOrNull() } ?: return null
        return screen.id to WindowSizing.forScreen(WindowStart(state.placement == WindowPlacement.Maximized, normal), screen)
    }

    private companion object {
        const val SETTLE_NS = 1_000_000_000L
    }
}

/** Saves the placement 500 ms after a move or resize, and keeps the window at least 640 × 480 dp (rule 5). */
@OptIn(FlowPreview::class)
@Composable
internal fun RememberWindowPlacement(window: Window, state: WindowState, saver: WindowPlacementSaver) {
    LaunchedEffect(window, state, saver) {
        saver.window = window
        window.minimumSize = Dimension(WindowSizing.MIN_WIDTH.toInt(), WindowSizing.MIN_HEIGHT.toInt())
        snapshotFlow { listOf(state.placement, state.isMinimized, state.position, state.size) }
            .onEach { saver.observe(state) }
            .debounce(SAVE_AFTER_MS)
            .collect { saver.pending(state)?.let { (screen, start) -> withContext(Dispatchers.IO) { saver.store.save(screen, start) } } }
    }
}

/** "Reset window size": forgets the saved placements and puts the window where a first start would. */
internal fun resetWindow(window: Window, store: WindowPlacementStore) {
    store.forget()
    val screen = window.graphicsConfiguration?.let { runCatching { WindowScreens.of(it) }.getOrNull() } ?: return
    val start = WindowSizing.firstStart(screen.work)
    val frame = window as? Frame
    frame?.extendedState = Frame.NORMAL
    with(start.normal) { window.setBounds(x.roundToInt(), y.roundToInt(), width.roundToInt(), height.roundToInt()) }
    if (start.maximised) frame?.extendedState = Frame.MAXIMIZED_BOTH
}

private const val SAVE_AFTER_MS = 500L
