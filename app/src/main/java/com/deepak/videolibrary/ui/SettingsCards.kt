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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Settings-only styling: always resolve colours from the active app theme. */
@Composable
internal fun settingsCardBorder(emphasised: Boolean = false): BorderStroke {
    val colors = MaterialTheme.colorScheme
    return BorderStroke(1.dp, lerp(colors.outlineVariant, colors.tertiary, if (emphasised) 0.5f else 0.22f))
}

@Composable
internal fun SettingsPanel(modifier: Modifier = Modifier, emphasised: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val theme = LocalLibraryTheme.current
    val base = colors.surfaceContainerHigh
    // Glass gets a subtle specular wash. Reduced transparency and solid themes stay opaque.
    val top = if (theme.glass && !theme.reduceTransparency) lerp(base, colors.onSurface, 0.07f) else base
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = Color.Transparent, contentColor = colors.onSurface, border = settingsCardBorder(emphasised)) {
        Column(Modifier.background(Brush.verticalGradient(listOf(top, base))).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
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
    Surface(shape = RoundedCornerShape(12.dp), color = lerp(colors.surfaceContainerHigh, colors.tertiary, 0.22f)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(settingsIcon(title), contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun SettingsGroupHeading(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsIconTile(title)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun SettingsStaticCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsPanel(Modifier.padding(vertical = 5.dp)) {
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
        SettingsPanel(Modifier.padding(vertical = 5.dp), emphasised = expanded) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(enabled = enabled, role = Role.Button) { vm.ui.setAccordionOpen(page, id, !expanded) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsIconTile(title)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                    contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    color = if (active) lerp(colors.surfaceContainerHigh, colors.tertiary, 0.45f) else Color.Transparent,
                    border = if (active) settingsCardBorder(true) else null) {
                    Box(Modifier.selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(index) })
                        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
    }
}
