package com.deepak.videolibrary.artwork.online

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** One ThePornDB movie that can provide a full movie poster. */
data class MovieCandidate(
    val id: String,
    val title: String,
    val year: String?,
    val site: String?,
    /** The poster to download for a cover (large size, not the full original). */
    val posterUrl: String?,
    /** A small version for lists. */
    val previewUrl: String?,
)

/**
 * Full movie posters from ThePornDB. Movies are not part of the stash-box GraphQL endpoint used for scenes, so this
 * uses ThePornDB's REST API (`https://api.theporndb.net/movies`, the API its own Jellyfin plugin uses), with the same
 * API token, sent as `Authorization: Bearer`. Only the search text (a folder or file name) is sent.
 */
internal object TpdbMovies {
    private const val ENDPOINT = "https://api.theporndb.net/movies"

    /** [byFileName] asks ThePornDB to parse a file name (site, date, title); otherwise [text] is a plain title search. */
    suspend fun search(apiKey: String, text: String, byFileName: Boolean): List<MovieCandidate> {
        if (apiKey.isBlank()) throw ProviderException(ProviderFailure.NO_KEY)
        val term = text.trim().take(200)
        if (term.length < 2) return emptyList()
        val param = if (byFileName) "parse" else "q"
        val url = "$ENDPOINT?$param=" + URLEncoder.encode(term, "UTF-8") + "&per_page=10"
        val body = ArtworkHttp.getJson(url, mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"))
        return parse(body)
    }

    internal fun parse(body: String): List<MovieCandidate> {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ProviderException(ProviderFailure.NON_JSON, e)
        }
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<MovieCandidate>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() && it != "null" }
                ?: item.optString("_id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val title = item.optString("title").trim().takeIf { it.isNotEmpty() && it != "null" } ?: continue
            val posters = item.optJSONObject("posters")
            fun pick(vararg names: String): String? = names.asSequence()
                .mapNotNull { posters?.optString(it) }
                .firstOrNull { ImageUrlPolicy.looksUsable(it) }
            val poster = pick("large", "full", "medium")
                ?: item.optString("poster").takeIf { ImageUrlPolicy.looksUsable(it) }
                ?: item.optString("image").takeIf { ImageUrlPolicy.looksUsable(it) }
            val preview = pick("small", "medium", "large") ?: poster
            out += MovieCandidate(
                id = id,
                title = title,
                year = item.optString("date").takeIf { it.length >= 4 && it != "null" }?.take(4),
                site = item.optJSONObject("site")?.optString("name")?.takeIf { it.isNotBlank() && it != "null" },
                posterUrl = poster,
                previewUrl = preview,
            )
        }
        return out
    }

    private fun words(text: String, fileName: Boolean = false): List<String> =
        text.lowercase(Locale.ROOT)
            .let { if (fileName) it.replace(Regex("\\.[a-z0-9]{2,4}$"), "") else it }
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }

    /** The movie whose title IS the folder name (same words, any punctuation or case), if exactly one has a poster. */
    fun confidentForFolder(folderName: String, results: List<MovieCandidate>): MovieCandidate? {
        val wanted = words(folderName)
        if (wanted.isEmpty()) return null
        val exact = results.filter { it.posterUrl != null && words(it.title) == wanted }
        return exact.distinctBy { it.id }.singleOrNull()
    }

    /**
     * Movies worth showing for review when none is certain: they have a poster and share enough words with [name]
     * (two words, or the whole shorter name when it is a single long word). At most five, best overlap first.
     */
    fun plausible(name: String, results: List<MovieCandidate>, fileName: Boolean = false): List<MovieCandidate> {
        val wanted = words(name, fileName).toSet()
        if (wanted.isEmpty()) return emptyList()
        return results.asSequence()
            .filter { it.posterUrl != null }
            .map { movie ->
                val title = words(movie.title).toSet()
                val shared = wanted.intersect(title)
                val fits = shared.size >= 2 ||
                    (shared.size == 1 && shared.size == minOf(wanted.size, title.size) && shared.first().length >= 5)
                Triple(movie, shared.size, fits)
            }
            .filter { it.third }
            .distinctBy { it.first.id }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(5)
            .toList()
    }

    /**
     * For a file name, ThePornDB's own parse puts its best guess first. It is used only when every word of that
     * movie's title also appears in the file name, so an unrelated guess is never applied.
     */
    fun confidentForFile(fileName: String, results: List<MovieCandidate>): MovieCandidate? {
        val best = results.firstOrNull { it.posterUrl != null } ?: return null
        val titleWords = words(best.title)
        if (titleWords.isEmpty() || titleWords.joinToString("").length < 4) return null
        val fileWords = words(fileName, fileName = true).toSet()
        return best.takeIf { fileWords.containsAll(titleWords) }
    }
}
