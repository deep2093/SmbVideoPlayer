package com.deepak.videolibrary.ui

import com.deepak.videolibrary.data.VideoEntity
import java.util.Locale

/** Presentation choices shared by the toolbar and Settings. Existing preference keys stay valid. */
object LibraryLayout {
    val presets = listOf("2×2", "2×3", "3×3", "3×4", "4×3", "4×4", "5×4", "5×5")
    fun dimensions(preset: String): Pair<Int, Int> {
        val safe = preset.takeIf { it in presets } ?: "3×3"
        return safe.substringBefore('×').toInt() to safe.substringAfter('×').toInt()
    }
}

enum class VideoSort(val label: String) {
    NAME("Name"), NEWEST("Recently added"), MODIFIED("Last modified"), SIZE("File size");

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
