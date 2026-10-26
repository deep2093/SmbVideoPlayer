package com.skywing.desktop

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.skywing.desktop.playback.EmbeddedMpv
import java.awt.Canvas
import java.awt.AWTEvent
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main(args: Array<String>) = application {
    val state = rememberWindowState(width = 1280.dp, height = 800.dp)
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
                scope.launch {
                    try { withContext(Dispatchers.IO) { embedded.close() } }
                    finally { exitApplication() }
                }
            }
        },
        title = "SkyWing for Windows (Development Preview)",
        state = state,
    ) {
        SkyWingWindowContent(window, embedded, closing, initialFile)
    }
}

@Composable
internal fun SkyWingWindowContent(awtWindow: java.awt.Window, embedded: EmbeddedMpv, closing: Boolean, initialFile: File? = null, initialSection: String = "Folders") {
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
        MaterialTheme(colorScheme = shownTheme.colorScheme()) {
            SkyWingDesktopShell(
                awtWindow, embedded, fullscreen, closing, changeFullscreen, initialFile, initialSection,
                shownTheme, { chosen -> theme = chosen; SkyThemeStore.save(chosen) },
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

private class NavEntry(val section: String, val icon: SkyIcon)

private val TOP_NAV = listOf(
    NavEntry("Folders", SkyIcon.Folder),
    NavEntry("Videos", SkyIcon.VideoLibrary),
    NavEntry("Files", SkyIcon.Cloud),
)

@Composable
private fun NavRow(label: String, icon: SkyIcon, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalSkyTheme.current
    val ink = if (selected) theme.ink else theme.muted
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(50))
            .background(if (selected) theme.card else Color.Transparent)
            .skyClickable(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkyIconView(icon, if (selected) theme.accent else ink)
        Text(label, color = ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

/** The sidebar, like the Android tablet sidebar: mark and name, the pages, Settings at the bottom. */
@Composable
private fun Sidebar(
    selected: String,
    nowPlaying: String?,
    paused: Boolean,
    onSelect: (String) -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalSkyTheme.current
    Column(modifier.skyGlass(RoundedCornerShape(28.dp)).padding(16.dp)) {
        SkyMark(40.dp, Modifier.padding(top = 12.dp, bottom = 12.dp))
        Text("SkyWing", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text("Video library", color = theme.muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(28.dp))
        TOP_NAV.forEach { entry ->
            NavRow(entry.section, entry.icon, selected == entry.section) { onSelect(entry.section) }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.weight(1f))
        if (nowPlaying != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .skyGlass(RoundedCornerShape(20.dp), tint = theme.card)
                    .skyClickable({ onSelect("Now Playing") })
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Now playing", color = if (selected == "Now Playing") theme.accent else theme.muted, style = MaterialTheme.typography.labelSmall)
                    Text(nowPlaying, color = theme.ink, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SkyIconButton(if (paused) SkyIcon.Play else SkyIcon.Pause, if (paused) "Play" else "Pause", onTogglePause, box = 36.dp, glyph = 20.dp)
            }
        }
        NavRow("Settings", SkyIcon.Settings, selected == "Settings") { onSelect("Settings") }
    }
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
) {
    var selected by remember { mutableStateOf(initialSection) }
    var previous by remember { mutableStateOf("Folders") }
    var message by remember { mutableStateOf("") }
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

    // What the pages show.
    val library = remember { LibraryModel(WindowsMediaIndex()) }
    LaunchedEffect(library.revision) { library.reload() }
    // Thumbnails for the whole library are made in the background, cards on screen first.
    LaunchedEffect(library.all) {
        if (library.revision > 0) ThumbnailStore.prefetch(library.all.map { it.file to it.modified })
    }
    val organizer = remember { Organizer(FolderOrganizerStore()) { WindowsCustomFolderStore().load() } }
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
    val history = remember(historyRevision) { historyStore.load().associateBy { it.path.lowercase() } }

    fun go(section: String) {
        if (section == selected) return
        // Leaving the player pauses the video; Now playing in the sidebar brings it back.
        if (selected == "Now Playing") embedded.pause() else previous = selected
        selected = section
        selection.clear()
        hub.endFolderSelection()
    }

    val mouseBack by rememberUpdatedState<() -> Unit>({
        when {
            selected == "Folders" && hub.navigateBack(organizer.data) -> Unit
            selected == "Files" && filesBack.value?.invoke() == true -> Unit
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
    // The Canvas is a heavyweight child; both it and the frame need drop targets.
    DisposableEffect(awtWindow, videoCanvas) {
        val listener = LocalVideoDropHandler { file -> latestOpenVideo(file) }
        val oldWindowTarget = awtWindow.dropTarget
        val target = DropTarget(awtWindow, DnDConstants.ACTION_COPY, listener, true)
        val canvas = videoCanvas
        val oldCanvasTarget = canvas?.dropTarget
        val canvasTarget = canvas?.let { DropTarget(it, DnDConstants.ACTION_COPY, listener, true) }
        onDispose {
            awtWindow.dropTarget = oldWindowTarget
            canvas?.dropTarget = oldCanvasTarget
            target.removeDropTargetListener(listener)
            canvasTarget?.removeDropTargetListener(listener)
        }
    }
    LaunchedEffect(currentFile, closing) {
        val file = currentFile ?: return@LaunchedEffect
        suspend fun savePosition() {
            val position = embedded.playbackPosition() ?: return
            val length = embedded.playbackDuration() ?: return
            runCatching { withContext(Dispatchers.IO) { historyStore.saveProgress(file, position, length) } }
                .onSuccess { historyRevision++ }
        }
        if (closing) {
            savePosition()
            return@LaunchedEffect
        }
        while (true) {
            delay(5000)
            savePosition()
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
            val d = embedded.playbackDuration() ?: 0.0
            val p = embedded.playbackPosition() ?: 0.0
            duration = d.coerceAtLeast(0.0)
            playbackTime = p.coerceAtLeast(0.0)
            paused = embedded.isPaused() ?: true
            muted = embedded.isMuted() ?: false
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
            if (event.keyCode == KeyEvent.VK_F && event.id == KeyEvent.KEY_RELEASED) fullscreenKeyDown = false
            val handled = if (event.id != KeyEvent.KEY_PRESSED || manager.focusedWindow !== awtWindow || currentClosing) {
                false
            } else if (currentFullscreen && event.keyCode == KeyEvent.VK_ESCAPE) {
                // Escape must work even when a text field or a slider owns focus.
                currentFullscreenChange(false)
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
    val playerShown = selected == "Now Playing"

    Box(Modifier.fillMaxSize().skyWallpaper(theme)) {
        Row(Modifier.fillMaxSize()) {
            if (!fullscreen) {
                Sidebar(
                    selected = selected,
                    nowPlaying = currentFile?.nameWithoutExtension,
                    paused = paused,
                    onSelect = { go(it) },
                    onTogglePause = { embedded.togglePause() },
                    modifier = Modifier.width(240.dp).fillMaxHeight().padding(12.dp),
                )
            }
            Column(
                modifier = if (fullscreen) Modifier.weight(1f).fillMaxHeight()
                else Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
            ) {
                if (!fullscreen && !playerShown) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CompositionLocalProvider(LocalCovers provides covers) { when (selected) {
                            "Folders" -> FoldersScreen(library, organizer, hub, history, playOne, { renameTarget = it })
                            "Videos" -> VideosScreen(library, organizer, hub, selection, history, playOne, { file, position -> openVideo(file, listOf(file), position) }, { renameTarget = it })
                            "Files" -> FilesScreen(library, playOne, { renameTarget = it }) { filesBack.value = it }
                            else -> SettingsScreen(theme, onTheme, library, organizer, portraits, sync) { go("Files") }
                        } }
                    }
                }
                if (playerShown && !fullscreen) {
                    PlayerTopBar(
                        title = currentFile?.nameWithoutExtension ?: "Nothing playing",
                        queueLabel = if (queueIndex >= 0 && queue.size > 1) "Queue " + (queueIndex + 1) + " / " + queue.size else null,
                        onBack = { go(previous) },
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
                if (playerShown && !fullscreen) {
                    PlayerControls(
                        position = playbackTime,
                        duration = duration,
                        seekFraction = seekTarget,
                        paused = paused,
                        muted = muted,
                        canPrevious = queueIndex > 0,
                        canNext = queueIndex >= 0 && queueIndex + 1 < queue.size,
                        status = message,
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
                        onFullscreen = { onFullscreenChange(true) },
                        onOpenFile = { chooseLocalVideo()?.let { openVideo(it) } },
                    )
                }
            }
        }
    }
    renameTarget?.let { file ->
        RenameVideoDialog(file, videoRenamer::rename) { renameTarget = null; historyRevision++ }
    }
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
