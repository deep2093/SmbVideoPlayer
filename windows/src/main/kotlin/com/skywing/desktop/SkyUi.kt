package com.skywing.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import java.io.File
import java.util.Locale

/** Click and hold (long press) with a hand cursor, as on every card and button. A right click does what holding does. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.skyClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, enabled: Boolean = true): Modifier =
    this.pointerHoverIcon(PointerIcon.Hand)
        .then(
            if (onLongClick == null || !enabled) Modifier
            else Modifier.pointerInput(onLongClick) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) onLongClick()
                    }
                }
            },
        )
        .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)

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
internal fun SkyChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: SkyIcon? = null,
    onLongClick: (() -> Unit)? = null,
) {
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
        modifier.clip(RoundedCornerShape(50)).background(lens).skyClickable(onClick, onLongClick).padding(horizontal = 16.dp, vertical = 9.dp),
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
 * video), the name and a short detail line. Clicking opens it; holding (or a right click) opens its options.
 * [listMode] shows it as a row; [selected] is null when folders are not being selected.
 */
@Composable
internal fun FolderCard(
    title: String,
    detail: String,
    cover: WindowsMediaEntry?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    listMode: Boolean = false,
    selected: Boolean? = null,
) {
    val theme = LocalSkyTheme.current
    val picture: @Composable (Modifier) -> Unit = { pictureModifier ->
        Box(pictureModifier) {
            if (cover != null) {
                Thumb(cover.file, cover.modified, Modifier.fillMaxSize(), glyph = SkyIcon.Folder)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(theme.card, theme.surface))), contentAlignment = Alignment.Center) {
                    SkyIconView(SkyIcon.Folder, theme.muted.copy(alpha = 0.6f), size = if (listMode) 26.dp else 40.dp)
                }
            }
            if (selected != null) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(if (listMode) 4.dp else 8.dp))
        }
    }
    CardFrame(modifier, selected == true, listMode, onClick, onLongClick, picture) {
        Text(title, color = theme.ink, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, color = theme.muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    listMode: Boolean = false,
) {
    val theme = LocalSkyTheme.current
    val picture: @Composable (Modifier) -> Unit = { pictureModifier ->
        Box(pictureModifier) {
            Thumb(entry.file, entry.modified, Modifier.fillMaxSize())
            if (progress != null && progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                        .background(theme.accent),
                )
            }
            if (selected != null) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(if (listMode) 4.dp else 8.dp))
        }
    }
    CardFrame(modifier, selected == true, listMode, onClick, onLongClick, picture) {
        Text(entry.title(), color = theme.ink, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            detail ?: listOf(formatSize(entry.size), entry.sourceName).filter { it.isNotBlank() }.joinToString("  ·  "),
            color = theme.muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The frame shared by folder and video cards: a 16:9 picture over the text, or a row with a small picture in list mode. */
@Composable
private fun CardFrame(
    modifier: Modifier,
    highlighted: Boolean,
    listMode: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    picture: @Composable (Modifier) -> Unit,
    text: @Composable () -> Unit,
) {
    val theme = LocalSkyTheme.current
    val frame = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (highlighted) theme.accent.copy(alpha = 0.22f) else theme.surface)
        .skyClickable(onClick, onLongClick)
    if (listMode) {
        Row(frame.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            picture(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f)) { text() }
        }
    } else {
        Column(frame) {
            picture(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 9.dp)) { text() }
        }
    }
}

/** One entry of a card's or a page's menu. */
internal data class MenuAction(val label: String, val icon: SkyIcon? = null, val enabled: Boolean = true, val action: () -> Unit)

/** A small menu that opens where it is placed, in the theme's menu colour. */
@Composable
internal fun SkyMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>) {
    val theme = LocalSkyTheme.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.background(theme.menu)) {
        actions.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label, color = if (item.enabled) theme.ink else theme.muted) },
                leadingIcon = item.icon?.let { icon -> { SkyIconView(icon, if (item.enabled) theme.accent else theme.muted, size = 18.dp) } },
                enabled = item.enabled,
                onClick = {
                    onDismiss()
                    item.action()
                },
            )
        }
    }
}

/** A round button that opens a menu under it. */
@Composable
internal fun SkyMenuButton(icon: SkyIcon, description: String, actions: List<MenuAction>, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        SkyIconButton(icon, description, { open = true }, enabled = enabled)
        SkyMenu(open, { open = false }, actions)
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

/** The slim scroll bar used everywhere: drag it, click the track, or use the mouse wheel. */
@Composable
internal fun SkyScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val theme = LocalSkyTheme.current
    VerticalScrollbar(
        adapter = adapter,
        modifier = modifier.fillMaxHeight(),
        interactionSource = interaction,
        style = ScrollbarStyle(
            minimalHeight = 40.dp,
            thickness = 8.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 200,
            unhoverColor = theme.ink.copy(alpha = 0.28f),
            hoverColor = theme.ink.copy(alpha = 0.55f),
        ),
    )
}

/**
 * The scroll bar at the right edge of a grid. While it is dragged, a bubble shows the letter of the current
 * position when [labelFor] gives one for an item index (lists sorted by name), like the letter rail on Android.
 */
@Composable
internal fun GridScrollbar(state: LazyGridState, modifier: Modifier = Modifier, labelFor: ((Int) -> String?)? = null) {
    val theme = LocalSkyTheme.current
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    BoxWithConstraints(modifier.fillMaxHeight()) {
        SkyScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.TopEnd), interaction)
        val letter = if (dragged && labelFor != null) labelFor(state.firstVisibleItemIndex) else null
        if (letter != null) {
            val info = state.layoutInfo
            val range = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            val fraction = (state.firstVisibleItemIndex.toFloat() / range).coerceIn(0f, 1f)
            Box(
                Modifier.align(Alignment.TopEnd)
                    .offset(x = (-20).dp, y = (maxHeight - 56.dp).coerceAtLeast(0.dp) * fraction)
                    .size(56.dp).clip(CircleShape).background(theme.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, color = theme.onAccent, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The scroll bar for a plain list. */
@Composable
internal fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    SkyScrollbar(rememberScrollbarAdapter(state), modifier)
}

/** The scroll bar for a scrolling column (Settings pages, Files). */
@Composable
internal fun ColumnScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    SkyScrollbar(rememberScrollbarAdapter(state), modifier)
}
