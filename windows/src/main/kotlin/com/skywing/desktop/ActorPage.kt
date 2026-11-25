package com.skywing.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.skywing.desktop.ui.theme.SerifNames
import com.skywing.desktop.ui.theme.SkyMotion
import com.skywing.desktop.ui.theme.SkyRadius
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The actor page (spec 5.3, Header layout): an actor's own page, opened from an actor card. The actor list on the left
 * from 900 dp, a header with the photo, the name, Resume, Play all, Shuffle and the actor menu that folds into a slim bar
 * as the videos scroll, a sort bar that stays above the grid, and the actor's videos. The words and rules are here and
 * are tested; FoldersScreen opens it for a Virtual folder's name subfolder and a Custom folder's name group.
 */

/** One actor of a page: [pageKey] is its [actorKey] (View, the new dot and "last opened"), [photoKey] its photo. */
internal data class ActorRef(
    val identity: String,
    val title: String,
    val photoKey: String,
    val pageKey: String,
    val videos: List<WindowsMediaEntry>,
)

/** How the actor page sorts its videos (the same on every actor). */
internal enum class VideoSort { NEWEST, NAME, LONGEST }

/** The sort bar's words, in its order. */
private val SORTS = listOf(VideoSort.NEWEST to "Newest", VideoSort.NAME to "A–Z", VideoSort.LONGEST to "Longest")

/** What the header's first button does: go on with a half-watched video, else play the newest one. */
internal sealed interface ResumeChoice {
    data class Resume(val entry: WindowsMediaEntry, val position: Double, val left: Double) : ResumeChoice
    data class Newest(val entry: WindowsMediaEntry) : ResumeChoice
    data object None : ResumeChoice
}

/**
 * The video of [videos] most recently left half watched ([resumePosition]'s rule, the latest `updated` wins), else the
 * newest by `modified`. History of other videos is not looked at.
 */
internal fun pickResume(videos: List<WindowsMediaEntry>, history: Map<String, WindowsWatchRecord>): ResumeChoice {
    val half = videos.mapNotNull { video ->
        resumePosition(history, video)?.let { at -> Triple(video, at, history.getValue(video.path.lowercase(Locale.ROOT))) }
    }.maxByOrNull { it.third.updated }
    return when {
        half != null -> ResumeChoice.Resume(half.first, half.second, half.third.duration - half.second)
        videos.isEmpty() -> ResumeChoice.None
        else -> ResumeChoice.Newest(videos.maxBy { it.modified })
    }
}

/** NEWEST by `modified`, newest first; NAME by title, ignoring case; LONGEST longest first, unknown lengths last. */
internal fun sortVideos(
    videos: List<WindowsMediaEntry>,
    sort: VideoSort,
    lengthOf: (WindowsMediaEntry) -> Double?,
): List<WindowsMediaEntry> = when (sort) {
    VideoSort.NEWEST -> videos.sortedByDescending { it.modified }
    // Each title once: the file behind an entry is worked out again on every call.
    VideoSort.NAME -> videos.map { it to it.title() }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second }).map { it.first }
    VideoSort.LONGEST -> videos.map { it to lengthOf(it)?.takeIf { length -> length > 0 } }
        .sortedWith(compareBy<Pair<WindowsMediaEntry, Double?>> { it.second == null }.thenByDescending { it.second ?: 0.0 })
        .map { it.first }
}

/**
 * The grid's list: [sorted] with the "Search here" [query] (in the file name, ignoring case) and Hide watched applied
 * ([watched] holds lower-case paths). Play all and Shuffle play it, in the header and in ⋯ alike.
 */
internal fun actorPlayList(sorted: List<WindowsMediaEntry>, query: String, hideWatched: Boolean, watched: Set<String>): List<WindowsMediaEntry> =
    sorted.filter { video ->
        (query.isEmpty() || video.file.name.contains(query, ignoreCase = true)) &&
            !(hideWatched && video.path.lowercase(Locale.ROOT) in watched)
    }

/** The video after [current] in [sorted]; the first one when [current] is null or not in the list; null after the last. */
internal fun upNext(sorted: List<WindowsMediaEntry>, current: WindowsMediaEntry?): WindowsMediaEntry? {
    val at = if (current == null) -1 else sorted.indexOfFirst { it.path == current.path }
    return sorted.getOrNull(at + 1)
}

internal fun videosText(n: Int) = if (n == 1) "1 video" else "$n videos"

/** "24 videos · 9 watched · 2 new", leaving out the parts that are 0. */
internal fun actorLine(total: Int, watched: Int, new: Int): String = listOfNotNull(
    videosText(total),
    if (watched > 0) "$watched watched" else null,
    if (new > 0) "$new new" else null,
).joinToString(" · ")

/** "23 min left", "1 h 5 min left", "under a minute left". */
internal fun timeLeftText(seconds: Double): String {
    if (!(seconds >= 60)) return "under a minute left"
    val minutes = (seconds / 60).roundToInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min left"
        rest == 0 -> "$hours h left"
        else -> "$hours h $rest min left"
    }
}

/** How far the header is folded, from the grid's scroll: 0 is open, 1 is the slim bar, reached after [rangePx]. */
internal fun headerFold(firstIndex: Int, offsetPx: Int, rangePx: Int): Float = when {
    firstIndex > 0 -> 1f
    rangePx <= 0 -> if (offsetPx > 0) 1f else 0f
    else -> (offsetPx.toFloat() / rangePx).coerceIn(0f, 1f)
}

/** The actors whose name holds [query], ignoring case; all of them for a blank query. */
internal fun filterActors(actors: List<ActorRef>, query: String): List<ActorRef> {
    val wanted = query.trim()
    return if (wanted.isEmpty()) actors else actors.filter { it.title.contains(wanted, ignoreCase = true) }
}

/** The actors before and after [identity] in [actors] (the ‹ › buttons), null at the ends. */
internal fun neighbours(actors: List<ActorRef>, identity: String): Pair<ActorRef?, ActorRef?> {
    val at = actors.indexOfFirst { it.identity == identity }
    return if (at < 0) null to null else actors.getOrNull(at - 1) to actors.getOrNull(at + 1)
}

/**
 * Where the page of [current] goes after it was renamed and saved as [renamed]: that identity, so the page stays on its
 * actor. Null when there is nothing to follow (the save failed, or only the case of letters changed) or when the new name
 * joins it to another actor of [identities]: that actor's page is gone and the grid shows, as when it is hidden.
 */
internal fun followRename(current: String, renamed: String?, identities: Collection<String>): String? =
    renamed?.takeIf { it != current && it !in identities }

/** The key the photo moves under between the actor card and this page's header. */
internal fun actorPhotoKey(pageKey: String) = "actor-photo:$pageKey"

/** How long the photo takes to move between the actor card and the page header (spec 5.4). */
private const val PHOTO_MOVE_MS = 300

/** Where the photo moves: given inside [ActorSwitch] while Windows animations are on, otherwise null. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal class PhotoMove(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

internal val LocalPhotoMove = compositionLocalOf<PhotoMove?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
private val PHOTO_BOUNDS = BoundsTransform { _, _ -> tween(PHOTO_MOVE_MS) }

/** The photo under [key] ([actorPhotoKey]) moves between the actor card and the page header; nothing without [LocalPhotoMove]. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.movingPhoto(key: String): Modifier {
    val move = LocalPhotoMove.current ?: return this
    return with(move.shared) { this@movingPhoto.sharedElement(rememberSharedContentState(key), move.visibility, boundsTransform = PHOTO_BOUNDS) }
}

/**
 * Switches between a folder's grid ([group] null) and the page of the actor [group] (spec 5.4). Between the grid and a
 * page the photo moves and the pages cross-fade in 0.3 s; from one actor to another, and with Windows animations off
 * ([animate] false: no photo move either), the switch is at once. A page shown again (coming back from a video) is
 * simply there. [content] is told what it shows and whether it is the one coming in: only that one takes a focus key,
 * so the one going out cannot clear it first.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ActorSwitch(group: String?, animate: Boolean = SkyMotion.animations, content: @Composable (shown: String?, entering: Boolean) -> Unit) {
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        AnimatedContent(
            group,
            Modifier.fillMaxSize(),
            transitionSpec = {
                if (animate && (initialState == null) != (targetState == null)) {
                    fadeIn(tween(PHOTO_MOVE_MS)) togetherWith fadeOut(tween(PHOTO_MOVE_MS)) using null
                } else {
                    fadeIn(snap()) togetherWith fadeOut(snap()) using null
                }
            },
            label = "actor page",
            // One actor's page and the next are the same page with another actor: its state stays and nothing moves.
            contentKey = { it != null },
        ) { shown ->
            val shared = this@SharedTransitionLayout
            val move = remember(animate) { if (animate) PhotoMove(shared, this) else null }
            CompositionLocalProvider(LocalPhotoMove provides move) {
                content(shown, transition.targetState == EnterExitState.Visible)
            }
        }
    }
}

/** What the page shows of each of its videos, by lower-case path: lengths when known, watched and new. */
internal class ActorMarks(val lengths: Map<String, Double>, val watched: Set<String>, val new: Set<String>) {
    fun badges(entry: WindowsMediaEntry): VideoBadges {
        val key = entry.path.lowercase(Locale.ROOT)
        return VideoBadges(lengths[key], key in watched, key in new)
    }

    companion object {
        val EMPTY = ActorMarks(emptyMap(), emptySet(), emptySet())
    }
}

/** The marks of [videos]. [lengthOf] may read the lengths file, so this runs off the screen thread. */
internal fun actorMarks(videos: List<WindowsMediaEntry>, marks: LibraryMarks, now: Long, lengthOf: (WindowsMediaEntry) -> Double?): ActorMarks {
    val lengths = HashMap<String, Double>()
    val watched = HashSet<String>()
    val new = HashSet<String>()
    for (video in videos) {
        val key = video.path.lowercase(Locale.ROOT)
        lengthOf(video)?.takeIf { it > 0 }?.let { lengths[key] = it }
        if (marks.isWatched(video)) watched += key
        if (marks.isNew(video, now)) new += key
    }
    return ActorMarks(lengths, watched, new)
}

private val LIST_FROM = 900.dp
private val LIST_WIDTH = 260.dp
private val HEADER_OPEN = 184.dp
private val HEADER_SLIM = 60.dp

/**
 * The page of [actor], one of [actors] (the actors of its folder). [onOpenActor] opens another by identity; [menuOf] is
 * the actor menu of an actor, true for this page's own actor (no Open there), with the list its Play all and Shuffle
 * play; it is built only when a menu opens or a key arrives. [add] is the folder kind's "add more" button, at the end of
 * the sort bar. [focusKey] is the video card to give the keyboard focus to (coming back from playing it).
 */
@Composable
internal fun ActorPage(
    actor: ActorRef,
    actors: List<ActorRef>,
    f: Folders,
    onOpenActor: (String) -> Unit,
    onBack: () -> Unit,
    menuOf: (ActorRef, Boolean, List<WindowsMediaEntry>) -> List<MenuAction>,
    add: MenuAction? = null,
    focusKey: String? = null,
) {
    val hub = f.hub
    val page = actor.pageKey
    // Opening the page clears the actor's new dot; the write runs off the screen thread (Main).
    LaunchedEffect(page) { f.onOpenedActor(page) }
    val alphabetical = remember(actors) { actors.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) }

    // Lengths, watched and new, read off the screen thread; a page shown again starts with what it showed last.
    val marks = f.marks
    val lengthsRevision = ThumbnailStore.lengths.revision.value
    val info = key(page) {
        produceState(hub.lastResult<ActorMarks>("actor-marks:$page") ?: ActorMarks.EMPTY, actor.videos, marks, lengthsRevision) {
            value = withContext(Dispatchers.IO) {
                actorMarks(actor.videos, marks, System.currentTimeMillis()) {
                    runCatching { ThumbnailStore.lengths.get(ThumbnailStore.keyOf(it.path, it.modified)) }.getOrNull()
                }
            }.also { hub.keepResult("actor-marks:$page", it) }
        }.value
    }
    val sort = hub.actorSort
    val sorted = remember(actor.videos, sort, info) { sortVideos(actor.videos, sort) { info.lengths[it.path.lowercase(Locale.ROOT)] } }
    val query = f.query
    val hideWatched = hub.hideWatched
    // What the grid shows, and what every Play all and Shuffle on this page plays.
    val shown = remember(sorted, query, hideWatched, info) { actorPlayList(sorted, query, hideWatched, info.watched) }
    val resume = remember(actor.videos, f.history) { pickResume(actor.videos, f.history) }
    // The videos as shown, in the current sort; the whole sorted list when search or Hide watched hides the one to start.
    fun queueFor(entry: WindowsMediaEntry) = (if (shown.any { it.path == entry.path }) shown else sorted).map { it.file }
    val playResume: () -> Unit = {
        when (resume) {
            is ResumeChoice.Resume -> f.onResume(resume.entry.file, resume.position, queueFor(resume.entry), true)
            is ResumeChoice.Newest -> f.onResume(resume.entry.file, 0.0, queueFor(resume.entry), true)
            ResumeChoice.None -> Unit
        }
    }
    val resumeLabel = (resume as? ResumeChoice.Resume)?.let { "Resume · " + timeLeftText(it.left) } ?: "Play newest"
    val searchFocus = remember { FocusRequester() }
    val grid = hub.grid(page)
    val studio = LocalLibraryViews.current.data.actorLayout == ActorLayout.STUDIO

    BoxWithConstraints(Modifier.fillMaxSize().searchKey(searchFocus)) {
        val showList = maxWidth >= LIST_FROM
        val showCards = maxWidth >= LIST_FROM
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showList) ActorList(alphabetical, actor, shown, onOpenActor, menuOf, Modifier.width(LIST_WIDTH).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val (previous, next) = if (showList) null to null else neighbours(alphabetical, actor.identity)
                if (studio) ActorStudioHeader(
                    actor = actor,
                    line = actorLine(actor.videos.size, info.watched.size, info.new.size),
                    resume = resume,
                    resumeLabel = resumeLabel,
                    upNext = upNext(shown, (resume as? ResumeChoice.Resume)?.entry),
                    cards = showCards,
                    canPlay = shown.isNotEmpty(),
                    onResume = playResume,
                    onPlayUpNext = { entry -> f.onResume(entry.file, 0.0, shown.map { it.file }, true) },
                    onPlayAll = { f.play(shown) },
                    onShuffle = { f.play(shown.shuffled()) },
                    menu = { menuOf(actor, true, shown) },
                    onBack = onBack,
                    arrows = !showList,
                    previous = previous,
                    next = next,
                    onOpenActor = onOpenActor,
                ) else ActorHeader(
                    actor = actor,
                    line = actorLine(actor.videos.size, info.watched.size, info.new.size),
                    grid = grid,
                    resumeLabel = resumeLabel,
                    canResume = resume != ResumeChoice.None,
                    canPlay = shown.isNotEmpty(),
                    onResume = playResume,
                    onPlayAll = { f.play(shown) },
                    onShuffle = { f.play(shown.shuffled()) },
                    // Exactly the actor menu (spec 5.6); its Play all and Shuffle play the grid's list, the same as the buttons.
                    menu = { menuOf(actor, true, shown) },
                    onBack = onBack,
                    arrows = !showList,
                    previous = previous,
                    next = next,
                    onOpenActor = onOpenActor,
                )
                if (hub.folderPage == page) VideoSelectionBar(f, page, shown) else SortBar(hub, page, searchFocus, add)
                TileGrid(
                    f.videoTiles(shown, page) { info.badges(it) }, grid, f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
                    emptyText = "Nothing matches.",
                    emptyAction = if (query.isNotEmpty()) MenuAction("Clear search", SkyIcon.Close) { hub.query = "" } else null,
                    footer = videosText(shown.size),
                    letters = sort == VideoSort.NAME,
                    focusKey = focusKey,
                    onFocused = { hub.focusTile = null },
                )
            }
        }
    }
}

/**
 * The header: Back, the 3:4 photo, the name and its line, then Resume (or Play newest), Play all, Shuffle and ⋯. As the
 * grid scrolls it folds into a slim bar with the photo, the name and Resume; with Windows animations off it follows the
 * scroll at once. Below 900 dp ‹ › beside the name go to the previous and next actor.
 */
@Composable
private fun ActorHeader(
    actor: ActorRef,
    line: String,
    grid: LazyGridState,
    resumeLabel: String,
    canResume: Boolean,
    canPlay: Boolean,
    onResume: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    menu: () -> List<MenuAction>,
    onBack: () -> Unit,
    arrows: Boolean,
    previous: ActorRef?,
    next: ActorRef?,
    onOpenActor: (String) -> Unit,
) {
    val theme = LocalSkyTheme.current
    val range = with(LocalDensity.current) { (HEADER_OPEN - HEADER_SLIM).roundToPx() }
    val scrolled by remember(grid, range) { derivedStateOf { headerFold(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, range) } }
    val fold by animateFloatAsState(scrolled, SkyMotion.fast(), label = "header fold")
    val open = fold < 0.5f
    Row(
        Modifier.fillMaxWidth().height(lerp(HEADER_OPEN, HEADER_SLIM, fold)).clipToBounds(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SkyIconButton(SkyIcon.Back, "Back", onBack)
        Box(
            Modifier.movingPhoto(actorPhotoKey(actor.pageKey)).height(lerp(HEADER_OPEN - 16.dp, HEADER_SLIM - 12.dp, fold)).aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(SkyRadius.card)).testTag(actorPhotoKey(actor.pageKey)),
        ) {
            ActorPicture(actor.photoKey, actorInitials(actor.title), tall = true, listMode = !open)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    actor.title, color = theme.ink, fontFamily = SerifNames, fontWeight = FontWeight.SemiBold,
                    fontSize = lerp(30.sp, 19.sp, fold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (arrows) {
                    SkyIconButton(SkyIcon.ChevronLeft, "Previous actor", { previous?.let { onOpenActor(it.identity) } }, enabled = previous != null)
                    SkyIconButton(SkyIcon.ChevronRight, "Next actor", { next?.let { onOpenActor(it.identity) } }, enabled = next != null)
                }
            }
            if (open) {
                Column(Modifier.graphicsLayer { alpha = 1f - fold * 2f }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(line, color = theme.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // One line that scrolls sideways on a narrow page, so the open header (a fixed height) never cuts a button off.
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkyButton(resumeLabel, onResume, enabled = canResume, icon = SkyIcon.Play)
                        SkyButton("Play all", onPlayAll, enabled = canPlay, primary = false, icon = SkyIcon.Play)
                        SkyButton("Shuffle", onShuffle, enabled = canPlay, primary = false, icon = SkyIcon.Queue)
                        LazyMenuButton("Actor options", menu)
                    }
                }
            }
        }
        if (!open) {
            Box(Modifier.graphicsLayer { alpha = (fold - 0.5f) * 2f }) {
                SkyButton(resumeLabel, onResume, enabled = canResume, icon = SkyIcon.Play)
            }
        }
    }
}

/** A ⋯ button whose menu is built only when it opens. */
@Composable
internal fun LazyMenuButton(description: String, items: () -> List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        SkyIconButton(SkyIcon.MoreVert, description, { open = true })
        SkyMenu(open, { open = false }, if (open) items() else emptyList())
    }
}

/** The bar that stays above the grid: Newest / A–Z / Longest, Hide watched, the page's search, View and [add]. */
@Composable
private fun SortBar(hub: HubState, page: String, searchFocus: FocusRequester, add: MenuAction?) {
    val theme = LocalSkyTheme.current
    // One Tab stop (spec 5.10): Tab goes on to the grid; the arrows move along the bar.
    OneTabStop {
        HeaderLine(Modifier.heightIn(min = 48.dp), gap = 12.dp, lead = {
            SkyChipBar(SORTS.map { it.second }, SORTS.indexOfFirst { it.first == hub.actorSort }, { hub.actorSort = SORTS[it].first })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SkySwitch(hub.hideWatched, { hub.hideWatched = it }, "Hide watched")
                Text("Hide watched", color = theme.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
            }
        }, trail = {
            SkyField(hub.query, { hub.query = it }, "Search here", Modifier.width(220.dp).focusRequester(searchFocus).escClears(hub.query) { hub.query = "" }, leading = SkyIcon.Search)
            ViewButton(page, actors = false, videos = true)
            if (add != null) SkyButton(add.label, add.action, primary = false, icon = add.icon)
        })
    }
}

/**
 * The actors of the folder, A–Z, with this page's actor marked. The search icon opens a field that filters as you type:
 * Enter opens the first match, Esc clears it, then closes it. Right-click, the menu key or Shift+F10 opens a row's menu;
 * the marked actor's menu plays [currentPlay] (the grid's list), every other actor its own videos.
 */
@Composable
private fun ActorList(
    actors: List<ActorRef>,
    current: ActorRef,
    currentPlay: List<WindowsMediaEntry>,
    onOpenActor: (String) -> Unit,
    menuOf: (ActorRef, Boolean, List<WindowsMediaEntry>) -> List<MenuAction>,
    modifier: Modifier,
) {
    val theme = LocalSkyTheme.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = remember(actors, query) { filterActors(actors, query) }
    val field = remember { FocusRequester() }
    val start = remember { actors.indexOfFirst { it.identity == current.identity }.coerceAtLeast(0) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    // One Tab stop (spec 5.10): Tab goes on past the list; the arrows move between its rows.
    OneTabStop(modifier) {
        Column(Modifier.fillMaxSize().skyPanel().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Actors", color = theme.ink, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                SkyIconButton(SkyIcon.Search, "Search actors", {
                    if (searching) query = ""
                    searching = !searching
                }, box = 36.dp, glyph = 18.dp)
            }
            if (searching) {
                SkyField(
                    query, { query = it }, "Search actors",
                    Modifier.fillMaxWidth().focusRequester(field).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                shown.firstOrNull()?.let { onOpenActor(it.identity) }
                                true
                            }
                            Key.Escape -> {
                                if (query.isNotEmpty()) query = "" else searching = false
                                true
                            }
                            else -> false
                        }
                    },
                    leading = SkyIcon.Search,
                )
                LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (shown.isEmpty()) item { EmptyNote("No actors found.") }
                    items(shown, key = { it.identity }) { ref ->
                        val here = ref.identity == current.identity
                        ActorRow(ref, here, { onOpenActor(ref.identity) }) { menuOf(ref, here, if (here) currentPlay else ref.videos) }
                    }
                }
                ListScrollbar(state, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/** One row of the actor list: a small photo (or the initials), the name and the video count. */
@Composable
private fun ActorRow(ref: ActorRef, here: Boolean, onOpen: () -> Unit, menu: () -> List<MenuAction>) {
    val theme = LocalSkyTheme.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(SkyRadius.control))
                .background(if (here) theme.colors.accentSubtle else Color.Transparent)
                .cardKeys(menu) { open = true }
                .skyClickable(onOpen, onLongClick = { open = true }, role = Role.Button)
                .semantics { selected = here }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(33.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp))) {
                ActorPicture(ref.photoKey, actorInitials(ref.title), tall = true, listMode = true)
            }
            Column(Modifier.weight(1f)) {
                Text(ref.title, color = theme.ink, fontFamily = SerifNames, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(videosText(ref.videos.size), color = theme.muted, fontSize = 11.sp, maxLines = 1)
            }
        }
        SkyMenu(open, { open = false }, if (open) menu() else emptyList())
    }
}
