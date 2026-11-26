package com.skywing.desktop

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import java.io.File
import java.util.Locale

/**
 * The three things playing a video changes: the resume list, the watched marks and the new marks. Every write raises
 * [revision] so the library marks are read again. A write that fails reaches the caller and raises nothing.
 */
internal class PlaybackRecords(
    val history: WindowsPlaybackHistoryStore,
    val watched: WindowsWatchedStore,
    val newVideos: NewVideoStore,
) {
    private val revisionState = mutableIntStateOf(0)
    val revision: State<Int> get() = revisionState

    /** Saves where the video is; the resume record first, so a failed watched mark never loses it. */
    fun save(file: File, position: Double, duration: Double) {
        history.saveProgress(file, position, duration)
        if (finishedAt(position, duration)) watched.mark(file)
        bump()
    }

    fun started(file: File) {
        newVideos.played(file.absolutePath)
        bump()
    }

    fun rename(old: String, new: String) {
        history.renamePath(old, new)
        watched.renamePath(old, new)
        newVideos.renamePath(old, new)
        bump()
    }

    fun setWatched(file: File, watched: Boolean) = setWatched(listOf(file), watched)

    /** Marks [files] watched or not: each store is read and written once, and the revision goes up once. */
    fun setWatched(files: Collection<File>, watched: Boolean) {
        if (files.isEmpty()) return
        if (watched) {
            this.watched.mark(files)
        } else {
            this.watched.unmark(files)
            history.forget(files)
        }
        bump()
    }

    fun openedActor(key: String) {
        newVideos.openedActor(key)
        bump()
    }

    fun arrived(paths: List<String>) {
        newVideos.add(paths)
        bump()
    }

    private fun bump() {
        revisionState.intValue++
    }
}

/** What the library pages mark on a video: watched to the end, or new. Keys are as the stores write them. */
internal data class LibraryMarks(
    val watched: Set<String> = emptySet(),
    val news: NewMarks = NewMarks(emptyMap(), emptyMap()),
) {
    fun isWatched(e: WindowsMediaEntry) = markKey(e.path) in watched

    fun isNew(e: WindowsMediaEntry, now: Long) = news.isNew(markKey(e.path), now)

    /** How many of [videos] are new and arrived after the actor page [key] was last opened. */
    fun newSince(key: String, videos: Collection<WindowsMediaEntry>, now: Long) = news.newSince(key, videos.map { markKey(it.path) }, now)
}

private fun markKey(path: String) = ShareHosts.canonical(path).lowercase(Locale.ROOT)
