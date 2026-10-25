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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.skywing.desktop.playback.EmbeddedMpv
import java.awt.Canvas
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.FileDialog
import javax.swing.SwingUtilities
import java.awt.Frame
import java.io.File

fun main() = application {
    val state = rememberWindowState(width = 1200.dp, height = 760.dp)
    var fullscreen by remember { mutableStateOf(false) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "SkyWing for Windows (Development Preview)",
        state = state,
        undecorated = fullscreen,
    ) {
        MaterialTheme { SkyWingDesktopShell(fullscreen, { fullscreen = it; state.placement = if (it) WindowPlacement.Fullscreen else WindowPlacement.Floating }) }
    }
}

@Composable
private fun SkyWingDesktopShell(fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit) {
    val sections = listOf("Folders", "Videos", "Continue Watching", "Settings")
    var selected by remember { mutableStateOf("Videos") }
    var message by remember { mutableStateOf("Choose a local video to play.") }
    var current by remember { mutableStateOf<String?>(null) }
    val embedded = remember { EmbeddedMpv() }
    var videoCanvas by remember { mutableStateOf<Canvas?>(null) }
    var seekTarget by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var playbackTime by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    LaunchedEffect(embedded) {
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
    DisposableEffect(embedded) { onDispose { embedded.close() } }

    Row(Modifier.fillMaxSize().background(Color(0xFF101217)).onPreviewKeyEvent { e ->
        if (selected != "Videos" || e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed) false
        else when (e.key) {
            Key.Spacebar, Key.K -> { embedded.togglePause(); true }
            Key.DirectionLeft -> { embedded.seek(-5); true }
            Key.DirectionRight -> { embedded.seek(10); true }
            Key.DirectionUp -> { embedded.volumeUp(); true }
            Key.DirectionDown -> { embedded.volumeDown(); true }
            Key.M -> { embedded.toggleMute(); true }
            Key.F -> { onFullscreenChange(!fullscreen); true }
            Key.Escape -> if (fullscreen) { onFullscreenChange(false); true } else false
            else -> false
        }
    }) {
        Column(
            modifier = Modifier.width(230.dp).fillMaxHeight().background(Color(0xFF1B1E26)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SkyWing", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            sections.forEach { section ->
                Button(onClick = { selected = section }, modifier = Modifier.fillMaxWidth()) { Text(section) }
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(selected, color = Color.White, style = MaterialTheme.typography.headlineLarge)
            // Keep the AWT Canvas HWND alive across navigation. Removing SwingPanel
            // destroys the native window while libmpv still renders into it.
            // A one-dp placeholder preserves the component's lifecycle in other tabs.
            SwingPanel(
                modifier = Modifier.fillMaxWidth().height(if (selected == "Videos") 320.dp else 1.dp),
                factory = {
                    Canvas().apply {
                        background = java.awt.Color.BLACK
                        isFocusable = true
                        addKeyListener(object : KeyAdapter() {
                            override fun keyPressed(event: KeyEvent) {
                                when (event.keyCode) {
                                    KeyEvent.VK_SPACE, KeyEvent.VK_K -> embedded.togglePause()
                                    KeyEvent.VK_LEFT -> embedded.seek(-5)
                                    KeyEvent.VK_RIGHT -> embedded.seek(10)
                                    KeyEvent.VK_UP -> embedded.volumeUp()
                                    KeyEvent.VK_DOWN -> embedded.volumeDown()
                                    KeyEvent.VK_M -> embedded.toggleMute()
                                }
                            }
                        })
                        videoCanvas = this
                        SwingUtilities.invokeLater { embedded.attach(this) }
                    }
                },
            )
            if (selected == "Videos") {
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Open local video", FileDialog.LOAD)
                    dialog.isVisible = true
                    val file = dialog.file?.let { File(dialog.directory, it) }
                    dialog.dispose()
                    if (file != null) {
                        current = file.name
                        val canvas = videoCanvas
                        message = if (canvas != null && embedded.attach(canvas)) {
                            if (embedded.open(file)) "Load request accepted in ${embedded.lastLoadCommandMillis} ms (not first-frame time)" else "Embedded load failed"
                        } else embedded.lastError ?: "Video surface not ready"
                    }
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

                Text("Phase 2 preview: requires mpv.exe installed and available on PATH. Playback opens in mpv's native window.", color = Color(0xFF9FA7B7))
            } else {
                Text(
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
