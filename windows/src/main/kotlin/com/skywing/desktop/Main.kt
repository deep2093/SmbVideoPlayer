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
        MaterialTheme { SkyWingDesktopShell(awtWindow, embedded, fullscreen, closing, changeFullscreen, initialFile) }
}

@Composable
private fun SkyWingDesktopShell(awtWindow: java.awt.Window, embedded: EmbeddedMpv, fullscreen: Boolean, closing: Boolean, onFullscreenChange: (Boolean) -> Unit, initialFile: File?) {
    val sections = listOf("Folders", "Videos", "Continue Watching", "Settings")
    var selected by remember { mutableStateOf("Videos") }
    var message by remember { mutableStateOf("Choose a local video to play.") }
    var current by remember { mutableStateOf<String?>(null) }
    var videoCanvas by remember { mutableStateOf<Canvas?>(null) }
    var seekTarget by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var playbackTime by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var initialFilePending by remember { mutableStateOf(initialFile) }
    fun openVideo(file: File) {
        if (closing) return
        if (!file.isFile) {
            message = "File not available: ${file.name}"
            return
        }
        selected = "Videos"
        current = file.name
        val canvas = videoCanvas
        message = if (canvas != null && embedded.attach(canvas)) {
            if (embedded.open(file)) "Load request accepted in ${embedded.lastLoadCommandMillis} ms (not first-frame time)"
            else "Embedded load failed"
        } else embedded.lastError ?: "Video surface not ready"
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


    Row(Modifier.fillMaxSize().background(Color(0xFF101217))) {
        if (!fullscreen) Column(
            modifier = Modifier.width(230.dp).fillMaxHeight().background(Color(0xFF1B1E26)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SkyWing", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            sections.forEach { section ->
                Button(onClick = { selected = section }, modifier = Modifier.fillMaxWidth()) { Text(section) }
            }
        }
        Column(
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!fullscreen) Text(selected, color = Color.White, style = MaterialTheme.typography.headlineLarge)
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
                if (selected == "Folders") FoldersPanel { file -> openVideo(file) }
                else Text(
                    when (selected) {
                        "Folders" -> "PC folder indexing and SMB sources are planned for Phase 3."
                        "Continue Watching" -> "History and resume are planned for Phase 3."
                        else -> "Desktop playback settings and HDR options will be added in subsequent milestones."
                    },
                    color = Color(0xFFC6C8D1),
                )
            }
        }
    }
}
