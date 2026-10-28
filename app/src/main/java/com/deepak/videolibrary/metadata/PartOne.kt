package com.deepak.videolibrary.metadata

/**
 * When a folder holds several parts of one thing, its poster should come from the first part: file names with
 * "pt1", "pt.1", "pt 1", "part1", "part 1" or "part one" (any case, also 01) go ahead of the others.
 */
object PartOne {
    private val FIRST = Regex("(?<![A-Za-z])(?:pt|part)[ ._-]*(?:0*1|one)(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)

    fun isFirstPart(fileName: String): Boolean = FIRST.containsMatchIn(fileName)

    /** [items] with the first parts moved to the front; everything else keeps its order after them. */
    fun <T> firstPartsFirst(items: List<T>, name: (T) -> String): List<T> {
        val (first, rest) = items.partition { isFirstPart(name(it)) }
        return first + rest
    }
}
