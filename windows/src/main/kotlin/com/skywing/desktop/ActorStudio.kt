package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skywing.desktop.ui.theme.SerifNames
import com.skywing.desktop.ui.theme.SkyRadius

/*
 * The Studio layout of the actor page (spec 5.3): a small header that does not fold, with the photo, the name, the same
 * buttons as the Header layout, and under the name a Continue watching card and an Up next card from 900 dp of page width.
 */

private val STUDIO_PHOTO = 132.dp
private val CARD_WIDTH = 260.dp

/**
 * The Studio header. [resume] is what Resume does (the Continue card shows it); [upNext] the video after it in the grid's
 * list, played from the start by [onPlayUpNext]. [cards] is false below 900 dp, where only the buttons stay.
 */
@Composable
internal fun ActorStudioHeader(
    actor: ActorRef,
    line: String,
    resume: ResumeChoice,
    resumeLabel: String,
    upNext: WindowsMediaEntry?,
    cards: Boolean,
    canPlay: Boolean,
    onResume: () -> Unit,
    onPlayUpNext: (WindowsMediaEntry) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    menu: () -> List<MenuAction>,
    onBack: () -> Unit,
    /** Takes the focus when the page opens with no card to take it. */
    backFocus: FocusRequester,
    arrows: Boolean,
    previous: ActorRef?,
    next: ActorRef?,
    onOpenActor: (String) -> Unit,
    comeback: FocusComeback,
) {
    val theme = LocalSkyTheme.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SkyIconButton(SkyIcon.Back, "Back", onBack, Modifier.focusRequester(backFocus))
            // The same key as the Header layout's photo, so the move from the actor card finds it in either layout.
            Box(
                Modifier.movingPhoto(actorPhotoKey(actor.pageKey)).height(STUDIO_PHOTO).aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(SkyRadius.card)).testTag(actorPhotoKey(actor.pageKey)),
            ) {
                ActorPicture(actor.photoKey, actorInitials(actor.title), tall = true, listMode = false)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        actor.title, color = theme.ink, fontFamily = SerifNames, fontWeight = FontWeight.SemiBold,
                        fontSize = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (arrows) ActorArrows(previous, next, onOpenActor, comeback)
                }
                Text(line, color = theme.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SkyButton(resumeLabel, onResume, enabled = resume != ResumeChoice.None, icon = SkyIcon.Play)
                    SkyButton("Play all", onPlayAll, enabled = canPlay, primary = false, icon = SkyIcon.Play)
                    SkyButton("Shuffle", onShuffle, enabled = canPlay, primary = false, icon = SkyIcon.Queue)
                    LazyMenuButton("Actor options", menu)
                }
            }
        }
        if (cards && (resume != ResumeChoice.None || upNext != null)) {
            Row(Modifier.padding(start = 54.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when (resume) {
                    is ResumeChoice.Resume -> StudioCard(
                        "Continue watching", resume.entry, resume.entry.title(), timeLeftText(resume.left),
                        progress = (resume.position / (resume.position + resume.left)).toFloat(),
                    ) { SkyButton("Resume", onResume, icon = SkyIcon.Play) }
                    is ResumeChoice.Newest -> StudioCard("Continue watching", resume.entry, "Start with the newest", null, progress = null) {
                        SkyIconButton(SkyIcon.Play, "Play newest", onResume)
                    }
                    ResumeChoice.None -> Unit
                }
                if (upNext != null) StudioCard("Up next", upNext, upNext.title(), null, progress = null, onClick = { onPlayUpNext(upNext) }) {}
            }
        }
    }
}

/** One card of the Studio header: a 16:9 picture (with the progress bar and [action] on it), then [title] and [sub]. */
@Composable
private fun StudioCard(
    heading: String,
    entry: WindowsMediaEntry,
    title: String,
    sub: String?,
    progress: Float?,
    onClick: (() -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    val theme = LocalSkyTheme.current
    val shape = RoundedCornerShape(SkyRadius.card)
    Column(Modifier.width(CARD_WIDTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(heading, color = theme.muted, style = MaterialTheme.typography.labelMedium)
        val frame = Modifier.fillMaxWidth().clip(shape).background(theme.surface)
        Column(if (onClick != null) frame.skyClickable(onClick, role = Role.Button, ringShape = shape) else frame) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                VideoPicture(entry, false, Modifier.fillMaxSize())
                if (progress != null && progress > 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(theme.accent))
                }
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { action() }
            }
            Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 9.dp)) {
                Text(title, color = theme.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, color = theme.muted, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
