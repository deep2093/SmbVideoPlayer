package com.deepak.videolibrary.ui

import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.smb.SmbEntry

fun matchesFilename(name: String, query: String): Boolean = name.contains(query.trim(), ignoreCase = true)

fun folderSearchVideos(videos: List<VideoEntity>, query: String): List<VideoEntity> =
    videos
        .filter { matchesFilename(it.name, query) || matchesFilename(it.customGroup ?: it.groupKey, query) }
        .distinctBy { it.relativePath }

fun isWithinFolder(parent: String, folder: String): Boolean =
    folder.isEmpty() || parent == folder || parent.startsWith("$folder/")

data class OriginalFolder(val path: String, val videos: List<VideoEntity>)

fun originalChildren(videos: List<VideoEntity>, folder: String): List<OriginalFolder> =
    videos
        .filter { isWithinFolder(it.parentDir, folder) && it.parentDir != folder }
        .groupBy {
            val remainder = if (folder.isEmpty()) it.parentDir else it.parentDir.removePrefix("$folder/")
            listOf(folder, remainder.substringBefore('/')).filter(String::isNotEmpty).joinToString("/")
        }.map { (path, items) -> OriginalFolder(path, items) }
        .sortedBy { it.path.lowercase(java.util.Locale.ROOT) }

data class ExplorerHit(val path: String, val entry: SmbEntry)

private val playableExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "wmv", "asf", "flv", "mpg", "mpeg", "3gp")
fun isPlayable(entry: SmbEntry): Boolean = !entry.isDirectory &&
    entry.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in playableExtensions

fun explorerVideo(hit: ExplorerHit, indexed: Map<String, VideoEntity>): VideoEntity = indexed[hit.path] ?: VideoEntity(
    relativePath = hit.path,
    name = hit.entry.name,
    parentDir = hit.path.substringBeforeLast('/', ""),
    size = hit.entry.size,
    modified = hit.entry.modified,
    groupKey = "",
    lastSeenSession = 0L,
    addedAt = 0L,
)


/**
 * File Explorer ordering. Folders always come first; within each group the chosen key decides
 * (NAME, DATE, SIZE or TYPE = file extension), with the name as a tie-breaker.
 */
fun sortExplorerHits(hits: List<ExplorerHit>, sort: String, descending: Boolean): List<ExplorerHit> {
    val byName: Comparator<ExplorerHit> = compareBy(String.CASE_INSENSITIVE_ORDER) { it.entry.name }
    val key: Comparator<ExplorerHit> = when (sort) {
        "DATE" -> compareBy<ExplorerHit> { it.entry.modified }.then(byName)
        "SIZE" -> compareBy<ExplorerHit> { it.entry.size }.then(byName)
        "TYPE" -> compareBy<ExplorerHit> { it.entry.name.substringAfterLast('.', "").lowercase() }.then(byName)
        else -> byName
    }
    val ordered = if (descending) key.reversed() else key
    return hits.sortedWith(compareBy<ExplorerHit> { !it.entry.isDirectory }.then(ordered))
}
