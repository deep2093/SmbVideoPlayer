package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration

/*
 * Compact toolbars (14 Nov 2026). On a phone the page toolbars held six or seven icons and squeezed the title to
 * "...". Now, below 600 dp of width, only the main buttons stay on the bar (search, add, play, an active download)
 * and every other button moves into one ⋮ menu, with its name beside its icon. Tablets keep the full row.
 *
 * Buttons don't need to know where they end up: [IconAction] and [ToolbarMenuButton] check [LocalToolbarOverflow]
 * and either draw themselves or add a line to the menu.
 */

/** One line in the ⋮ menu. [onClick] is a state so a line only changes when its icon, name or enabled state do. */
internal data class ToolbarEntry(val key: String, val icon: ImageVector, val label: String, val enabled: Boolean, val onClick: State<() -> Unit>)

/** The lines of one toolbar's ⋮ menu, in the order the buttons appear on the page. */
internal class ToolbarOverflow {
    val entries = mutableStateListOf<ToolbarEntry>()

    fun put(entry: ToolbarEntry) {
        val index = entries.indexOfFirst { it.key == entry.key }
        if (index < 0) entries.add(entry) else if (entries[index] != entry) entries[index] = entry
    }

    fun remove(key: String) {
        entries.removeAll { it.key == key }
    }
}

/** Set while a compact toolbar is being drawn: buttons that are not main ones go into its menu. */
internal val LocalToolbarOverflow = compositionLocalOf<ToolbarOverflow?> { null }

/** Whether toolbars fold their extra buttons into a menu: phones, and narrow windows. */
@Composable
internal fun compactToolbar(): Boolean = LocalConfiguration.current.screenWidthDp < 600

/** Adds a line to the ⋮ menu for as long as the calling button is on the page. */
@Composable
internal fun RegisterOverflowEntry(key: String, icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val overflow = LocalToolbarOverflow.current ?: return
    val click = rememberUpdatedState(onClick)
    SideEffect { overflow.put(ToolbarEntry(key, icon, label, enabled, click)) }
    DisposableEffect(overflow, key) { onDispose { overflow.remove(key) } }
}

/**
 * Draws a toolbar's buttons: in full on wide screens; on phones the main buttons plus one ⋮ menu for the rest.
 * Use inside a toolbar Row, in place of calling the buttons directly.
 */
@Composable
internal fun RowScope.AdaptiveToolbarActions(actions: @Composable RowScope.() -> Unit) {
    if (!compactToolbar()) {
        actions()
        return
    }
    val overflow = remember { ToolbarOverflow() }
    CompositionLocalProvider(LocalToolbarOverflow provides overflow) { actions() }
    if (overflow.entries.isNotEmpty()) {
        Box {
            var open by remember { mutableStateOf(false) }
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                overflow.entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entry.label) },
                        leadingIcon = { Icon(entry.icon, contentDescription = null) },
                        enabled = entry.enabled,
                        onClick = {
                            open = false
                            entry.onClick.value()
                        },
                    )
                }
            }
        }
    }
}

/**
 * A toolbar button that opens its own menu (sort, folder options). On a phone it becomes a line in the ⋮ menu that
 * opens the same menu, unless it is [primary].
 */
@Composable
internal fun ToolbarMenuButton(
    icon: ImageVector,
    label: String,
    primary: Boolean = false,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val folded = LocalToolbarOverflow.current != null && !primary
    Box {
        if (folded) {
            RegisterOverflowEntry(label, icon, label, enabled = true) { open = true }
        } else {
            IconButton(onClick = { open = true }) { Icon(icon, contentDescription = label) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Buttons inside the menu draw normally, not into the toolbar's ⋮ menu.
            CompositionLocalProvider(LocalToolbarOverflow provides null) { content { open = false } }
        }
    }
}
