package com.deepak.videolibrary.ui

import com.deepak.videolibrary.data.VideoEntity
import java.util.Locale

/** Presentation choices shared by the toolbar and Settings. Existing preference keys stay valid. */
object LibraryLayout {
    val presets = listOf("2", "3", "4", "5")

    // Migrate old columns × rows choices without losing the user's column count.
    fun normalize(preset: String?, fallback: String = "3"): String =
        preset?.substringBefore('×')?.takeIf { it in presets } ?: fallback

    fun columns(preset: String): Int = normalize(preset).toInt()

    /** Narrowest card a grid may use, so even 5 columns fit on a phone. */
    const val MIN_TILE_DP = 64

    /**
     * The number of columns you chose in Settings > App layout. It is only reduced when the screen is too narrow for
     * cards of [MIN_TILE_DP] with a 12dp gap (a phone used to be held to 2 columns, so the choice seemed to do nothing).
     */
    fun fittedColumns(preset: String, availableWidthDp: Int): Int =
        minOf(columns(preset), ((availableWidthDp.coerceAtLeast(1) + 12) / (MIN_TILE_DP + 12)).coerceAtLeast(1))
}

enum class VideoSort(val label: String) {
    NAME("Name"),
    NEWEST("Recently added"),
    MODIFIED("Last modified"),
    SIZE("File size"),
    ;

    fun arrange(videos: List<VideoEntity>, descending: Boolean): List<VideoEntity> {
        val comparator = when (this) {
            NAME -> compareBy<VideoEntity> { it.name.lowercase(Locale.ROOT) }
            NEWEST -> compareBy<VideoEntity> { it.addedAt }
            MODIFIED -> compareBy<VideoEntity> { it.modified }
            SIZE -> compareBy<VideoEntity> { it.size }
        }.thenBy { it.relativePath }
        return videos.sortedWith(if (descending) comparator.reversed() else comparator)
    }
}

fun canContinue(position: Long, duration: Long, hidden: Boolean): Boolean =
    !hidden && position > 15_000L && (duration <= 0L || position < duration - 10_000L)

/** Original-folder display only; physical paths remain the identity for every action. */
fun originalFolderLabel(path: String): String {
    val leaf = path.trimEnd('/').substringAfterLast('/')
    val end = leaf.indexOfFirst { it == '-' || it == ',' }
    return (if (end >= 0)leaf.substring(0, end) else leaf).trim().ifEmpty { leaf }
}
