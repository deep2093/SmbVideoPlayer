package com.deepak.videolibrary.artwork.online

import java.text.Normalizer

/**
 * Turns a folder or video label into a search term for a provider. Only the cleaned label ever leaves the
 * device: never a full path, a server name or an address.
 */
object NameCleaner {
    private val videoExtension = Regex("\\.(mp4|mkv|avi|mov|wmv|m4v|ts|flv|webm|mpg|mpeg)$", RegexOption.IGNORE_CASE)
    private val brackets = Regex("[\\[(\\{][^\\]\\)\\}]*[\\]\\)\\}]")
    private val releaseTag = Regex(
        "\\b(\\d{3,4}p|4k|uhd|hd|sd|web-?dl|web-?rip|blu-?ray|bd-?rip|dvd-?rip|hd-?rip|x26[45]|h\\.?26[45]|hevc|avc|aac|ac3|dts|mp4|mkv|xxx|rarbg|complete|collection)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val separators = Regex("[_.+]+")
    private val spaces = Regex("\\s+")

    fun clean(raw: String): String {
        var s = raw.trim().substringAfterLast('/')
        s = s.replace(videoExtension, "")
        s = s.replace(brackets, " ")
        s = s.replace(separators, " ")
        s = s.replace(releaseTag, " ")
        // "Name, extra" or "Name - extra": the part before the first separator is the person.
        s = s.substringBefore(',').substringBefore(" - ")
        s = s.replace(spaces, " ").trim(' ', '-', ',', '.')
        if (s.length < 2 || s.all { it.isDigit() || it == ' ' }) return ""
        return s
    }

    /**
     * Search term for a video file name: only the part before the first "," or "-" is the person.
     * "tom cruise- movie" and "tom cruise, brad pitt- movie" both give "tom cruise".
     */
    fun forVideo(raw: String): String {
        var s = raw.trim().substringAfterLast('/').replace(videoExtension, "")
        s = s.substringBefore(',').substringBefore('-')
        return tidy(s)
    }

    /**
     * Search term for a folder: the whole folder name (only separators and spacing are tidied) when [whole] is on,
     * otherwise the same cleaning as a file name.
     */
    fun forFolder(raw: String, whole: Boolean): String =
        if (whole) tidy(raw.trim().trimEnd('/').substringAfterLast('/')) else clean(raw)

    private fun tidy(text: String): String {
        val s = text.replace(separators, " ").replace(spaces, " ").trim(' ', '-', ',', '.')
        return if (s.length < 2 || s.all { it.isDigit() || it == ' ' }) "" else s
    }

    /** Lower case, accents removed, punctuation reduced to single spaces: used to compare names. */
    fun normalize(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return decomposed.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
