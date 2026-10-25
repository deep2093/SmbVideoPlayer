package com.skywing.desktop

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.skywing.desktop.playback.EmbeddedMpv
import java.awt.Canvas
import java.awt.FileDialog
import javax.swing.SwingUtilities
import java.awt.Frame
import java.io.File

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SkyWing for Windows (Development Preview)",
        state = rememberWindowState(width = 1200.dp, height = 760.dp),
    ) {
        MaterialTheme { SkyWingDesktopShell() }
    }
}

@Composable
private fun SkyWingDesktopShell() {
    val sections = listOf("Folders", "Videos", "Continue Watching", "Settings")
    var selected by remember { mutableStateOf("Videos") }
    var message by remember { mutableStateOf("Choose a local video to play.") }
    var current by remember { mutableStateOf<String?>(null) }
    val embedded = remember { EmbeddedMpv() }
    var videoCanvas by remember { mutableStateOf<Canvas?>(null) }
    DisposableEffect(embedded) { onDispose { embedded.close() } }

    Row(Modifier.fillMaxSize().background(Color(0xFF101217))) {
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
            modifier = Modifier.fillMaxSize().padding(32.dp),
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
                            if (embedded.open(file)) "Embedded video opened" else "Embedded load failed"
                        } else embedded.lastError ?: "Video surface not ready"
                    }
                }) { Text("Open video from PC") }
                Text(current ?: "No video selected", color = Color.White)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { message = if (embedded.seekPercent(25)) "Jumped to 25%" else "Player not ready" }) { Text("25%") }
                    Button(onClick = { message = if (embedded.seekPercent(50)) "Jumped to 50%" else "Player not ready" }) { Text("50%") }
                    Button(onClick = { message = if (embedded.seekPercent(75)) "Jumped to 75%" else "Player not ready" }) { Text("75%") }
                }
                Text(message, color = Color(0xFFC6C8D1))
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
