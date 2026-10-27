package com.deepak.videolibrary.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Shared readable card on ALL Settings pages. Reuse rather than duplicating styles. */
@Composable
internal fun SettingsStaticCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(24.dp).background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(3.dp)))
                Text(title, modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

/** Each page has a single open accordion even if its groups live in different source files. */
@Composable
internal fun SettingsAccordion(
    vm: LibraryViewModel,
    page: String,
    id: String,
    title: String,
    summary: String,
    initialOpen: Boolean = false,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expanded = vm.ui.accordionOpen(page, id, initialOpen)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (expanded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) {
                    vm.ui.setAccordionOpen(page, id, !expanded)
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.width(4.dp).height(36.dp).background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(3.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            if (expanded && enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        }
    }
}
