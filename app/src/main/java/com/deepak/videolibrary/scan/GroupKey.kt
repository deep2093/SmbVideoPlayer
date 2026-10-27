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
        val delimiter = s.indexOfFirst { (splitsOnHyphen && (NameMarks.isComma(it) || NameMarks.isDash(it))) || (rule == GroupingRule.COMMA_ONLY && NameMarks.isComma(it)) }
        if (delimiter >= 0) s = s.substring(0, delimiter).trim()
        s = s.trim().trimStart { !it.isLetterOrDigit() }
        // Keep the complete cleaned phrase rather than only the first word.
        s = s
            .replace(spaces, " ")
            .replace(trailingNumber, "")
            .trim(*NameMarks.trimChars)
        if (rule == GroupingRule.COMMA_HYPHEN_OR_TWO_WORDS && delimiter < 0) {
            s = s.split(' ').filter(String::isNotBlank).take(2).joinToString(" ")
        }
        return if (s.isBlank()) FALLBACK else s.lowercase(Locale.ROOT)
    }
}

/**
 * The names a video is filed under inside a Virtual folder. Read the file name from the left and stop at the first
 * hyphen; every comma-separated name before it gets its own subfolder, and the same video appears in each of them
 * ("tom cruise, brad pitt - movie.mp4" -> Tom Cruise, Brad Pitt). A name with neither a hyphen nor a comma uses its
 * first two words. Display only: nothing on the drive is renamed, copied or moved.
 */
/**
 * The three on/off rules that turn a file name into folder names. All on is the Virtual folder rule:
 * [stopAtHyphen] reads only the text before the first "-", [splitAtCommas] makes one folder per comma-separated name
 * (the video is listed in each), and [firstTwoWords] uses the first two words when no hyphen or comma was used.
 */
data class GroupingSwitches(
    val stopAtHyphen: Boolean = true,
    val splitAtCommas: Boolean = true,
    val firstTwoWords: Boolean = true,
)

/**
 * The grouping rule of every place (Virtual folders, Custom folders) is always the full one: stop at the first hyphen,
 * split names at commas, otherwise use the first two words. There are no switches for it.
 */
object GroupingPrefs {
    const val VIRTUAL = "virtual"
    const val CUSTOM = "custom"

    @Suppress("UNUSED_PARAMETER")
    fun load(context: android.content.Context, place: String): GroupingSwitches = GroupingSwitches()
}

/**
 * Every character that works as a hyphen or comma in file names. Downloaded names often use an en dash "–", em dash
 * "—", minus sign "−" or a full-width comma instead of the plain keys; without these, "Theodora Day– Tune Up" was read
 * as one long name and made a second "Theodora Day…" folder.
 */
internal object NameMarks {
    private const val DASHES = "-\u2010\u2011\u2012\u2013\u2014\u2015\u2212\uFE58\uFE63\uFF0D"
    private const val COMMAS = ",\uFF0C\u3001"

    fun isDash(c: Char): Boolean = c in DASHES
    fun isComma(c: Char): Boolean = c in COMMAS

    /** The trim set used after cutting a name, with every dash and comma form included. */
    val trimChars: CharArray = (" _.;:" + DASHES + COMMAS).toCharArray()
}

object VirtualNames {
    private val leadingTag = Regex("^\\s*(\\[[^\\]]*\\]|\\([^)]*\\))")
    private val spaces = Regex("\\s+")
    private val trailingNumber = Regex("\\s+(?:part\\s*)?\\d+$", RegexOption.IGNORE_CASE)

    /** Same-folder test: names that differ only by capitalisation or spacing are one folder. */
    fun identity(name: String): String = name.trim().replace(spaces, " ").lowercase(Locale.ROOT)

    /** "tom cruise" and "TOM CRUISE" both become "Tom Cruise". */
    fun display(name: String): String = name.trim().replace(spaces, " ").split(' ')
        .joinToString(" ") { word -> word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }

    fun of(fileName: String, rules: GroupingSwitches = GroupingSwitches()): List<String> {
        var s = fileName.substringBeforeLast('.', fileName).trim()
        while (true) {
            val tag = leadingTag.find(s) ?: break
            s = s.substring(tag.range.last + 1).trimStart()
        }
        // Any dash form counts as the hyphen ("Theodora Day– Tune Up" stops at "–"), any comma form as a comma.
        val hyphen = s.indexOfFirst(NameMarks::isDash)
        val cutAtHyphen = rules.stopAtHyphen && hyphen >= 0
        val head = if (cutAtHyphen) s.substring(0, hyphen) else s
        val splitAtComma = rules.splitAtCommas && head.any(NameMarks::isComma)
        var parts = (if (splitAtComma) head.split(*",\uFF0C\u3001".toCharArray()) else listOf(head)).map(::clean).filter(String::isNotBlank)
        if (rules.firstTwoWords && !cutAtHyphen && !splitAtComma) {
            parts = parts.map { it.split(' ').filter(String::isNotBlank).take(2).joinToString(" ") }
        }
        val seen = HashSet<String>()
        val names = parts.map(::display).filter { seen.add(identity(it)) }
        return names.ifEmpty { listOf(GroupKey.FALLBACK) }
    }

    private fun clean(part: String): String = part.trim().trimStart { !it.isLetterOrDigit() }
        .replace(spaces, " ")
        .replace(trailingNumber, "")
        .trim(*NameMarks.trimChars)
}
