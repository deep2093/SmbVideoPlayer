package com.skywing.desktop.matching

import java.text.Normalizer
import java.util.Locale

/** Pure JVM matcher: no API traffic, disk writes, or Android dependencies. */
data class PerformerRecord(
    val provider: String,
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
)

enum class MatchKind { EXACT, ALIAS, COMPACT_REVIEW, FUZZY_REVIEW }

data class PerformerMatch(
    val performer: PerformerRecord,
    val kind: MatchKind,
    val matchedText: String,
    val automatic: Boolean,
)

/** Windows-first test implementation. This is NOT yet connected to library scanning. */
class PerformerMatcher(performers: List<PerformerRecord>) {
    private data class IndexedName(val performer: PerformerRecord, val value: String, val alias: Boolean) {
        val words: List<String> = tokenize(value)
        val compact: String = words.joinToString("")
    }
    private val names = performers.flatMap { p ->
        (listOf(p.name) + p.aliases).filter(String::isNotBlank).mapIndexed { i, n ->
            IndexedName(p, n, i > 0)
        }
    }.filter { it.words.isNotEmpty() }
    private val tokenIndex = names.groupBy { it.words.first() }

    fun match(fileName: String): List<PerformerMatch> {
        val tokens = tokenize(stripExtension(fileName))
        if (tokens.isEmpty()) return emptyList()
        val found = mutableListOf<PerformerMatch>()
        val claimed = mutableSetOf<Int>()
        // Prefer long exact names; never match a sub-token such as "mel" inside "melody".
        for (start in tokens.indices) {
            if (start in claimed) continue
            val options = tokenIndex[tokens[start]].orEmpty()
                .filter { item -> item.words.indices.all { i -> tokens.getOrNull(start + i) == item.words[i] } }
                .sortedWith(compareByDescending<IndexedName> { it.words.size }.thenBy { it.alias })
            val bestLength = options.firstOrNull()?.words?.size ?: continue
            val winners = options.filter { it.words.size == bestLength }
                .distinctBy { it.performer.provider to it.performer.id }
            // Shared names across distinct performer IDs are ambiguous, never auto-applied.
            if (winners.size == 1) {
                val name = winners.single()
                found += PerformerMatch(
                    name.performer,
                    if (name.alias) MatchKind.ALIAS else MatchKind.EXACT,
                    tokens.subList(start, start + bestLength).joinToString(" "),
                    automatic = true,
                )
                for (i in start until start + bestLength) claimed += i
            }
        }
        // Fuzzy/compact matches are *review suggestions only*. Never create a folder from them.
        val compactFile = tokens.joinToString("")
        val already = found.map { it.performer.provider to it.performer.id }.toSet()
        val review = names.asSequence().filter { (it.performer.provider to it.performer.id) !in already }
            .filter { it.compact.length >= 7 }
            .mapNotNull { item ->
                val direct = item.compact in compactFile
                // One typo allowed per multiword candidate; compared only with same-length token windows.
                val typo = !direct && item.words.size >= 2 && tokens.windowed(item.words.size)
                    .any { window -> editDistanceAtMostOne(window.joinToString(""), item.compact) }
                if (!direct && !typo) null
                else PerformerMatch(item.performer, if (direct) MatchKind.COMPACT_REVIEW else MatchKind.FUZZY_REVIEW, item.value, false)
            }
            .distinctBy { it.performer.provider to it.performer.id }
            .take(20).toList()
        return found + review
    }

    companion object {
        private val videoExtension = Regex("\\.(mp4|mkv|avi|mov|wmv|m4v|ts|flv|webm|mpg|mpeg)$", RegexOption.IGNORE_CASE)
        private val junk = setOf("1080p", "720p", "2160p", "4k", "hevc", "webdl", "webrip", "x264", "x265")
        fun stripExtension(text: String): String = text.substringAfterLast('/').substringAfterLast('\\').replace(videoExtension, "")
        fun tokenize(text: String): List<String> {
            val clean = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ")
            return clean.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it !in junk }
        }
        private fun editDistanceAtMostOne(a: String, b: String): Boolean {
            if (kotlin.math.abs(a.length - b.length) > 1) return false
            var i = 0
            var j = 0
            var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) { i++; j++; continue }
                if (++edits > 1) return false
                when {
                    a.length > b.length -> i++
                    b.length > a.length -> j++
                    else -> { i++; j++ }
                }
            }
            return edits + (a.length - i) + (b.length - j) <= 1
        }
    }
}
