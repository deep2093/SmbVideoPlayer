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
 * The floating navigation bar: a rounded capsule in every theme, with a soft shadow, a rim and a rounded "lens"
 * behind the selected tab. Glass themes also blur the content scrolling underneath ([hazeState]), tinted dark
 * enough that labels stay readable; other themes use their own opaque surface.
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
    val shape = RoundedCornerShape(percent = 50)
    val shadowAlpha = if (theme.light) 0.35f else 1f
    val surface = Modifier
        .shadow(if (glass) 20.dp else 12.dp, shape, ambientColor = Color.Black.copy(alpha = shadowAlpha), spotColor = Color.Black.copy(alpha = shadowAlpha))
        .clip(shape)
        .then(
            if (glass && hazeState != null) {
                Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = theme.menu,
                        tints = listOf(HazeTint(theme.glassTint), HazeTint(Color.White.copy(alpha = if (theme.light) 0.2f else 0.06f))),
                        blurRadius = 26.dp,
                        noiseFactor = 0.04f,
                        // Android 10 and 11 cannot blur: fall back to a stronger tint.
                        fallbackTint = HazeTint(theme.menu.copy(alpha = 0.92f)),
                    ),
                )
            } else if (glass) {
                Modifier.background(theme.menu.copy(alpha = 0.92f))
            } else {
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
            },
        )
        .themedOutline(shape)
    Row(
        modifier.widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 16.dp)
            .then(surface)
            .padding(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = selected == index
            val lensColor = when {
                !isSelected -> Color.Transparent
                glass && theme.light -> theme.accent.copy(alpha = 0.14f)
                glass -> Color.White.copy(alpha = 0.2f)
                else -> theme.accent.copy(alpha = 0.22f)
            }
            val lens by animateColorAsState(
                lensColor,
                animationSpec = tween(220),
                label = "nav_lens",
            )
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(percent = 50)).background(lens)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 2.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                val ink = when {
                    isSelected -> theme.accent
                    glass && theme.light -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
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
    val theme = LocalLibraryTheme.current
    Column(modifier.then(if (theme.glass) Modifier.skyWingGlassSurface(
        shape = RoundedCornerShape(28.dp), liveBlur = false,
    ) else Modifier.background(MaterialTheme.colorScheme.background)).padding(16.dp)) {
        AppIconMark(40.dp, Modifier.padding(top = 12.dp, bottom = 12.dp))
        Text(APP_NAME, style = MaterialTheme.typography.titleMedium)
        Text("SMB media", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        labels.forEachIndexed { index, label ->
            if (index == labels.lastIndex) Spacer(Modifier.weight(1f))
            val ink = if (selected == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(percent = 50))
                    .background(if (selected == index) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
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
