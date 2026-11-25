package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.skywing.desktop.artwork.ArtworkRepository
import com.skywing.desktop.artwork.ArtworkSource
import com.skywing.desktop.ui.theme.SerifNames
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Actor cards: the generated name subfolders of a Virtual folder, and the name groups of a Custom folder, shown as
 * people (photo or initials, a serif name, a dot for new videos) instead of folders. The words and numbers are here
 * and are tested; FoldersScreen builds the cards and the menu is in CardMenus.kt.
 */

private fun count(n: Int, one: String) = if (n == 1) "$n $one" else "$n ${one}s"

/** The first letters of the first two words that start with a letter, in capitals, or "#" when there are none. */
internal fun actorInitials(name: String): String {
    val letters = name.trim().split(Regex("\\s+")).mapNotNull { word ->
        val first = word.codePoints().findFirst()
        if (first.isPresent && Character.isLetter(first.asInt)) String(Character.toChars(first.asInt)).uppercase(Locale.ROOT) else null
    }
    return letters.take(2).joinToString("").ifEmpty { "#" }
}

/** "12 videos · 3 new", else "12 videos · 8 watched" when some are watched, else "12 videos". */
internal fun actorDetail(videos: Int, new: Int, watched: Int): String = count(videos, "video") + when {
    new > 0 -> " · $new new"
    watched > 0 -> " · $watched watched"
    else -> ""
}

/** What a card shows besides its name: the detail line and whether the new dot is on. */
internal data class ActorInfo(val detail: String, val dot: Boolean)

/** The line and dot of an actor with [videos]. [key] is the actor's page key, which "last opened" is kept under. */
internal fun actorInfo(key: String, videos: List<WindowsMediaEntry>, marks: LibraryMarks, now: Long): ActorInfo =
    ActorInfo(actorDetail(videos.size, marks.newCount(videos, now), videos.count(marks::isWatched)), marks.dot(key, videos, now))

/** One actor in a grid: its videos and what it shows. */
internal class ActorEntry(val group: VirtualGroup, val info: ActorInfo)

/** The page key of an actor: where its dot, "last opened" and View are kept. [container] is "set:<id>" or "own:<id>". */
internal fun actorKey(container: String, identity: String) = "$container|$identity"

internal enum class ActorRemoval { HIDE, TAKE_OUT }

internal data class ActorMenuState(
    val folderName: String,
    val onActorPage: Boolean,
    val artworkOn: Boolean,
    val canRename: Boolean,
    /** The folders their videos are in, with the number of videos in each ([actorFolders]). */
    val folders: List<Pair<String, Int>>,
    val removal: ActorRemoval,
    /** False when there is nothing to play: on the actor page, everything is filtered out of its grid. */
    val canPlay: Boolean = true,
)

internal class ActorMenuActions(
    val open: () -> Unit,
    val playAll: () -> Unit,
    val shuffle: () -> Unit,
    val changePhoto: () -> Unit,
    val rename: () -> Unit,
    val showFolder: (String) -> Unit,
    val details: () -> Unit,
    val select: () -> Unit,
    val remove: () -> Unit,
)

private fun parentOf(path: String): String? {
    val end = path.trimEnd('\\', '/').lastIndexOfAny(charArrayOf('\\', '/'))
    if (end < 0) return null
    val parent = path.substring(0, end)
    return if (parent.endsWith(":")) "$parent\\" else parent.ifEmpty { null }
}

/** The folders [videos] are in with their number of videos, most videos first, at most 10. */
internal fun actorFolders(videos: List<WindowsMediaEntry>): List<Pair<String, Int>> {
    val counts = LinkedHashMap<String, Pair<String, Int>>()
    for (video in videos) {
        val parent = parentOf(video.path) ?: continue
        val key = parent.lowercase(Locale.ROOT)
        counts[key] = (counts[key]?.first ?: parent) to ((counts[key]?.second ?: 0) + 1)
    }
    return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }.thenBy { it.key })
        .take(10).map { it.value }
}

/** The label of a folder in the Show in File Explorer list: its own name. */
internal fun folderLeaf(path: String): String = path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }

/** What kind of card a selected key stands for. */
internal enum class TileKind { FOLDER, ACTOR, VIDEO }

/** The kind of card behind a selection key: "group:<name>" is an actor, "file:<path>" a video, anything else a folder. */
internal fun tileKindOf(key: String): TileKind = when {
    key.startsWith("group:") -> TileKind.ACTOR
    key.startsWith("file:") -> TileKind.VIDEO
    else -> TileKind.FOLDER
}

/** A page's cards in sections: Folders, Actors, then Videos, each under a heading when two or more sections have cards. */
internal fun sectioned(folders: List<Tile>, actors: List<Tile>, videos: List<Tile>): List<Tile> {
    val sections = listOf("folders" to "Folders", "actors" to "Actors", "videos" to "Videos").zip(listOf(folders, actors, videos))
        .filter { it.second.isNotEmpty() }
    if (sections.size < 2) return folders + actors + videos
    return sections.flatMap { (name, tiles) -> listOf<Tile>(LabelTile("label:" + name.first, name.second)) + tiles }
}

/**
 * The labels of the selection bar for what is selected. Only actors: Play all, Shuffle, and Hide ("Take out…" on a Custom
 * page, which really removes their videos from it). Anything else, alone or mixed: the actions that fit every kind,
 * with today's Take out.
 */
internal fun selectionActionsFor(kinds: Set<TileKind>, custom: Boolean): List<String> =
    if (kinds.isNotEmpty() && kinds.all { it == TileKind.ACTOR }) listOf("Play all", "Shuffle", if (custom) "Take out…" else "Hide")
    else listOf("Play", "Add to folder", "Add to virtual folder", "Mark as watched", "Mark as unwatched", "Take out")

/** The total length of [videos], or null when one is not known yet (or there are none). */
internal fun actorTotalLength(videos: List<WindowsMediaEntry>, lengthOf: (WindowsMediaEntry) -> Double?): Double? {
    if (videos.isEmpty()) return null
    var total = 0.0
    for (video in videos) total += lengthOf(video) ?: return null
    return total
}

/** The text of an actor's Details. */
internal fun actorDetails(name: String, videos: Int, size: Long, length: Double?, container: String): String = listOf(
    name,
    listOfNotNull(count(videos, "video"), formatSize(size).ifBlank { null }, length?.let(::formatLength)).joinToString(" · "),
    "Actor in $container",
    "A video listed in several subfolders is the same file, never a copy.",
).joinToString("\n")

/** The Details of an actor; the lengths are read off the screen thread (the store loads its file on first use). */
internal suspend fun actorDetailsText(name: String, videos: List<WindowsMediaEntry>, container: String): String {
    val length = withContext(Dispatchers.IO) {
        runCatching { actorTotalLength(videos) { ThumbnailStore.lengths.get(ThumbnailStore.keyOf(it.path, it.modified)) } }.getOrNull()
    }
    return actorDetails(name, videos.size, videos.sumOf { it.size }, length, container)
}

/** The sorts of an actor list: the folder sorts under their actor names. */
internal val ACTOR_SORTS = listOf("NAME" to "Name", "COUNT" to "Number of videos", "DATE" to "Latest by date", "SIZE" to "Total size")

/** The A-Z letters help once the actors fill more than about two screens (the cards in view stand for one screen). */
internal fun lettersWanted(actors: Int, visible: Int): Boolean = visible > 0 && actors > 2 * visible

/** Ctrl+F: go to the page's search box. */
internal fun isSearchKey(key: Key, ctrl: Boolean, alt: Boolean, shift: Boolean): Boolean = key == Key.F && ctrl && !alt && !shift

/**
 * Ctrl+F anywhere in the page (focus on a card, a button or the box itself) focuses its search box, where [focus] is set.
 * It does nothing, and the key goes on, while the box is not shown (the page is selecting).
 */
internal fun Modifier.searchKey(focus: FocusRequester): Modifier = onPreviewKeyEvent { event ->
    event.type == KeyEventType.KeyDown && isSearchKey(event.key, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed) &&
        runCatching { focus.requestFocus() }.isSuccess
}

/**
 * An actor's picture: the photo chosen or found for [coverKey], or their [initials] on a tinted tile of the same shape,
 * so nothing jumps when a photo arrives. A video collage is never made for a person.
 */
@Composable
internal fun ActorPicture(coverKey: String?, initials: String, tall: Boolean, listMode: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalSkyTheme.current
    val art = LocalCovers.current?.artwork
    val photo = if (art == null || coverKey == null) null else remember(coverKey, art.revision, tall) { photoOf(art, coverKey, tall) }
    Box(modifier.fillMaxSize()) {
        if (photo != null) {
            CoverImage(photo)
        } else {
            Box(Modifier.fillMaxSize().background(theme.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text(
                    initials, color = theme.ink.copy(alpha = 0.8f), fontFamily = SerifNames, fontWeight = FontWeight.SemiBold,
                    fontSize = if (listMode) 22.sp else 40.sp, maxLines = 1,
                )
            }
        }
    }
}

private fun photoOf(art: ArtworkRepository, coverKey: String, tall: Boolean): java.io.File? {
    val decision = art.decideFolder(coverKey)
    return if (decision.source == ArtworkSource.IMAGE) ArtworkRepository.forShape(art.imageFile(decision.imageName), tall) else null
}
