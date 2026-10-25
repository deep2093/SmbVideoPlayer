package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.skywing.desktop.playback.EmbeddedMpv
import java.awt.Canvas
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import javax.swing.text.JTextComponent
import java.awt.event.KeyEvent
import java.awt.FileDialog
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import javax.swing.SwingUtilities
import java.awt.Frame
import java.io.File

fun main(args: Array<String>) = application {
    val state = rememberWindowState(width = 1200.dp, height = 760.dp)
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
internal fun SkyWingWindowContent(awtWindow: java.awt.Window, embedded: EmbeddedMpv, closing: Boolean, initialFile: File? = null) {
        var fullscreen by remember { mutableStateOf(false) }
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
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF8BCEFF),
                onPrimary = Color(0xFF06213A),
                primaryContainer = Color(0xFF173B58),
                onPrimaryContainer = Color(0xFFD4EAFF),
                secondary = Color(0xFF9ED7C0),
                background = Color(0xFF0B1118),
                onBackground = Color(0xFFE9EFF7),
                surface = Color(0xFF111A24),
                onSurface = Color(0xFFE9EFF7),
                surfaceVariant = Color(0xFF1A2633),
                onSurfaceVariant = Color(0xFFB7C4D2),
            ),
        ) { SkyWingDesktopShell(awtWindow, embedded, fullscreen, closing, changeFullscreen, initialFile) }
}

@Composable
private fun SkyWingDesktopShell(awtWindow: java.awt.Window, embedded: EmbeddedMpv, fullscreen: Boolean, closing: Boolean, onFullscreenChange: (Boolean) -> Unit, initialFile: File?) {
    val sections = listOf("Folders", "Videos", "Continue Watching", "Settings")
    var selected by remember { mutableStateOf("Folders") }
    var message by remember { mutableStateOf("Choose a local video to play.") }
    var current by remember { mutableStateOf<String?>(null) }
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
    var initialFilePending by remember { mutableStateOf(initialFile) }
    fun openVideo(file: File, queuedFiles: List<File> = listOf(file), resumePosition: Double? = null) {
        if (closing) return
        if (!file.isFile) {
            message = "File not available: " + file.name
            return
        }
        currentFile?.let { previous ->
            val previousPosition = embedded.playbackPosition() ?: 0.0
            val previousDuration = embedded.playbackDuration() ?: 0.0
            playbackScope.launch(Dispatchers.IO) { historyStore.saveProgress(previous, previousPosition, previousDuration) }
        }
        val safeQueue = queuedFiles.ifEmpty { listOf(file) }
        val selectedIndex = safeQueue.indexOfFirst { it.absolutePath.equals(file.absolutePath, ignoreCase = true) }
        queue = if (selectedIndex >= 0) safeQueue else listOf(file)
        queueIndex = if (selectedIndex >= 0) selectedIndex else 0
        selected = "Videos"
        current = file.name
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
            if (!dragging && duration > 0) seekTarget = (playbackTime / duration * 100.0).toFloat().coerceIn(0f, 100f)
            delay(500)
        }
    }
    fun clock(seconds: Double): String {
        val n = seconds.toLong().coerceAtLeast(0)
        return "%d:%02d:%02d".format(n / 3600, (n / 60) % 60, n % 60)
    }
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
            } else if (currentSection != "Videos") {
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


    Row(Modifier.fillMaxSize().background(Color(0xFF0B1118))) {
        if (!fullscreen) Column(
            modifier = Modifier.width(248.dp).fillMaxHeight().background(Color(0xFF101923)).padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Surface(color = Color(0xFF214D70), shape = RoundedCornerShape(14.dp)) {
                    Text("S", color = Color(0xFFD4EAFF), modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.titleLarge)
                }
                Column {
                    Text("SkyWing", color = Color(0xFFF1F6FC), style = MaterialTheme.typography.titleLarge)
                    Text("VIDEO LIBRARY", color = Color(0xFF8FA4B8), style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("YOUR LIBRARY", color = Color(0xFF8FA4B8), style = MaterialTheme.typography.labelMedium)
            sections.forEach { section ->
                NavigationDrawerItem(
                    label = { Text(section, style = MaterialTheme.typography.labelLarge) },
                    selected = selected == section,
                    onClick = { selected = section },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = Color(0xFF1B3449),
                        selectedTextColor = Color(0xFFD4EAFF),
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = Color(0xFFB7C4D2),
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Surface(color = Color(0xFF162330), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Windows preview", color = Color(0xFFD4EAFF), style = MaterialTheme.typography.labelLarge)
                    Text("Your videos stay on your devices and shares.", color = Color(0xFF9BAFC1), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Column(
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (!fullscreen) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(selected, color = Color(0xFFF1F6FC), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        when (selected) {
                            "Folders" -> "Browse videos from this PC and your network shares."
                            "Videos" -> "Play and control your current video."
                            "Continue Watching" -> "Pick up where you left off."
                            else -> "Personalise your SkyWing experience."
                        },
                        color = Color(0xFF9BAFC1),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (selected == "Folders") {
                    androidx.compose.material3.Surface(color = Color(0xFF142333), shape = RoundedCornerShape(50)) {
                        Text("LOCAL  •  NETWORK", color = Color(0xFF9ED7C0), modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            // Keep the AWT Canvas HWND alive across navigation. Removing SwingPanel
            // destroys the native window while libmpv still renders into it.
            // A one-dp placeholder preserves the component's lifecycle in other tabs.
            SwingPanel(
                modifier = if (fullscreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(if (selected == "Videos") 320.dp else 1.dp),
                factory = {
                    Canvas().apply {
                        background = java.awt.Color.BLACK
                        isFocusable = true
                        videoCanvas = this
                        SwingUtilities.invokeLater { embedded.attach(this) }
                    }
                },
            )
            if (selected == "Videos" && !fullscreen) {
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Open local video", FileDialog.LOAD)
                    dialog.isVisible = true
                    val file = dialog.file?.let { File(dialog.directory, it) }
                    dialog.dispose()
                    if (file != null) openVideo(file)
                }) { Text("Open video from PC") }
                Text(current ?: "No video selected", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = queueIndex > 0, onClick = { openQueueAt(queueIndex - 1) }) { Text("Previous") }
                    Text(if (queueIndex >= 0) "Queue " + (queueIndex + 1) + " / " + queue.size else "Queue empty", color = Color.White)
                    Button(enabled = queueIndex >= 0 && queueIndex + 1 < queue.size, onClick = { openQueueAt(queueIndex + 1) }) { Text("Next") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onFullscreenChange(!fullscreen) }) {
                        Text(if (fullscreen) "Exit fullscreen (Esc)" else "Fullscreen (F)")
                    }
                }
                Text("Playback diagnostics: $message", color = Color(0xFFE5CB87))
                Text(embedded.lastError ?: "Embedded player ready. Open a video from your PC.", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { message = if (embedded.togglePause()) "Pause toggled" else "Embedded not ready" }) { Text("Play / Pause") }
                    Button(onClick = { message = if (embedded.seek(-10)) "Seek -10" else "Embedded not ready" }) { Text("-10 sec") }
                    Button(onClick = { message = if (embedded.seek(10)) "Seek +10" else "Embedded not ready" }) { Text("+10 sec") }
                    Button(onClick = { message = if (embedded.stop()) "Stopped" else "Embedded not ready" }) { Text("Stop") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { message = if (embedded.volumeDown()) "Volume -5" else "Player not ready" }) { Text("Vol -") }
                    Button(onClick = { message = if (embedded.volumeUp()) "Volume +5" else "Player not ready" }) { Text("Vol +") }
                    Button(onClick = { message = if (embedded.toggleMute()) "Mute toggled" else "Player not ready" }) { Text("Mute") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { message = if (embedded.nextAudioTrack()) "Audio track changed" else "No audio track" }) { Text("Next audio") }
                    Button(onClick = { message = if (embedded.nextSubtitleTrack()) "Subtitle track changed" else "No subtitle track" }) { Text("Next subtitles") }
                    Button(onClick = { message = if (embedded.subtitleOff()) "Subtitles off" else "Player not ready" }) { Text("Subtitles off") }
                }
                Text("Timeline  ${clock(playbackTime)} / ${clock(duration)}", color = Color.White)
                Slider(
                    value = seekTarget,
                    onValueChange = { dragging = true; seekTarget = it },
                    valueRange = 0f..100f,
                    onValueChangeFinished = {
                        message = if (duration > 0 && embedded.seekAbsolute(duration * seekTarget / 100.0)) "Seeked to ${seekTarget.toInt()}%" else "Duration unavailable"
                        dragging = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { message = if (embedded.seekPercent(25)) "Jumped to 25%" else "Player not ready" }) { Text("25%") }
                    Button(onClick = { message = if (embedded.seekPercent(50)) "Jumped to 50%" else "Player not ready" }) { Text("50%") }
                    Button(onClick = { message = if (embedded.seekPercent(75)) "Jumped to 75%" else "Player not ready" }) { Text("75%") }
                }

                Text("Phase 2 preview: video uses the bundled embedded libmpv player.", color = Color(0xFF9FA7B7))
            } else if (!fullscreen) {
                when (selected) {
                    "Folders" -> FoldersPanel { file, files -> openVideo(file, files) }
                    "Continue Watching" -> ContinueWatchingPanel(historyRevision) { file, position ->
                        openVideo(file, listOf(file), position)
                    }
                    else -> Text("Desktop playback settings and HDR options will be added in subsequent milestones.", color = Color(0xFFC6C8D1))
                }
            }
        }
    }
}

