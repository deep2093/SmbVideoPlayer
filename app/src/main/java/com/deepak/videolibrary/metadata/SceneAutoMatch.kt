package com.deepak.videolibrary.metadata

import java.util.Locale

/**
 * Decides when a found scene is applied without asking. A fingerprint (file hash) match is exact. A title match is
 * applied only when the scene's whole title appears in the file name and no other scene fits as well; anything less
 * clear is left for "Review suggested scene matches" so a wrong poster is never applied silently.
 */
internal object SceneAutoMatch {
    /** The match to apply, or null when the choice is not clear. [providerOrder] is the user's preferred provider first. */
    fun choose(matches: List<SceneMatch>, fileName: String, providerOrder: List<String>): SceneMatch? {
        val ordered = matches
            .distinctBy { it.provider + ":" + it.id }
            .sortedBy { providerOrder.indexOf(it.provider).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        if (ordered.isEmpty()) return null

        // Exact file hash: each provider that knows the file must name exactly one scene. Both providers naming
        // "their" copy of the same scene is the normal case; the preferred provider's copy is used.
        val exact = ordered.filter { it.method == "OSHASH" }
        if (exact.isNotEmpty()) {
            return exact.takeIf { list -> list.groupBy { it.provider }.values.all { it.size == 1 } }?.first()
        }

        // File-name search: whole title present in the file name, and only one such title.
        val fileWords = words(fileName.substringBeforeLast('.', fileName)).toSet()
        val fitting = ordered.filter { it.method == "TITLE" && titleFits(it.title, fileWords) }
        if (fitting.isEmpty()) return null
        return fitting.takeIf { list -> list.map { words(it.title).joinToString(" ") }.distinct().size == 1 }?.first()
    }

    private fun titleFits(title: String, fileWords: Set<String>): Boolean {
        val titleWords = words(title)
        if (titleWords.isEmpty()) return false
        if (titleWords.size < 2 && titleWords.joinToString("").length < 8) return false
        if (titleWords.joinToString("").length < 6) return false
        return fileWords.containsAll(titleWords)
    }

    private fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}
