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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.selected
import com.skywing.desktop.ui.adaptive.LocalFocusVisible
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
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
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.heightIn
import com.skywing.desktop.ui.adaptive.LocalAdaptive
import com.skywing.desktop.ui.theme.SkyMotion
import com.skywing.desktop.ui.theme.SkyRadius
import java.io.File
import java.util.Locale

/**
 * Names a control for screen readers. Compose Desktop 1.8 gives Java Access Bridge its accessible name from text
 * semantics (contentDescription becomes only the description), so icon-only and unlabelled controls need text.
 */
internal fun Modifier.accessibleName(name: String): Modifier = semantics { text = AnnotatedString(name) }

/**
 * Labels a switch or check box for screen readers through its description. On these controls text semantics give
 * Java Access Bridge a text interface, which NVDA announces as "read only"; NVDA reads the description right after
 * the role instead ("check box, checked, Motion smoothing"). The JAB name stays empty, so automation must match the
 * name or, when it is empty, the description. Buttons, tabs and sliders keep [accessibleName].
 */
internal fun Modifier.accessibleDescription(label: String): Modifier = semantics { contentDescription = label }

/**
 * A Material switch that assistive technology sees as a labelled check box. Compose Desktop 1.8 maps no Java Access Bridge
 * role for Role.Switch (it arrives as a plain label), while Role.Checkbox arrives as a check box with its checked state.
 * Material's own toggle handling is switched off and the same toggleable, minimum size and interaction source are
 * applied here, so the look, size, press feedback, mouse and keyboard behaviour stay exactly Material's.
 */
@Composable
internal fun SkySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    name: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
) {
    val interactions = remember { MutableInteractionSource() }
    Switch(
        checked = checked,
        onCheckedChange = null,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .toggleable(checked, interactions, indication = null, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .accessibleDescription(name),
        enabled = enabled,
        colors = colors,
        interactionSource = interactions,
    )
}

/** Click and hold (long press) with a hand cursor, as on every card and button. A right click does what holding does. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.skyClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role? = null,
    ringShape: Shape = RoundedCornerShape(SkyRadius.control),
): Modifier =
    this.pointerHoverIcon(PointerIcon.Hand)
        .then(
            if (onLongClick == null || !enabled) Modifier
            else Modifier.pointerInput(onLongClick) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
                        // Opens on release, so a right-button drag can draw a selection box instead.
                        var taken = false
                        while (true) {
                            val next = awaitPointerEvent()
                            if (next.changes.any { it.isConsumed }) taken = true
                            if (next.type == PointerEventType.Release || !next.buttons.areAnyPressed) break
                        }
                        if (!taken) onLongClick()
                    }
                }
            },
        )
        .focusRing(ringShape)
        .combinedClickable(enabled = enabled, role = role, onLongClick = onLongClick, onClick = onClick)

/**
 * The keyboard focus ring (M3 design brief 5.7, A6): 2 dp in the theme's focus colour while the control below it has
 * focus and the keyboard is in use ([LocalFocusVisible]). It is drawn just inside the edge, over the content, so a
 * control that clips its corners (cards, icon buttons) keeps it.
 */
internal fun Modifier.focusRing(shape: Shape = RoundedCornerShape(SkyRadius.control)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val visible = LocalFocusVisible.current
    val colour = LocalSkyTheme.current.colors.focus
    onFocusChanged { focused = it.isFocused }.drawWithContent {
        drawContent()
        if (focused && visible) {
            val inset = 1.dp.toPx()
            translate(inset, inset) {
                val outline = shape.createOutline(Size(size.width - 2 * inset, size.height - 2 * inset), layoutDirection, this)
                drawOutline(outline, colour, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

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

/**
 * A button (plan 6.3): Accent (the page's main action), Standard (a control-coloured fill with the 1 px edge) or
 * Subtle (no fill). Radius 8; at least as tall as the input mode's smallest target.
 */
@Composable
internal fun SkyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    icon: SkyIcon? = null,
    subtle: Boolean = false,
) {
    val theme = LocalSkyTheme.current
    val c = theme.colors
    val shape = RoundedCornerShape(SkyRadius.control)
    val accent = primary && !subtle
    val container = when {
        subtle -> Color.Transparent
        !enabled -> c.control.copy(alpha = 0.5f)
        accent -> c.accent
        else -> c.control
    }
    val content = when {
        !enabled -> c.textDisabled
        accent -> c.onAccent
        else -> c.text
    }
    Row(
        modifier
            .heightIn(min = LocalAdaptive.current.minTarget)
            .clip(shape)
            .background(container)
            .then(if (accent || subtle) Modifier else Modifier.border(1.dp, c.stroke, shape))
            .skyClickable(onClick, enabled = enabled, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) SkyIconView(icon, content, size = 18.dp)
        Text(text, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** One choice of a segmented control (plan 6.3, SkySegmented): the selected one has the accent at 16 % and accent text. */
@Composable
internal fun SkyChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: SkyIcon? = null,
    onLongClick: (() -> Unit)? = null,
    /** What screen readers hear, when it says more than [text] (a short tab label). */
    name: String? = null,
) {
    val theme = LocalSkyTheme.current
    val lens by animateColorAsState(if (selected) theme.colors.accentSubtle else Color.Transparent, animationSpec = SkyMotion.fast())
    val ink = if (selected) theme.accent else theme.ink
    Row(
        modifier.heightIn(min = LocalAdaptive.current.minTarget - 8.dp).clip(RoundedCornerShape(6.dp)).background(lens)
            .skyClickable(onClick, onLongClick, role = Role.Tab).semantics { this.selected = selected }
            .then(if (name != null && name != text) Modifier.accessibleName(name) else Modifier)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) SkyIconView(icon, ink, size = 18.dp)
        Text(
            text, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            modifier = if (name != null && name != text) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

/** A segmented control (plan 6.3): choices side by side in one control-coloured frame, one of them selected. */
@Composable
internal fun SkySegmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) =
    SkyChipBar(labels, selected, onSelect, modifier)

/** The row of choices of [SkySegmented]. */
@Composable
internal fun SkyChipBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSkyTheme.current.colors
    val shape = RoundedCornerShape(SkyRadius.control)
    Row(
        modifier.clip(shape).background(c.control.copy(alpha = 0.6f)).border(1.dp, c.stroke, shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label -> SkyChip(label, index == selected, { onSelect(index) }) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    val shape = if (outlined) CircleShape else RoundedCornerShape(SkyRadius.control)
    // The tooltip says what an icon-only button does (plan 6.3); the accessible name stays on the button.
    TooltipArea(tooltip = { SkyTooltip(description) }, delayMillis = 600) {
        Box(
            modifier
                .size(box)
                .clip(shape)
                .then(if (outlined) Modifier.border(2.dp, color, CircleShape) else Modifier)
                .skyClickable(onClick, enabled = enabled, role = Role.Button, ringShape = shape)
                .accessibleName(description),
            contentAlignment = Alignment.Center,
        ) {
            SkyIconView(icon, color, size = glyph)
        }
    }
}

/** A tooltip: layer 3, radius 8, Caption text. */
@Composable
internal fun SkyTooltip(text: String) {
    val c = LocalSkyTheme.current.colors
    val shape = RoundedCornerShape(SkyRadius.control)
    Text(
        text,
        color = c.text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.clip(shape).background(c.layer3).border(1.dp, c.stroke, shape).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
internal fun SkyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: SkyIcon? = null,
    /** Hides what is typed (keys). */
    secret: Boolean = false,
) {
    val theme = LocalSkyTheme.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // The bridge names a text field by what is typed in it, so the placeholder becomes its description.
        modifier = modifier.semantics { contentDescription = placeholder },
        singleLine = true,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(SkyRadius.control),
        placeholder = { Text(placeholder, color = theme.muted, maxLines = 1) },
        leadingIcon = if (leading != null) ({ SkyIconView(leading, theme.muted) }) else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = theme.ink,
            unfocusedTextColor = theme.ink,
            cursorColor = theme.accent,
            focusedContainerColor = theme.card,
            unfocusedContainerColor = theme.card,
            focusedBorderColor = theme.accent,
            unfocusedBorderColor = theme.ink.copy(alpha = 0.30f),
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

/**
 * A page header's line (M3 design brief 5.6: Compact always fits, from the 640 dp minimum window): [lead], then the
 * [trail] actions at the end. When the line can't hold [lead] at [leadMin] (or, with no [leadMin], at its own width)
 * and every action at its own width, the actions go under [lead] instead, at the end, wrapping onto further lines if
 * they must. No action is ever squeezed.
 */
@Composable
internal fun HeaderLine(
    modifier: Modifier = Modifier,
    leadMin: Dp? = null,
    gap: Dp = 8.dp,
    lead: @Composable RowScope.() -> Unit,
    trail: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(
            { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) { lead() } },
            trail,
        ),
        modifier = modifier.fillMaxWidth(),
    ) { (leadParts, trailParts), constraints ->
        val gapPx = gap.roundToPx()
        val lineGap = 8.dp.roundToPx()
        val actions = trailParts.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        // A part with no width (a closed menu's anchor) takes no room and no gap.
        fun widthOf(parts: List<Placeable>): Int {
            val seen = parts.filter { it.width > 0 }
            return seen.sumOf { it.width } + gapPx * (seen.size - 1).coerceAtLeast(0)
        }
        val actionsWidth = widthOf(actions)
        val leadPart = leadParts.single()
        val leadNeed = leadMin?.roundToPx() ?: leadPart.maxIntrinsicWidth(constraints.maxHeight)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else leadNeed + gapPx + actionsWidth
        val oneLine = actionsWidth == 0 || leadNeed + gapPx + actionsWidth <= width
        val leadPlaced = leadPart.measure(Constraints(maxWidth = if (oneLine && actionsWidth > 0) width - gapPx - actionsWidth else width))

        if (oneLine) {
            val height = maxOf(constraints.minHeight, leadPlaced.height, actions.maxOfOrNull { it.height } ?: 0)
            layout(width, height) {
                leadPlaced.placeRelative(0, (height - leadPlaced.height) / 2)
                var x = width - actionsWidth
                actions.forEach { p ->
                    p.placeRelative(x, (height - p.height) / 2)
                    if (p.width > 0) x += p.width + gapPx
                }
            }
        } else {
            val lines = mutableListOf(mutableListOf<Placeable>())
            actions.forEach { p ->
                val line = lines.last()
                if (p.width > 0 && line.any { it.width > 0 } && widthOf(line) + gapPx + p.width > width) lines += mutableListOf(p) else line += p
            }
            val lineHeights = lines.map { line -> line.maxOfOrNull { it.height } ?: 0 }
            val height = maxOf(constraints.minHeight, leadPlaced.height + lineHeights.sumOf { it + lineGap })
            layout(width, height) {
                leadPlaced.placeRelative(0, 0)
                var y = leadPlaced.height + lineGap
                lines.forEachIndexed { index, line ->
                    var x = width - widthOf(line)
                    line.forEach { p ->
                        p.placeRelative(x, y + (lineHeights[index] - p.height) / 2)
                        if (p.width > 0) x += p.width + gapPx
                    }
                    y += lineHeights[index] + lineGap
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val theme = LocalSkyTheme.current
    Text(text, color = theme.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier, action: MenuAction? = null) {
    val theme = LocalSkyTheme.current
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, color = theme.muted, style = MaterialTheme.typography.bodyMedium)
            if (action != null) SkyButton(action.label, action.action, enabled = action.enabled, icon = action.icon)
        }
    }
}

/** A picture taken from the video (or a plain placeholder while it is being made). */
@Composable
internal fun Thumb(file: File?, stamp: Long, modifier: Modifier = Modifier, glyph: SkyIcon = SkyIcon.Play) {
    val theme = LocalSkyTheme.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    val picture by produceState<ImageBitmap?>(initialValue = null, file?.path, stamp, box) {
        value = if (file == null) null else ThumbnailStore.load(file, stamp, box)
    }
    Box(
        modifier.background(Brush.linearGradient(listOf(theme.card, theme.surface))).onSizeChanged { box = it },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = picture
        ReportScreenColor(file?.path ?: "", bitmap)
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
 * One folder card, the same for Custom, Virtual and drive folders: the cover (chosen in Change cover, a portrait, or
 * made from its videos), the name and a short detail line. Clicking opens it; holding (or a right click) opens its options.
 * [listMode] shows it as a row; [selected] is null when folders are not being selected.
 */
@Composable
internal fun FolderCard(
    title: String,
    detail: String,
    coverKey: String?,
    coverVideos: List<WindowsMediaEntry>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    listMode: Boolean = false,
    selected: Boolean? = null,
    aspect: Float = CardAspect.SCENE,
) {
    val theme = LocalSkyTheme.current
    val picture: @Composable (Modifier) -> Unit = { pictureModifier ->
        Box(pictureModifier) {
            if (coverVideos.isNotEmpty() || coverKey != null) {
                FolderPicture(coverKey, coverVideos, aspect < 1f, listMode, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(theme.card, theme.surface))), contentAlignment = Alignment.Center) {
                    SkyIconView(SkyIcon.Folder, theme.muted.copy(alpha = 0.6f), size = if (listMode) 26.dp else 40.dp)
                }
            }
            if (selected != null) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(if (listMode) 4.dp else 8.dp))
        }
    }
    CardFrame(modifier, selected, null, listMode, aspect, onClick, onLongClick, picture) {
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
    aspect: Float = CardAspect.SCENE,
) {
    val theme = LocalSkyTheme.current
    val picture: @Composable (Modifier) -> Unit = { pictureModifier ->
        Box(pictureModifier) {
            VideoPicture(entry, aspect < 1f, Modifier.fillMaxSize())
            if (progress != null && progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                        .background(theme.accent),
                )
            }
            if (selected != null) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(if (listMode) 4.dp else 8.dp))
        }
    }
    val watched = progress?.takeIf { it > 0f }?.let { "${(it.coerceIn(0f, 1f) * 100).roundToInt()}% watched" }
    CardFrame(modifier, selected, watched, listMode, aspect, onClick, onLongClick, picture) {
        Text(entry.title(), color = theme.ink, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            detail ?: listOf(formatSize(entry.size), entry.sourceName).filter { it.isNotBlank() }.joinToString("  ·  "),
            color = theme.muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The frame shared by folder and video cards: a picture of the given [aspect] (16:9, 3:4 or 2:3) over the text, or a row with a small
 * picture in list mode. Screen readers get a push button named by the first text, the title (M3 design brief 5.7), with
 * [watched] as its description and, only while cards are being selected, its [selected] state.
 */
@Composable
private fun CardFrame(
    modifier: Modifier,
    selected: Boolean?,
    watched: String?,
    listMode: Boolean,
    aspect: Float,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    picture: @Composable (Modifier) -> Unit,
    text: @Composable () -> Unit,
) {
    val theme = LocalSkyTheme.current
    val shape = RoundedCornerShape(SkyRadius.card)
    val frame = modifier.fillMaxWidth().clip(shape)
        .background(if (selected == true) theme.accent.copy(alpha = 0.22f) else theme.surface)
        .skyClickable(onClick, onLongClick, role = Role.Button, ringShape = shape)
        .semantics {
            if (selected != null) this.selected = selected
            if (watched != null) contentDescription = watched
        }
    if (listMode) {
        Row(frame.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            picture(Modifier.width(if (aspect < 1f) 72.dp else 128.dp).aspectRatio(aspect).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f)) { text() }
        }
    } else {
        Column(frame) {
            picture(Modifier.fillMaxWidth().aspectRatio(aspect))
            Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 9.dp)) { text() }
        }
    }
}

/**
 * One entry of a card's or a page's menu. [shortcut] (or else [hint]) shows on the right in the muted colour; a
 * [dividerBefore] item starts a new group; an item with [children] opens them in place of the menu.
 */
internal data class MenuAction(
    val label: String,
    val icon: SkyIcon? = null,
    val enabled: Boolean = true,
    val shortcut: String? = null,
    val hint: String? = null,
    val dividerBefore: Boolean = false,
    val children: List<MenuAction> = emptyList(),
    val action: () -> Unit = {},
)

/** A small menu that opens where it is placed, in the theme's menu colour. */
@Composable
internal fun SkyMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>) {
    val theme = LocalSkyTheme.current
    // The children of the item chosen last, shown in place of the menu with "‹ Back" first.
    var inside by remember(expanded) { mutableStateOf<MenuAction?>(null) }
    val parent = inside
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.background(theme.menu)) {
        @Composable
        fun row(item: MenuAction, onChoose: () -> Unit) {
            if (item.dividerBefore) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(theme.muted.copy(alpha = 0.3f)))
            val side = item.shortcut ?: item.hint
            DropdownMenuItem(
                text = { Text(item.label, color = if (item.enabled) theme.ink else theme.muted) },
                leadingIcon = item.icon?.let { icon -> { SkyIconView(icon, if (item.enabled) theme.accent else theme.muted, size = 18.dp) } },
                trailingIcon = side?.let { { Text(it, color = theme.muted, style = MaterialTheme.typography.labelMedium) } },
                enabled = item.enabled,
                onClick = onChoose,
            )
        }
        if (parent != null) row(MenuAction("‹ Back")) { inside = null }
        (parent?.children ?: actions).forEach { item ->
            row(item) {
                if (item.children.isNotEmpty()) {
                    inside = item
                } else {
                    onDismiss()
                    item.action()
                }
            }
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

/** Ctrl + mouse wheel over a grid: [onStep] gets +1 (wheel up, larger cards) or -1. The grid does not scroll meanwhile. */
internal fun Modifier.ctrlWheel(onStep: (Int) -> Unit): Modifier = composed {
    // The handler outlives recompositions, so it must call the latest lambda, not the first one's page.
    val latest by rememberUpdatedState(onStep)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll || !event.keyboardModifiers.isCtrlPressed) continue
                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) latest(if (dy < 0f) 1 else -1)
                event.changes.forEach { it.consume() }
            }
        }
    }
}
