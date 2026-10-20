package com.deepak.videolibrary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** An opaque, compact option for the existing floating-navigation preference. */
@Composable
internal fun FloatingLibraryNavigation(
    selected: Int,
    labels: List<String>,
    icons: List<ImageVector>,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface),
    ) {
        labels.forEachIndexed { index, label ->
            Column(
                Modifier.weight(1f).heightIn(min = 72.dp)
                    .selectable(selected = selected == index, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 2.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                val ink = if (selected == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(icons[index], null, tint = ink, modifier = Modifier.size(22.dp))
                Text(label, color = ink, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun LibrarySidebar(
    selected: Int,
    labels: List<String>,
    icons: List<ImageVector>,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Column(modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Icon(Icons.Outlined.VideoLibrary, null, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp).size(28.dp))
        Text("Video Library", style = MaterialTheme.typography.titleMedium)
        Text("SMB media", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        labels.forEachIndexed { index, label ->
            if (index == labels.lastIndex) Spacer(Modifier.weight(1f))
            val ink = if (selected == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (selected == index) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
                    .selectable(selected = selected == index, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icons[index], null, tint = ink, modifier = Modifier.size(22.dp))
                Text(label, color = ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
