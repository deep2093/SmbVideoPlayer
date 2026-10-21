package com.deepak.videolibrary.scan

import java.util.Locale

/**
 * Builds the virtual-folder key from a file name: the first meaningful word of the cleaned name.
 * Display only: the original file name is never changed and nothing is written to the SMB drive.
 */
enum class GroupingRule {
    COMMA_OR_HYPHEN,
    COMMA_ONLY,
    FULL_NAME,

    /** Like [COMMA_OR_HYPHEN]; a name with neither keeps only its first two words ("Tom Cruise movie" -> "tom cruise"). */
    COMMA_HYPHEN_OR_TWO_WORDS,
}

object GroupKey {
    const val FALLBACK = "#"

    // A leading [tag] or (tag), e.g. "[1080p]" or "[WEB-DL]".
    private val leadingTag = Regex("^\\s*(\\[[^\\]]*\\]|\\([^)]*\\))")

    private val spaces = Regex("\\s+")
    private val trailingNumber = Regex("\\s+(?:part\\s*)?\\d+$", RegexOption.IGNORE_CASE)

    fun fromFileName(fileName: String, rule: GroupingRule = GroupingRule.COMMA_OR_HYPHEN): String {
        // A comma separates the grouping title from extra names/credits. Keep the whole
        // leading phrase (e.g. "Roger Marting, alex" -> "Roger Marting").
        var s = fileName.substringBeforeLast('.', fileName).trim()
        // Remove leading release tags before searching for the first delimiter.
        while (true) {
            val m = leadingTag.find(s) ?: break
            s = s.substring(m.range.last + 1).trimStart()
        }
        val splitsOnHyphen = rule == GroupingRule.COMMA_OR_HYPHEN || rule == GroupingRule.COMMA_HYPHEN_OR_TWO_WORDS
        val delimiter = s.indexOfFirst { (splitsOnHyphen && (it == ',' || it == '-')) || (rule == GroupingRule.COMMA_ONLY && it == ',') }
        if (delimiter >= 0) s = s.substring(0, delimiter).trim()
        s = s.trim().trimStart { !it.isLetterOrDigit() }
        // Keep the complete cleaned phrase rather than only the first word.
        s = s
            .replace(spaces, " ")
            .replace(trailingNumber, "")
            .trim(' ', '-', '_', '.', ',', ';', ':')
        if (rule == GroupingRule.COMMA_HYPHEN_OR_TWO_WORDS && delimiter < 0) {
            s = s.split(' ').filter(String::isNotBlank).take(2).joinToString(" ")
        }
        return if (s.isBlank()) FALLBACK else s.lowercase(Locale.ROOT)
    }
}
