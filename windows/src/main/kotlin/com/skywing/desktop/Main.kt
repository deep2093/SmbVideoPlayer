package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

// Foundation UI only. No video engine, SMB or HDR capability is claimed by this preview.
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SkyWing for Windows (Foundation Preview)",
        state = rememberWindowState(width = 1200.dp, height = 760.dp),
    ) {
        MaterialTheme {
            SkyWingDesktopShell()
        }
    }
}

@Composable
private fun SkyWingDesktopShell() {
    val sections = listOf("Folders", "Videos", "Continue Watching", "Settings")
    var selected by remember { mutableStateOf(sections.first()) }
    Row(Modifier.fillMaxSize().background(Color(0xFF101217))) {
        Column(
            modifier = Modifier.width(230.dp).fillMaxHeight()
                .background(Color(0xFF1B1E26)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SkyWing", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            sections.forEach { section ->
                Button(onClick = { selected = section }, modifier = Modifier.fillMaxWidth()) {
                    Text(section)
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(selected, color = Color.White, style = MaterialTheme.typography.headlineLarge)
            Text(
                when (selected) {
                    "Folders" -> "Windows SMB and virtual folders are scheduled for milestones W2-W3."
                    "Videos" -> "Local playback and the native libmpv engine are scheduled for W1."
                    "Continue Watching" -> "Watch history is scheduled for W3."
                    else -> "HDR10, HDR10+, HLG and Dolby Vision compatibility arrive in W4-W5."
                },
                color = Color(0xFFC6C8D1),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("Foundation preview only. No playback is connected yet.", color = Color(0xFF9FA7B7))
        }
    }
}
