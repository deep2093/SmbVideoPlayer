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
import com.skywing.desktop.playback.LocalPlayback
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
    val playback = remember { LocalPlayback() }
    val embedded = remember { EmbeddedMpv() }
    var experimental by remember { mutableStateOf(true) }
    var videoCanvas by remember { mutableStateOf<Canvas?>(null) }
    DisposableEffect(playback, embedded) { onDispose { playback.close(); embedded.close() } }

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
            if (selected == "Videos") {
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Open local video", FileDialog.LOAD)
                    dialog.isVisible = true
                    val file = dialog.file?.let { File(dialog.directory, it) }
                    dialog.dispose()
                    if (file != null) {
                        current = file.name
                        message = if (experimental) {
                            val canvas = videoCanvas
                            if (canvas != null && embedded.attach(canvas)) {
                                if (embedded.open(file)) "Embedded video opened" else "Embedded load failed"
                            } else embedded.lastError ?: "Video surface not ready"
                        } else playback.play(file)
                    }
                }) { Text("Open video from PC") }
                Text(current ?: "No video selected", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { embedded.stop(); experimental = false }) { Text("External (legacy)") }
                    Button(onClick = { experimental = true; playback.stop() }) { Text("Embedded (default)") }
                }
                if (experimental) {
                    SwingPanel(
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        factory = {
                            Canvas().apply {
                                background = java.awt.Color.BLACK
                                videoCanvas = this
                                SwingUtilities.invokeLater { embedded.attach(this) }
                            }
                        },
                    )
                    Text(embedded.lastError ?: "Embedded player ready. Open a video from your PC.", color = Color.White)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { message = if (experimental) if (embedded.togglePause()) "Pause toggled" else "Embedded not ready" else playback.togglePause() }) { Text("Play / Pause") }
                    Button(onClick = { message = if (experimental) if (embedded.seek(-10)) "Seek -10" else "Embedded not ready" else playback.seek(-10) }) { Text("-10 sec") }
                    Button(onClick = { message = if (experimental) if (embedded.seek(10)) "Seek +10" else "Embedded not ready" else playback.seek(10) }) { Text("+10 sec") }
                    Button(onClick = { message = if (experimental) if (embedded.stop()) "Stopped" else "Embedded not ready" else playback.stop() }) { Text("Stop") }
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
