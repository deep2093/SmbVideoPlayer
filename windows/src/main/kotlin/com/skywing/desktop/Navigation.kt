package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skywing.desktop.ui.adaptive.LocalAdaptive
import com.skywing.desktop.ui.theme.SkyRadius

/** A section of the main navigation (M3 design brief 5.2). [short] is the label under the icon on the narrow rail. */
internal class Section(val id: String, val label: String, val short: String, val icon: SkyIcon)

internal const val NOW_PLAYING = "Now Playing"
internal const val SETTINGS = "Settings"

/** What screen readers hear for the Now playing entry; the title is on screen beside it. */
private const val NOW_PLAYING_NAME = "Now playing"

internal val SECTIONS = listOf(
    Section("Home", "Home", "Home", SkyIcon.Home),
    Section("Folders", "Folders", "Folders", SkyIcon.Folder),
    Section("Videos", "Videos", "Videos", SkyIcon.VideoLibrary),
    // The narrow rail and the top tabs say "Drives"; screen readers still hear the full name.
    Section("Files", "Files and drives", "Drives", SkyIcon.Cloud),
)

/** What the navigation shows of the loaded video. */
internal class NowPlaying(val title: String, val paused: Boolean, val progress: Float)

/**
 * One entry of the navigation: a tab with its selected state. Wide: a 20 dp icon beside a Body label, radius 8;
 * narrow: the icon above a Caption label. Selected: the accent at 16 %, accent icon. Hover: layer 3. A [name] other
 * than the label is what screen readers hear.
 */
@Composable
internal fun NavRow(
    label: String,
    icon: SkyIcon,
    selected: Boolean,
    name: String = label,
    narrow: Boolean = false,
    onClick: () -> Unit,
) {
    val c = LocalSkyTheme.current.colors
    val adaptive = LocalAdaptive.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill = when {
        selected -> c.accentSubtle
        hovered -> c.layer3
        else -> Color.Transparent
    }
    val labelSemantics = if (name != label) Modifier.clearAndSetSemantics {} else Modifier
    val row = Modifier.fillMaxWidth().clip(RoundedCornerShape(SkyRadius.control)).background(fill)
        .hoverable(hover)
        .skyClickable(onClick, role = Role.Tab)
        .then(if (name != label) Modifier.accessibleName(name) else Modifier)
        .semantics { this.selected = selected }
    if (narrow) {
        Column(
            row.heightIn(min = 56.dp).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            SkyIconView(icon, if (selected) c.accent else c.textSecondary, size = 20.dp)
            Text(label, color = if (selected) c.text else c.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = labelSemantics)
        }
    } else {
        Row(
            row.heightIn(min = adaptive.rowHeight).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SkyIconView(icon, if (selected) c.accent else c.textSecondary, size = 20.dp)
            Text(label, color = if (selected) c.text else c.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = labelSemantics)
        }
    }
}

/**
 * The rail (M3 design brief 5.2) on layer 1 with a 1 px edge on its right: 240 dp with full labels and a Now playing
 * card, or 72 dp with icons over short labels and Now playing as an entry with a progress ring. Every entry is a tab
 * in one group, so one of them is always the selected one. [footer] goes at the very bottom (the actor page's pin).
 */
@Composable
internal fun NavRail(
    wide: Boolean,
    selected: String,
    nowPlaying: NowPlaying?,
    onSelect: (String) -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    val c = LocalSkyTheme.current.colors
    Column(
        modifier.width(if (wide) 240.dp else 72.dp).fillMaxHeight().background(c.layer1)
            .drawBehind { drawLine(c.stroke, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) }
            .padding(horizontal = if (wide) 12.dp else 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = if (wide) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (wide) Arrangement.spacedBy(10.dp) else Arrangement.Center,
        ) {
            SkyMark(28.dp)
            if (wide) Text("SkyWing", color = c.text, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        SECTIONS.forEach { section ->
            NavRow(if (wide) section.label else section.short, section.icon, selected == section.id, name = section.label, narrow = !wide) {
                onSelect(section.id)
            }
        }
        Spacer(Modifier.weight(1f))
        if (nowPlaying != null) {
            if (wide) NowPlayingCard(nowPlaying, selected == NOW_PLAYING, { onSelect(NOW_PLAYING) }, onTogglePause)
            else NowPlayingEntry(nowPlaying, selected == NOW_PLAYING) { onSelect(NOW_PLAYING) }
        }
        NavRow(SETTINGS, SkyIcon.Settings, selected == SETTINGS, narrow = !wide) { onSelect(SETTINGS) }
        footer?.invoke()
    }
}

/**
 * The pin at the bottom of the rail on an actor page (spec 5.3): on, the rail keeps its 240 dp there; off, it folds to
 * its 72 dp icons to give the videos room. [wide] is the rail's width now; the choice is kept with the views.
 */
@Composable
internal fun KeepMenuOpenPin(wide: Boolean, on: Boolean, onToggle: () -> Unit) {
    val c = LocalSkyTheme.current.colors
    val tint = if (on) c.accent else c.textSecondary
    val state = Modifier.semantics { toggleableState = ToggleableState(on) }
    if (wide) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = LocalAdaptive.current.rowHeight).clip(RoundedCornerShape(SkyRadius.control))
                .background(if (on) c.accentSubtle else Color.Transparent)
                .skyClickable(onToggle, role = Role.Checkbox).then(state)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SkyIconView(SkyIcon.Pin, tint, size = 20.dp)
            Text(KEEP_MENU_OPEN, color = if (on) c.text else c.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    } else {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SkyIconButton(SkyIcon.Pin, KEEP_MENU_OPEN, onToggle, modifier = state, tint = tint)
        }
    }
}

private const val KEEP_MENU_OPEN = "Keep menu open"

/** The wide rail's Now playing card: the title on one line, a 2 dp accent progress line, and play or pause. */
@Composable
private fun NowPlayingCard(playing: NowPlaying, selected: Boolean, onOpen: () -> Unit, onTogglePause: () -> Unit) {
    val c = LocalSkyTheme.current.colors
    val shape = RoundedCornerShape(SkyRadius.control)
    Text("Now playing", color = c.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) c.accentSubtle else c.layer2)
            .skyClickable(onOpen, role = Role.Tab).accessibleName(NOW_PLAYING_NAME)
            .semantics { this.selected = selected }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(playing.title, color = c.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {})
            Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.control)) {
                Box(Modifier.fillMaxWidth(playing.progress.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
            }
        }
        SkyIconButton(if (playing.paused) SkyIcon.Play else SkyIcon.Pause, if (playing.paused) "Play" else "Pause", onTogglePause, box = 36.dp, glyph = 20.dp)
    }
}

/** The narrow rail's Now playing entry: the play icon inside a ring that fills with the progress (assumption A1). */
@Composable
private fun NowPlayingEntry(playing: NowPlaying, selected: Boolean, onOpen: () -> Unit) {
    val c = LocalSkyTheme.current.colors
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(SkyRadius.control))
            .background(if (selected) c.accentSubtle else Color.Transparent)
            .skyClickable(onOpen, role = Role.Tab).accessibleName(NOW_PLAYING_NAME)
            .semantics { this.selected = selected }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(28.dp).drawBehind {
                val ring = Stroke(2.dp.toPx())
                drawCircle(c.control, style = ring)
                drawArc(c.accent, -90f, 360f * playing.progress.coerceIn(0f, 1f), false, style = ring)
            },
            contentAlignment = Alignment.Center,
        ) {
            SkyIconView(if (playing.paused) SkyIcon.Pause else SkyIcon.Play, c.accent, size = 16.dp)
        }
        Text("Playing", color = if (selected) c.text else c.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * The Compact navigation (M3 design brief 5.2): a 48 dp row of tabs at the top of the content, Now playing as a fifth
 * while a video is loaded, and Settings at the right as an icon tab of the same group.
 */
@Composable
internal fun TopTabs(selected: String, nowPlaying: NowPlaying?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSkyTheme.current.colors
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SECTIONS.forEach { section ->
            SkyChip(section.short, selected == section.id, { onSelect(section.id) }, name = section.label)
        }
        if (nowPlaying != null) SkyChip("Playing", selected == NOW_PLAYING, { onSelect(NOW_PLAYING) }, name = NOW_PLAYING_NAME)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(LocalAdaptive.current.minTarget).clip(RoundedCornerShape(SkyRadius.control))
                .background(if (selected == SETTINGS) c.accentSubtle else Color.Transparent)
                .skyClickable({ onSelect(SETTINGS) }, role = Role.Tab).accessibleName(SETTINGS)
                .semantics { this.selected = selected == SETTINGS },
            contentAlignment = Alignment.Center,
        ) {
            SkyIconView(SkyIcon.Settings, if (selected == SETTINGS) c.accent else c.textSecondary, size = 20.dp)
        }
    }
}
