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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The floating navigation bar. Normal themes: an opaque, compact bar. Glass themes: a Liquid Glass capsule
 * that blurs the content scrolling underneath ([hazeState]), tinted dark enough that labels stay readable,
 * with a specular rim and a lighter glass "lens" behind the selected tab.
 */
@Composable
internal fun FloatingLibraryNavigation(
    selected: Int,
    labels: List<String>,
    icons: List<ImageVector>,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onSelect: (Int) -> Unit,
) {
    val theme = LocalLibraryTheme.current
    val glass = theme.glass
    val shape = if (glass) RoundedCornerShape(percent = 50) else RoundedCornerShape(16.dp)
    val surface = Modifier
        .then(if (glass) Modifier.shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
        .clip(shape)
        .then(
            if (glass && hazeState != null) {
                Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = theme.menu,
                        tints = listOf(HazeTint(theme.glassTint), HazeTint(Color.White.copy(alpha = 0.06f))),
                        blurRadius = 26.dp,
                        noiseFactor = 0.04f,
                        // Android 10 and 11 cannot blur: fall back to a stronger tint.
                        fallbackTint = HazeTint(theme.menu.copy(alpha = 0.92f)),
                    ),
                )
            } else if (glass) {
                Modifier.background(theme.menu.copy(alpha = 0.92f))
            } else {
                Modifier.background(MaterialTheme.colorScheme.surface)
            },
        )
        .then(if (glass) Modifier.themedOutline(shape) else Modifier)
    Row(
        modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp)
            .then(surface)
            .padding(if (glass) 6.dp else 0.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = selected == index
            val lens by animateColorAsState(
                if (glass && isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                animationSpec = tween(220),
                label = "nav_lens",
            )
            Column(
                Modifier.weight(1f).heightIn(min = if (glass) 60.dp else 72.dp)
                    .then(if (glass) Modifier.clip(RoundedCornerShape(percent = 50)).background(lens) else Modifier)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 2.dp, vertical = if (glass) 8.dp else 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                val ink = when {
                    isSelected && glass -> theme.accent
                    isSelected -> MaterialTheme.colorScheme.onSurface
                    glass -> Color.White.copy(alpha = 0.88f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
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
