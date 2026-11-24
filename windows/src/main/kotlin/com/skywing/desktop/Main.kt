package com.skywing.desktop

import com.skywing.desktop.ui.theme.SkyTypography
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.skywing.desktop.ui.adaptive.Adaptive
import com.skywing.desktop.ui.adaptive.InputMode
import com.skywing.desktop.ui.adaptive.LocalAdaptive
import com.skywing.desktop.ui.adaptive.NavigationShape
import com.skywing.desktop.ui.adaptive.followInputMode
import com.skywing.desktop.ui.adaptive.rememberSettledWidth
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.skywing.desktop.playback.EmbeddedMpv
import com.skywing.desktop.playback.SmoothingStatus
import com.skywing.desktop.playback.SmoothingMode
import com.skywing.desktop.playback.SmoothingStore
import java.awt.Canvas
import java.awt.AWTEvent
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import com.skywing.desktop.artwork.ArtworkKeys
import com.skywing.desktop.artwork.ArtworkRepository
import com.skywing.desktop.artwork.LookupTarget
import com.skywing.desktop.artwork.PortraitLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // The hidden benchmark (plan 5.6) runs before the single-instance check, so it never hands its video to a
    // running SkyWing, and it opens no library or settings.
    BenchOptions.parse(args.toList())?.let { parsed ->
        exitProcess(parsed.fold(Bench::run) { Bench.reject(it.message ?: "bad bench arguments") })
    }
    val mainStarted = System.currentTimeMillis()
    // A second start hands its video to the SkyWing already open and exits (B38).
    val claim = SingleInstance.claim(SingleInstance.dataFolder(), args.toList())
    val checkMs = System.currentTimeMillis() - mainStarted
    val instance = when (claim) {
        SingleInstance.HandedOff -> return
        is SingleInstance.Owner -> claim
        is SingleInstance.Alone -> null.also { System.err.println("SkyWing runs without the single-instance check: " + claim.reason) }
    }
    PerfMonitor.startIfLogging()
    // The recorder starts after the check, so it logs what came before it: Java reaching this code, then the check.
    PerfMonitor.mark("single instance", "main_ms" to PerfMonitor.sinceStart(mainStarted), "check_ms" to checkMs, "alone" to if (instance == null) 1 else 0)
    skyWingApplication(args, instance)
}

private fun skyWingApplication(args: Array<String>, instance: SingleInstance.Owner?) = application {
    // Where the window opens and what it remembers (M3 item 8, plan 6.8).
    val placementStore = remember { WindowPlacementStore() }
    val (start, state) = remember { startWindowState(placementStore) }
    val placementSaver = remember { WindowPlacementSaver(placementStore, start) }
    var closing by remember { mutableStateOf(false) }
    val embedded = remember { EmbeddedMpv() }
    val initialFile = remember { args.firstOrNull()?.let(::File) }
    val scope = rememberCoroutineScope()
    DisposableEffect(embedded) {
        val hook = Thread({ embedded.close() }, "skywing-mpv-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        onDispose {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            // Also cover unexpected composition disposal. close is idempotent.
            Thread({ embedded.close() }, "skywing-mpv-dispose").start()
        }
    }
    Window(
        onCloseRequest = {
            if (!closing) {
                closing = true
                val placement = placementSaver.pending(state)
                scope.launch {
                    // A start from now on opens its own window rather than handing its video to this closing one.
                    try {
                        withContext(Dispatchers.IO) {
                            placement?.let { (screen, start) -> placementStore.save(screen, start) }
                            instance?.close()
                            embedded.close()
                        }
                    }
                    finally { exitApplication() }
                }
            }
        },
        title = "SkyWing for Windows (Development Preview)",
        state = state,
    ) {
        RememberWindowPlacement(window, state, placementSaver)
        SkyWingWindowContent(
            window, embedded, closing, initialFile, handoffs = instance?.handoffs,
            onFullscreenChange = { placementSaver.fullscreen = it },
            onResetWindow = { resetWindow(window, placementStore) },
        )
    }
}

@Composable
internal fun SkyWingWindowContent(
    awtWindow: java.awt.Window,
    embedded: EmbeddedMpv,
    closing: Boolean,
    initialFile: File? = null,
    initialSection: String = "Home",
    handoffs: ReceiveChannel<List<String>>? = null,
    /** Told of every change of the full-screen player, as it happens (the window placement is not saved meanwhile). */
    onFullscreenChange: (Boolean) -> Unit = {},
    /** "Reset window size" in Appearance. */
    onResetWindow: () -> Unit = {},
) {
    var fullscreen by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf(SkyThemeStore.load()) }
    val controller = remember(awtWindow) { BorderlessFullscreen(awtWindow) }
    val changeFullscreen: (Boolean) -> Unit = { enabled ->
        if (!closing) {
            // One EDT transaction; never set WindowPlacement.Fullscreen or
            // undecorated (either can dispose/recreate the native AWT peer).
            try { fullscreen = controller.set(enabled) }
            catch (failure: Exception) {
                fullscreen = controller.isFullscreen
                System.err.println("Fullscreen transition failed: ${failure.message}")
            }
            onFullscreenChange(fullscreen)
        }
    }
    // The Windows title bar follows the theme.
    LaunchedEffect(awtWindow, theme) {
        repeat(20) {
            if (awtWindow.isDisplayable) {
                SwingUtilities.invokeLater { WindowChrome.apply(awtWindow, theme) }
                return@LaunchedEffect
            }
            delay(100)
        }
    }
    // Adaptive themes take their accent from the covers on screen.
    val shownTheme = rememberShownTheme(theme)
    CompositionLocalProvider(LocalSkyTheme provides shownTheme, LocalContentColor provides shownTheme.ink) {
        MaterialTheme(colorScheme = shownTheme.colorScheme(), typography = SkyTypography) {
            SkyWingDesktopShell(
                awtWindow, embedded, fullscreen, closing, changeFullscreen, initialFile, initialSection,
                shownTheme, { chosen -> theme = chosen; SkyThemeStore.save(chosen) }, handoffs, onResetWindow,
            )
        }
    }
}

private fun chooseLocalVideo(): File? {
    val dialog = FileDialog(null as Frame?, "Open local video", FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.file?.let { File(dialog.directory, it) }
    dialog.dispose()
    return file
}

@Composable
private fun SkyWingDesktopShell(
    awtWindow: java.awt.Window,
    embedded: EmbeddedMpv,
    fullscreen: Boolean,
    closing: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    initialFile: File?,
    initialSection: String,
    theme: SkyTheme,
    onTheme: (SkyTheme) -> Unit,
    handoffs: ReceiveChannel<List<String>>?,
    onResetWindow: () -> Unit,
) {
    var selected by remember { mutableStateOf(initialSection) }
    var previous by remember { mutableStateOf("Home") }
    var message by remember { mutableStateOf("") }
    var playbackProblem by remember { mutableStateOf<PlaybackProblem?>(null) }
    var reportedFailure by remember { mutableStateOf<String?>(null) }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var queue by remember { mutableStateOf<List<File>>(emptyList()) }
    var queueIndex by remember { mutableStateOf(-1) }
    val historyStore = remember { WindowsPlaybackHistoryStore() }
    var historyRevision by remember { mutableStateOf(0) }
    val playbackScope = rememberCoroutineScope()
    var videoCanvas by remember { mutableStateOf<Canvas?>(null) }
    var seekTarget by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var playbackTime by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var paused by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var initialFilePending by remember { mutableStateOf(initialFile) }
    var smoothingMode by remember { mutableStateOf(SmoothingStore.load()) }
    var smoothingStatus by remember { mutableStateOf(SmoothingStatus()) }
    var smoothingMenu by remember { mutableStateOf(false) }
    fun selectSmoothing(mode: SmoothingMode) {
        smoothingMode = mode
        playbackScope.launch(Dispatchers.IO) { SmoothingStore.save(mode) }
    }
    // The last input used (M3 item 3). In full screen a touch leaves the hidden pointer over a bar, which must not hold it.
    val inputMode = remember { mutableStateOf(InputMode.Mouse) }
    val topInteractions = remember { MutableInteractionSource() }
    val bottomInteractions = remember { MutableInteractionSource() }
    val topHovered by topInteractions.collectIsHoveredAsState()
    val bottomHovered by bottomInteractions.collectIsHoveredAsState()
    val barHovered = inputMode.value == InputMode.Mouse && (topHovered || bottomHovered)
    val (chromeVisible, showPlayerChrome, playerVideoClick) = rememberPlayerChrome(awtWindow, fullscreen,
        paused || dragging || smoothingMenu || barHovered) { visible -> embedded.setCursorHidden(!visible) }
    val currentShowPlayerChrome by rememberUpdatedState(showPlayerChrome)
    // A click or tap on the video reaches the window of mpv, not AWT; mpv passes it on (M3 item 10).
    val currentVideoClick by rememberUpdatedState(playerVideoClick)
    DisposableEffect(embedded) {
        embedded.onVideoClick = { SwingUtilities.invokeLater { currentVideoClick() } }
        onDispose { embedded.onVideoClick = null }
    }
    val playerFullscreen by rememberUpdatedState(fullscreen)
    LaunchedEffect(embedded, smoothingMode, currentFile, closing) {
        if (closing) return@LaunchedEffect
        while (true) {
            smoothingStatus = withContext(Dispatchers.IO) { embedded.updateSmoothing(smoothingMode, playerFullscreen) }
            embedded.playbackFailure?.takeIf { it != reportedFailure }?.let { failure ->
                reportedFailure = failure
                message = failure
                playbackProblem = PlaybackProblem("Can’t play this video", failure)
            }
            if (smoothingStatus.requestOff) selectSmoothing(SmoothingMode.OFF)
            delay(1000)
        }
    }

    // What the pages show.
    val library = remember { LibraryModel(WindowsMediaIndex()) }
    LaunchedEffect(library.revision) { library.reload() }
    // Thumbnails for the whole library are made in the background, cards on screen first.
    LaunchedEffect(library.all) {
        if (library.revision > 0) ThumbnailStore.prefetch(library.all.map { it.file to it.modified })
    }
    // While a video plays, thumbnails wait so they don't take the processor or disk from it (B24).
    LaunchedEffect(currentFile != null) { ThumbnailStore.playing = currentFile != null }
    val organizer = remember { Organizer(FolderOrganizerStore()) { WindowsCustomFolderStore().load() } }
    // Once folders.bin holds the custom folders, their old registry copy goes (B20). It can hold one value per video,
    // so it is removed off the screen thread.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { if (FolderOrganizerStore().exists()) WindowsCustomFolderStore().clear() } }
    }
    // Covers and portraits: one store for the app, and the background portrait lookup after each scan.
    val artwork = remember { ArtworkRepository() }
    val videoRenamer = remember(library, organizer, artwork, historyStore) { VideoRenameService(library, organizer, artwork, historyStore) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    // Always check for library changes at startup; the optional periodic scan runs while the app is open.
    val sync = remember { LibrarySync(library) }
    LaunchedEffect(sync) {
        sync.refresh()
        while (true) {
            if (sync.due()) sync.refresh()
            delay(15 * 60 * 1000L)
        }
    }
    val portraits = remember { PortraitLookup(artwork) }
    val covers = remember(artwork, organizer.data.prefs.virtualRules) { CoverContext(artwork, organizer.data.prefs.virtualRules) }
    LaunchedEffect(library.all) {
        if (library.revision > 0 && library.all.isNotEmpty()) portraits.start(forced = false) { lookupTargets(library.all, organizer.data) }
    }
    // Saved network shares: learn their other addresses, and find the one that answers if the main one is down.
    LaunchedEffect(Unit) {
        val switched = withContext(Dispatchers.IO) {
            runCatching { WindowsShareProfileStore().load() }.getOrDefault(emptyList()).count { profile ->
                ShareHosts.register(profile.primaryAddress, profile.alternateAddress)
                val host = ShareHosts.hostOf(profile.primaryAddress) ?: return@count false
                ShareHosts.parse(profile.alternateAddress).isNotEmpty() && ShareHosts.resolve(host)?.equals(host, ignoreCase = true) == false
            }
        }
        if (switched > 0) library.changed()
    }
    val hub = remember { HubState() }
    val selection = remember { SelectionState() }
    val filesBack = remember { mutableStateOf<(() -> Boolean)?>(null) }
    // What Back does inside Settings (one pane: back to the list); Settings chosen again does the same (B1).
    val settingsBack = remember { mutableStateOf<(() -> Boolean)?>(null) }
    // Read off the screen thread (B8). The pages keep the last history until the new one is read.
    val history by produceState(emptyMap<String, WindowsWatchRecord>(), historyRevision) {
        value = withContext(Dispatchers.IO) { historyStore.load().associateBy { it.path.lowercase() } }
    }

    fun go(section: String) {
        if (section == selected) {
            if (section == SETTINGS) settingsBack.value?.invoke()
            return
        }
        // Leaving the player pauses the video; Now playing in the navigation brings it back.
        if (selected == "Now Playing") {
            embedded.pause()
            if (fullscreen) onFullscreenChange(false)
        } else previous = selected
        selected = section
        selection.clear()
        hub.endFolderSelection()
    }

    val mouseBack by rememberUpdatedState<() -> Unit>({
        when {
            selected == "Folders" && hub.navigateBack(organizer.data) -> Unit
            selected == "Files" && filesBack.value?.invoke() == true -> Unit
            selected == SETTINGS && settingsBack.value?.invoke() == true -> Unit
            selected == "Now Playing" -> go(previous)
            selected != previous -> go(previous)
        }
    })
    // Windows side mouse buttons arrive as AWT button 4/5, including over the native video canvas.
    DisposableEffect(awtWindow) {
        val listener = AWTEventListener { event ->
            val mouse = event as? MouseEvent ?: return@AWTEventListener
            if (mouse.id == MouseEvent.MOUSE_PRESSED && mouse.button == 4 &&
                SwingUtilities.getWindowAncestor(mouse.component) === awtWindow
            ) {
                mouseBack()
                mouse.consume()
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }

    fun openVideo(file: File, queuedFiles: List<File> = listOf(file), resumePosition: Double? = null) {
        if (closing) return
        if (!file.isFile) {
            message = "File not available: " + file.name
            playbackProblem = PlaybackProblem("Video not available", file.name + " can’t be found. It may have been moved " +
                "or renamed, or the drive or network share it is on is offline.")
            return
        }
        currentFile?.let { earlier ->
            val previousPosition = embedded.playbackPosition() ?: 0.0
            val previousDuration = embedded.playbackDuration() ?: 0.0
            playbackScope.launch(Dispatchers.IO) { historyStore.saveProgress(earlier, previousPosition, previousDuration) }
        }
        val safeQueue = queuedFiles.ifEmpty { listOf(file) }
        val selectedIndex = safeQueue.indexOfFirst { it.absolutePath.equals(file.absolutePath, ignoreCase = true) }
        queue = if (selectedIndex >= 0) safeQueue else listOf(file)
        queueIndex = if (selectedIndex >= 0) selectedIndex else 0
        if (selected != "Now Playing") previous = selected
        selected = "Now Playing"
        selection.clear()
        currentFile = file
        reportedFailure = null
        val canvas = videoCanvas
        val accepted = canvas != null && embedded.attach(canvas) && embedded.open(file)
        message = if (accepted) {
            if (embedded.lastLoadCommandMillis >= 0) "Load request accepted in " + embedded.lastLoadCommandMillis + " ms (not first-frame time)" else "Load request accepted"
        } else embedded.lastError ?: "Embedded load failed"
        if (accepted && resumePosition != null && resumePosition > 0) {
            playbackScope.launch {
                delay(1200)
                if (currentFile?.absolutePath.equals(file.absolutePath, ignoreCase = true)) {
                    embedded.seekAbsolute(resumePosition)
                }
            }
        }
    }
    /** Stops the video, keeps where it was for Continue watching, and goes back to the page it was played from. */
    fun stopPlayback() {
        val file = currentFile ?: return
        val position = embedded.playbackPosition() ?: 0.0
        val length = embedded.playbackDuration() ?: 0.0
        embedded.stop()
        currentFile = null
        queue = emptyList()
        queueIndex = -1
        message = ""
        playbackScope.launch(Dispatchers.IO) {
            runCatching { historyStore.saveProgress(file, position, length) }
            historyRevision++
        }
        if (fullscreen) onFullscreenChange(false)
        if (selected == "Now Playing") selected = previous
    }
    fun openQueueAt(index: Int) {
        val files = queue
        files.getOrNull(index)?.let { openVideo(it, files) }
    }
    val latestOpenVideo by rememberUpdatedState<(File) -> Unit>({ file -> openVideo(file) })
    // Compose puts its own drop target on the window content, so drops there come through Compose (B37). The video
    // Canvas is a native window of its own that Compose doesn't reach, so it keeps an AWT drop target.
    @OptIn(ExperimentalComposeUiApi::class)
    val videoDrop = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val file = droppedVideo(event.awtTransferable) ?: return false
                latestOpenVideo(file)
                return true
            }
        }
    }
    DisposableEffect(videoCanvas) {
        val canvas = videoCanvas ?: return@DisposableEffect onDispose { }
        val listener = LocalVideoDropHandler { file -> latestOpenVideo(file) }
        val oldCanvasTarget = canvas.dropTarget
        val canvasTarget = DropTarget(canvas, DnDConstants.ACTION_COPY, listener, true)
        onDispose {
            canvas.dropTarget = oldCanvasTarget
            canvasTarget.removeDropTargetListener(listener)
        }
    }
    LaunchedEffect(currentFile, closing) {
        val file = currentFile ?: return@LaunchedEffect
        suspend fun savePosition() {
            val reading = withContext(Dispatchers.IO) { embedded.reading() }
            val position = reading.position ?: return
            val length = reading.duration ?: return
            runCatching { withContext(Dispatchers.IO) { historyStore.saveProgress(file, position, length) } }
                .onSuccess { historyRevision++ }
        }
        if (closing) {
            savePosition()
            return@LaunchedEffect
        }
        // While it plays, the place is written after a pause or a seek and at most every 30 s (B20). Stopping, closing
        // and the next video write it straight away.
        val saves = HistorySaves(startedAt = System.nanoTime() / 1_000_000)
        while (true) {
            delay(5000)
            // Read off the screen thread: the player may be busy, for example starting motion smoothing (B7).
            val reading = withContext(Dispatchers.IO) { embedded.reading() }
            val position = reading.position ?: continue
            val length = reading.duration ?: continue
            val now = System.nanoTime() / 1_000_000
            if (!saves.due(now, position, paused = reading.paused == true)) continue
            runCatching { withContext(Dispatchers.IO) { historyStore.saveProgress(file, position, length) } }
                .onSuccess {
                    saves.saved(now, position)
                    historyRevision++
                }
        }
    }

    // Another start of SkyWing handed this window its video (B38): come forward and play it the way a start file plays.
    LaunchedEffect(handoffs) {
        for (paths in handoffs ?: return@LaunchedEffect) {
            SingleInstance.bringToFront(awtWindow)
            paths.firstOrNull()?.let { initialFilePending = File(it) }
        }
    }
    LaunchedEffect(videoCanvas, initialFilePending) {
        val file = initialFilePending ?: return@LaunchedEffect
        val canvas = videoCanvas ?: return@LaunchedEffect
        repeat(100) {
            if (canvas.isDisplayable) {
                initialFilePending = null
                openVideo(file)
                return@LaunchedEffect
            }
            delay(50)
        }
        initialFilePending = null
        message = "Video surface not ready"
    }
    LaunchedEffect(embedded, closing) {
        if (closing) return@LaunchedEffect
        while (true) {
            val reading = withContext(Dispatchers.IO) { embedded.reading() }
            duration = (reading.duration ?: 0.0).coerceAtLeast(0.0)
            playbackTime = (reading.position ?: 0.0).coerceAtLeast(0.0)
            paused = reading.paused ?: true
            muted = reading.muted ?: false
            if (!dragging && duration > 0) seekTarget = (playbackTime / duration * 100.0).toFloat().coerceIn(0f, 100f)
            delay(500)
        }
    }
    val latestStop by rememberUpdatedState<() -> Unit>({ stopPlayback() })
    val currentSection by rememberUpdatedState(selected)
    val currentFullscreen by rememberUpdatedState(fullscreen)
    val currentFullscreenChange by rememberUpdatedState(onFullscreenChange)
    val currentClosing by rememberUpdatedState(closing)
    // AWT dispatch works regardless of whether Compose controls or Canvas own focus.
    DisposableEffect(awtWindow, embedded) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        var fullscreenKeyDown = false
        val focusListener = java.beans.PropertyChangeListener { fullscreenKeyDown = false }
        manager.addPropertyChangeListener("focusedWindow", focusListener)
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id == KeyEvent.KEY_PRESSED && manager.focusedWindow === awtWindow) currentShowPlayerChrome()
            if (event.keyCode == KeyEvent.VK_F && event.id == KeyEvent.KEY_RELEASED) fullscreenKeyDown = false
            val handled = if (event.id != KeyEvent.KEY_PRESSED || manager.focusedWindow !== awtWindow || currentClosing) {
                false
            } else if (currentFullscreen && event.keyCode == KeyEvent.VK_ESCAPE) {
                // Escape must work even when a text field or a slider owns focus.
                currentFullscreenChange(false)
                true
            } else if (event.keyCode == KeyEvent.VK_ESCAPE && currentSection == SETTINGS && settingsBack.value?.invoke() == true) {
                // Esc leaves a Settings page for the list when the list is hidden (M3 design brief 5.6).
                true
            } else if (event.keyCode == KeyEvent.VK_F12 && event.isControlDown && event.isShiftDown && !event.isAltDown) {
                PerfMonitor.toggleOverlay()
                true
            } else if (
                event.isControlDown || event.isAltDown || event.isMetaDown ||
                manager.focusOwner is JTextComponent
            ) {
                false
            } else if (currentSection != "Now Playing") {
                false
            } else {
                when (event.keyCode) {
                    KeyEvent.VK_F -> {
                        if (!fullscreenKeyDown) {
                            fullscreenKeyDown = true
                            currentFullscreenChange(!currentFullscreen)
                        }
                        true
                    }
                    KeyEvent.VK_SPACE, KeyEvent.VK_K -> { embedded.togglePause(); true }
                    KeyEvent.VK_LEFT -> { embedded.seek(-5); true }
                    KeyEvent.VK_RIGHT -> { embedded.seek(10); true }
                    KeyEvent.VK_UP -> { embedded.volumeUp(); true }
                    KeyEvent.VK_DOWN -> { embedded.volumeDown(); true }
                    KeyEvent.VK_M -> { embedded.toggleMute(); true }
                    KeyEvent.VK_S -> { latestStop(); true }
                    else -> false
                }
            }
            if (handled) event.consume()
            handled
        }
        manager.addKeyEventDispatcher(dispatcher)
        onDispose {
            manager.removeKeyEventDispatcher(dispatcher)
            manager.removePropertyChangeListener("focusedWindow", focusListener)
        }
    }

    // A video on a network drive whose address stopped answering is tried again through the drive's other addresses.
    val playOne: (File, List<File>) -> Unit = { file, files ->
        if (ShareHosts.hostOf(file.path) == null) {
            openVideo(file, files)
        } else {
            playbackScope.launch {
                val again = withContext(Dispatchers.IO) {
                    if (file.isFile) false
                    else ShareHosts.hostOf(ShareHosts.canonical(file.path))?.let { ShareHosts.resolve(it) } != null
                }
                fun reach(f: File) = if (again) File(ShareHosts.reachable(ShareHosts.canonical(f.path))) else f
                openVideo(reach(file), files.map(::reach))
            }
        }
    }
    val playerShown = selected == NOW_PLAYING
    val nowPlaying = currentFile?.let { NowPlaying(it.nameWithoutExtension, paused, if (duration > 0) (playbackTime / duration).toFloat() else 0f) }
    // One answer for every screen (M3 item 3): the window's width, read again once a resize settles, and the last input.
    val density = LocalDensity.current
    @OptIn(ExperimentalComposeUiApi::class)
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val adaptive = Adaptive(rememberSettledWidth(windowWidth), inputMode.value)

    @OptIn(ExperimentalComposeUiApi::class)
    val takesDrop: (DragAndDropEvent) -> Boolean = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }
    CompositionLocalProvider(LocalAdaptive provides adaptive) {
        Box(Modifier.fillMaxSize().skyWallpaper(theme).followInputMode(inputMode).dragAndDropTarget(shouldStartDragAndDrop = takesDrop, target = videoDrop)) {
            Row(Modifier.fillMaxSize()) {
                // The rail by width (M3 design brief 5.2); the video's SwingPanel below keeps its one place in the tree.
                if (!fullscreen && adaptive.navigation != NavigationShape.TopTabs) {
                    NavRail(
                        wide = adaptive.navigation == NavigationShape.WideRail,
                        selected = selected,
                        nowPlaying = nowPlaying,
                        onSelect = { go(it) },
                        onTogglePause = { embedded.togglePause() },
                    )
                }
                Column(
                    modifier = when {
                        fullscreen -> Modifier.weight(1f).fillMaxHeight()
                        playerShown -> Modifier.weight(1f).fillMaxHeight().padding(12.dp)
                        else -> Modifier.weight(1f).fillMaxHeight()
                            .padding(start = adaptive.pagePadding, end = adaptive.pagePadding, top = 16.dp, bottom = 12.dp)
                    },
                ) {
                    if (!fullscreen && adaptive.navigation == NavigationShape.TopTabs) {
                        TopTabs(selected, nowPlaying, { go(it) }, Modifier.padding(bottom = 8.dp))
                    }
                    if (!fullscreen && !playerShown) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            CompositionLocalProvider(LocalCovers provides covers) { when (selected) {
                                "Home" -> HomeScreen(library, hub, history, playOne, { file, position -> openVideo(file, listOf(file), position) },
                                    { go("Videos") }) { go("Files") }
                                "Folders" -> FoldersScreen(library, organizer, hub, history, playOne, { renameTarget = it })
                                "Videos" -> VideosScreen(library, organizer, hub, selection, history, playOne, { file, position -> openVideo(file, listOf(file), position) }, { renameTarget = it }) { go("Files") }
                                "Files" -> FilesScreen(library, playOne, { renameTarget = it }) { filesBack.value = it }
                                else -> SettingsScreen(theme, onTheme, library, organizer, portraits, sync,
                                    smoothingMode, smoothingStatus, ::selectSmoothing, { settingsBack.value = it }, onResetWindow) { go("Files") }
                            } }
                        }
                    }
                    FullscreenChrome(awtWindow, fullscreen, playerShown && chromeVisible, bottom = false) {
                        PlayerTopBar(
                            title = currentFile?.nameWithoutExtension ?: "Nothing playing",
                            queueLabel = if (queueIndex >= 0 && queue.size > 1) "Queue " + (queueIndex + 1) + " / " + queue.size else null,
                            onBack = { if (fullscreen) onFullscreenChange(false); go(previous) },
                            modifier = Modifier.hoverable(topInteractions),
                        )
                    }
                    // Keep the AWT Canvas HWND alive across navigation. Removing SwingPanel
                    // destroys the native window while libmpv still renders into it.
                    // A one-dp placeholder preserves the component's lifecycle in other pages.
                    SwingPanel(
                        modifier = if (fullscreen || playerShown) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(1.dp),
                        factory = {
                            Canvas().apply {
                                background = java.awt.Color.BLACK
                                isFocusable = true
                                videoCanvas = this
                                SwingUtilities.invokeLater { embedded.attach(this) }
                            }
                        },
                    )
                    FullscreenChrome(awtWindow, fullscreen, playerShown && chromeVisible, bottom = true) {
                        PlayerControls(
                            position = playbackTime,
                            duration = duration,
                            seekFraction = seekTarget,
                            paused = paused,
                            muted = muted,
                            canPrevious = queueIndex > 0,
                            canNext = queueIndex >= 0 && queueIndex + 1 < queue.size,
                            status = listOf(smoothingStatus.detail, message).filter { it.isNotBlank() }.joinToString(" · "),
                            smoothingMode = smoothingMode,
                            smoothingActive = smoothingStatus.motionActive,
                            onSmoothingToggle = { selectSmoothing(if (smoothingMode == SmoothingMode.OFF) SmoothingMode.MOTION else SmoothingMode.OFF) },
                            onSmoothingOptions = { smoothingMenu = true },
                            onSeekChange = { dragging = true; seekTarget = it },
                            onSeekFinished = {
                                message = if (duration > 0 && embedded.seekAbsolute(duration * seekTarget / 100.0)) "Seeked to ${seekTarget.toInt()}%" else "Duration unavailable"
                                dragging = false
                            },
                            onTogglePause = { message = if (embedded.togglePause()) "" else "Embedded not ready" },
                            onStop = { stopPlayback() },
                            onBack10 = { embedded.seek(-10) },
                            onForward10 = { embedded.seek(10) },
                            onPrevious = { openQueueAt(queueIndex - 1) },
                            onNext = { openQueueAt(queueIndex + 1) },
                            onAudio = { message = if (embedded.nextAudioTrack()) "Audio track changed" else "No audio track" },
                            onSubtitles = { message = if (embedded.nextSubtitleTrack()) "Subtitle track changed" else "No subtitle track" },
                            onSubtitlesOff = { message = if (embedded.subtitleOff()) "Subtitles off" else "Player not ready" },
                            onVolumeDown = { message = if (embedded.volumeDown()) "Volume -5" else "Player not ready" },
                            onVolumeUp = { message = if (embedded.volumeUp()) "Volume +5" else "Player not ready" },
                            onMute = { embedded.toggleMute() },
                            onFullscreen = { onFullscreenChange(!fullscreen) },
                            onOpenFile = { chooseLocalVideo()?.let { openVideo(it) } },
                            modifier = Modifier.hoverable(bottomInteractions),
                        )
                    }
                }
            }
            PerfOverlay(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 20.dp))
        }
        // The dialogs follow the same input mode: bigger targets after a touch.
        playbackProblem?.let { PlaybackProblemDialog(it) { playbackProblem = null } }
        if (smoothingMenu) SmoothingDialog(smoothingMode, smoothingStatus, ::selectSmoothing) { smoothingMenu = false }
        renameTarget?.let { file ->
            RenameVideoDialog(file, videoRenamer::rename) { renameTarget = null; historyRevision++ }
        }
    }
    PerfFrameProbe()
}

/**
 * What a portrait lookup may cover: filename collections, every name found in the video names (which also gives the
 * videos and the virtual subfolders of that name their portrait), and the drive folders holding videos.
 */
internal fun lookupTargets(all: List<WindowsMediaEntry>, data: OrganizerData): List<LookupTarget> {
    val custom = data.own.map { LookupTarget(ArtworkKeys.ownFolder(it.id), it.name) }
    val matches = data.match.filter { !it.hidden }.map { LookupTarget(ArtworkKeys.matchFolder(it.id), it.name) }
    val virtualSets = data.sets.map { LookupTarget(ArtworkKeys.virtualSet(it.id), it.name) }
    val names = FolderLogic.groupByNames(all, data.prefs.virtualRules, data.assignments).keys
        .map { LookupTarget(ArtworkKeys.virtualFolder(it), FolderLogic.groupTitle(it)) }
    val drives = all.mapNotNull { it.file.parentFile }.distinctBy { it.path.lowercase() }
        .map { LookupTarget(driveCoverKey(it.path), FolderLogic.originalLabel(it.name)) }
    return custom + matches + virtualSets + names + drives
}
