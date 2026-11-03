package com.deepak.videolibrary.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Settings-only styling: always resolve colours from the active app theme. */
@Composable
internal fun settingsCardBorder(emphasised: Boolean = false): BorderStroke {
    val colors = MaterialTheme.colorScheme
    return BorderStroke(if (emphasised) 2.dp else 1.dp, lerp(colors.tertiary, colors.onSurface, 0.50f).copy(alpha = 1f))
}

@Composable
private fun settingsPanelColor(): Color {
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f)
    return if (LocalLibraryTheme.current.light) lerp(surface, Color.White, 0.42f)
        else lerp(surface, Color.Black, 0.22f)
}

@Composable
internal fun SettingsPanel(modifier: Modifier = Modifier, emphasised: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val theme = LocalLibraryTheme.current
    val base = settingsPanelColor()
    // Glass gets a subtle specular wash. Reduced transparency and solid themes stay opaque.
    val top = if (theme.glass && !theme.reduceTransparency) lerp(base, colors.onSurface, 0.025f) else base
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp),
        color = Color.Transparent, contentColor = colors.onSurface, border = settingsCardBorder(emphasised)) {
        Column(Modifier.background(Brush.verticalGradient(listOf(top, base))).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

internal fun settingsIcon(title: String): ImageVector = when {
    title.contains("key", true) || title.contains("Server", true) -> Icons.Outlined.Key
    title.contains("provider", true) -> Icons.Outlined.Layers
    title.contains("online", true) || title.contains("Connection", true) || title.contains("VPN", true) -> Icons.Outlined.Language
    title.contains("theme", true) || title.contains("Appearance", true) -> Icons.Outlined.Palette
    title.contains("portrait", true) -> Icons.Outlined.Person
    title.contains("artwork", true) || title.contains("poster", true) || title.contains("thumbnail", true) -> Icons.Outlined.Image
    title.contains("scan", true) || title.contains("refresh", true) || title.contains("Sync", true) -> Icons.Outlined.Sync
    title.contains("backup", true) || title.contains("restore", true) -> Icons.Outlined.Backup
    title.contains("diagnostic", true) || title.contains("advanced", true) -> Icons.Outlined.Tune
    title.contains("folder", true) || title.contains("files", true) -> Icons.Outlined.Folder
    title.contains("Video", true) -> Icons.Outlined.PlayCircleOutline
    title.contains("new", true) -> Icons.Outlined.NewReleases
    else -> Icons.Outlined.Settings
}

@Composable
internal fun SettingsIconTile(title: String) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = lerp(colors.surfaceContainerHigh, colors.tertiary, 0.30f)) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(settingsIcon(title), contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
internal fun SettingsHeader(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
}

@Composable
internal fun SettingsGroupHeading(title: String) {
    SettingsHeader {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(settingsIcon(title), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun SettingsStaticCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsPanel(Modifier.padding(vertical = 1.dp)) {
        SettingsGroupHeading(title)
        content()
    }
}

/** Keep the existing single-open-group navigation and persistence. */
@Composable
internal fun SettingsAccordion(
    vm: LibraryViewModel, page: String, id: String, title: String, summary: String,
    initialOpen: Boolean = false, enabled: Boolean = true, splitContent: Boolean = false, content: @Composable ColumnScope.() -> Unit,
) {
    val expanded = vm.ui.accordionOpen(page, id, initialOpen)
    Column(Modifier.fillMaxWidth()) {
        SettingsPanel(Modifier.padding(vertical = 1.dp), emphasised = expanded) {
            SettingsHeader {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .clickable(enabled = enabled, role = Role.Button) { vm.ui.setAccordionOpen(page, id, !expanded) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(settingsIcon(title), contentDescription = null, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium,
                            color = LocalContentColor.current)
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current)
                    }
                    Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                        contentDescription = null, tint = LocalContentColor.current)
                }
            }
            if (expanded && enabled && !splitContent) content()
        }
        if (expanded && enabled && splitContent) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

/** Wrapping labels and 48dp targets keep segmented choices usable with large text. */
@Composable
internal fun SettingsChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainer, border = settingsCardBorder()) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { index, label ->
                val active = selected == index
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                    color = if (active) colors.tertiary else Color.Transparent,
                    border = if (active) settingsCardBorder(true) else null) {
                    Box(Modifier.selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(index) })
                        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) {
                                if (colors.tertiary.luminance() > 0.179f) Color.Black else Color.White
                            } else colors.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
    }
}
