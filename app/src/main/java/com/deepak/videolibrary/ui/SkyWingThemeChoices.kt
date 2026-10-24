package com.deepak.videolibrary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Real palette samples, with one accessible selection target per variant. */
@Composable
internal fun SkyWingThemeChoices(selected: String, onSelect: (String) -> Unit) {
    Text("SkyWing glass", style = MaterialTheme.typography.titleMedium)
    skyWingGlassThemes.forEach { theme ->
        val active = selected == theme.id
        val shape = RoundedCornerShape(22.dp)
        val ink = if (theme.light) Color(0xFF14243A) else Color.White
        Row(
            Modifier.fillMaxWidth()
                .background(Brush.linearGradient(theme.wallpaper), shape)
                .border(if (active) 2.dp else 1.dp, if (active) theme.accent else ink.copy(alpha = 0.2f), shape)
                .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(theme.id) })
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Miniature floating dock shows both tint and foreground contrast, rather than a flat color swatch.
            Row(
                Modifier.size(76.dp, 48.dp).background(theme.glassTint, RoundedCornerShape(20.dp))
                    .border(1.dp, ink.copy(alpha = 0.25f), RoundedCornerShape(20.dp)).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { index ->
                    Box(Modifier.weight(1f).height(20.dp).background(
                        if (index == 0) theme.accent else ink.copy(alpha = 0.22f), RoundedCornerShape(6.dp),
                    ))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(theme.label, color = ink, style = MaterialTheme.typography.titleSmall)
                Text(if (theme.skyWingGlassFamily() == "AURORA") "Cyan and violet highlights" else "Soft neutral frost",
                    color = ink.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            }
            if (active) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = theme.accent)
        }
    }
}
