package com.skywing.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale

/** Click and hold (long press) with a hand cursor, as on every card and button. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.skyClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, enabled: Boolean = true): Modifier =
    this.pointerHoverIcon(PointerIcon.Hand).combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)

internal fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
}

internal fun formatClock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total / 60) % 60
    val secs = total % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs) else String.format(Locale.US, "%d:%02d", minutes, secs)
}

internal fun WindowsMediaEntry.title(): String = file.nameWithoutExtension

@Composable
internal fun SkyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    icon: SkyIcon? = null,
) {
    val theme = LocalSkyTheme.current
    val shape = RoundedCornerShape(50)
    val container = when {
        !enabled -> theme.ink.copy(alpha = 0.08f)
        primary -> theme.accent
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> theme.muted.copy(alpha = 0.5f)
        primary -> theme.onAccent
        else -> theme.ink
    }
    Row(
        modifier
            .clip(shape)
            .background(container)
            .then(if (primary) Modifier else Modifier.border(1.dp, theme.ink.copy(alpha = 0.25f), shape))
            .skyClickable(onClick, enabled = enabled)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) SkyIconView(icon, content, size = 18.dp)
        Text(text, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A rounded filter or tab chip with a lens behind it when selected (like the Android navigation bar). */
@Composable
internal fun SkyChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: SkyIcon? = null) {
    val theme = LocalSkyTheme.current
    val lens by animateColorAsState(
        when {
            !selected -> Color.Transparent
            theme.light -> theme.accent.copy(alpha = 0.16f)
            else -> Color.White.copy(alpha = 0.18f)
        },
        animationSpec = tween(200),
    )
    val ink = if (selected) theme.accent else theme.ink.copy(alpha = if (theme.light) 0.78f else 0.88f)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(lens).skyClickable(onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) SkyIconView(icon, ink, size = 18.dp)
        Text(text, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A glass capsule holding a row of chips. */
@Composable
internal fun SkyChipBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.skyGlass(RoundedCornerShape(50)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEachIndexed { index, label -> SkyChip(label, index == selected, { onSelect(index) }) }
    }
}

@Composable
internal fun SkyIconButton(
    icon: SkyIcon,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    box: androidx.compose.ui.unit.Dp = 40.dp,
    glyph: androidx.compose.ui.unit.Dp = 22.dp,
    outlined: Boolean = false,
) {
    val theme = LocalSkyTheme.current
    val color = (tint ?: theme.ink).let { if (enabled) it else it.copy(alpha = 0.35f) }
    Box(
        modifier
            .size(box)
            .clip(CircleShape)
            .then(if (outlined) Modifier.border(2.dp, color, CircleShape) else Modifier)
            .skyClickable(onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        SkyIconView(icon, color, size = glyph)
    }
}

@Composable
internal fun SkyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: SkyIcon? = null,
) {
    val theme = LocalSkyTheme.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(50),
        placeholder = { Text(placeholder, color = theme.muted, maxLines = 1) },
        leadingIcon = if (leading != null) ({ SkyIconView(leading, theme.muted) }) else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = theme.ink,
            unfocusedTextColor = theme.ink,
            cursorColor = theme.accent,
            focusedContainerColor = theme.panelTint(),
            unfocusedContainerColor = theme.panelTint(),
            focusedBorderColor = theme.accent,
            unfocusedBorderColor = theme.ink.copy(alpha = 0.22f),
            focusedLabelColor = theme.accent,
            unfocusedLabelColor = theme.muted,
        ),
    )
}

/** Page title with an optional count under it. */
@Composable
internal fun PageTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val theme = LocalSkyTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = theme.ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = theme.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val theme = LocalSkyTheme.current
    Text(text, color = theme.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    val theme = LocalSkyTheme.current
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = theme.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A picture taken from the video (or a plain placeholder while it is being made). */
@Composable
internal fun Thumb(file: File?, stamp: Long, modifier: Modifier = Modifier, glyph: SkyIcon = SkyIcon.Play) {
    val theme = LocalSkyTheme.current
    val picture by produceState<ImageBitmap?>(initialValue = null, file?.path, stamp) {
        value = if (file == null) null else ThumbnailStore.load(file, stamp)
    }
    Box(
        modifier.background(Brush.linearGradient(listOf(theme.card, theme.surface))),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = picture
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            SkyIconView(glyph, theme.muted.copy(alpha = 0.5f), size = 30.dp)
        }
    }
}

/** A round tick shown on cards while videos are being selected. */
@Composable
internal fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalSkyTheme.current
    Box(
        modifier.size(24.dp).clip(CircleShape)
            .background(if (selected) theme.accent else Color(0x66000000))
            .border(2.dp, if (selected) theme.accent else Color.White.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) SkyIconView(SkyIcon.Check, theme.onAccent, size = 16.dp)
    }
}

/**
 * One folder card, the same for Custom, Virtual and Original folders: the cover (a picture from the first
 * video), the name and a short detail line. Clicking opens it; holding opens its options.
 */
@Composable
internal fun FolderCard(
    title: String,
    detail: String,
    cover: WindowsMediaEntry?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val theme = LocalSkyTheme.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.surface).skyClickable(onClick, onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (cover != null) {
                Thumb(cover.file, cover.modified, Modifier.fillMaxSize(), glyph = SkyIcon.Folder)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(theme.card, theme.surface))), contentAlignment = Alignment.Center) {
                    SkyIconView(SkyIcon.Folder, theme.muted.copy(alpha = 0.6f), size = 40.dp)
                }
            }
        }
        Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 9.dp)) {
            Text(title, color = theme.ink, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = theme.muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A video card: picture, name, size and a thin watched bar. [selected] is null when nothing is being selected. */
@Composable
internal fun VideoCard(
    entry: WindowsMediaEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    detail: String? = null,
) {
    val theme = LocalSkyTheme.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected == true) theme.accent.copy(alpha = 0.22f) else theme.surface)
            .skyClickable(onClick, onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Thumb(entry.file, entry.modified, Modifier.fillMaxSize())
            if (progress != null && progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                        .background(theme.accent),
                )
            }
            if (selected != null) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 9.dp)) {
            Text(entry.title(), color = theme.ink, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail ?: listOf(formatSize(entry.size), entry.sourceName).filter { it.isNotBlank() }.joinToString("  ·  "),
                color = theme.muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Asks for one line of text (a folder name). */
@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    hint: String? = null,
) {
    val theme = LocalSkyTheme.current
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.menu,
        titleContentColor = theme.ink,
        textContentColor = theme.ink,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hint != null) Text(hint, color = theme.muted, style = MaterialTheme.typography.bodySmall)
                SkyField(name, { name = it }, "Name", Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            SkyButton(confirmLabel, onClick = {
                onConfirm(name.trim())
                onDismiss()
            }, enabled = name.isNotBlank())
        },
        dismissButton = { SkyButton("Cancel", onDismiss, primary = false) },
    )
}

/** A slim scroll position marker at the right edge of a grid (the mouse wheel does the scrolling). */
@Composable
internal fun GridScrollThumb(state: LazyGridState, modifier: Modifier = Modifier) {
    val theme = LocalSkyTheme.current
    Canvas(modifier.width(6.dp).fillMaxHeight()) {
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo.size
        if (total > 0 && visible > 0 && visible < total) {
            val thumb = (visible.toFloat() / total).coerceIn(0.06f, 1f) * size.height
            val top = (state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f) * (size.height - thumb)
            drawRoundRect(
                color = theme.ink.copy(alpha = 0.28f),
                topLeft = Offset(0f, top),
                size = Size(size.width, thumb),
                cornerRadius = CornerRadius(size.width / 2f, size.width / 2f),
            )
        }
    }
}
