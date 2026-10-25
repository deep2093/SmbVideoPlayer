package com.skywing.desktop.artwork

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Turns a folder or video label into a search term for a provider. Only the cleaned label ever leaves the PC:
 * never a full path, a server name or an address.
 */
internal object NameCleaner {
    private val videoExtension = Regex("\\.(mp4|mkv|avi|mov|wmv|m4v|ts|flv|webm|mpg|mpeg)$", RegexOption.IGNORE_CASE)
    private val brackets = Regex("[\\[(\\{][^\\]\\)\\}]*[\\]\\)\\}]")
    private val releaseTag = Regex(
        "\\b(\\d{3,4}p|4k|uhd|hd|sd|web-?dl|web-?rip|blu-?ray|bd-?rip|dvd-?rip|hd-?rip|x26[45]|h\\.?26[45]|hevc|avc|aac|ac3|dts|mp4|mkv|xxx|rarbg|complete|collection)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val separators = Regex("[_.+]+")
    private val spaces = Regex("\\s+")

    private fun leaf(raw: String) = raw.trim().replace('\\', '/').trimEnd('/').substringAfterLast('/')

    fun clean(raw: String): String {
        var s = leaf(raw)
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

    /** Search terms for a video file name, in the order they are tried: the name before the first ",", then before the first "-". */
    fun videoTerms(raw: String): List<String> {
        val s = leaf(raw).replace(videoExtension, "")
        val beforeComma = if (',' in s) tidy(s.substringBefore(',')) else ""
        val beforeHyphen = tidy(s.substringBefore('-'))
        return listOf(beforeComma, beforeHyphen).filter { it.isNotEmpty() }.distinct()
    }

    fun forVideo(raw: String): String = videoTerms(raw).firstOrNull().orEmpty()

    /** The whole folder name (only separators and spacing tidied) when [whole] is on, otherwise cleaned like a file name. */
    fun forFolder(raw: String, whole: Boolean): String = if (whole) tidy(leaf(raw)) else clean(raw)

    private fun tidy(text: String): String {
        val s = text.replace(separators, " ").replace(spaces, " ").trim(' ', '-', ',', '.')
        return if (s.length < 2 || s.all { it.isDigit() || it == ' ' }) "" else s
    }

    /** Lower case, accents removed, punctuation reduced to single spaces: used to compare names. */
    fun normalize(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return decomposed.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    /** Search suggestions from a label: each part before or between "," and "-", then the whole name. */
    fun choices(label: String): List<String> {
        val base = leaf(label).replace(Regex("\\.[A-Za-z0-9]{2,4}$"), "")
        val parts = base.split(',', '-').map { it.replace('_', ' ').replace('.', ' ').trim().replace(spaces, " ") }
        return (parts + base.trim()).filter { it.length >= 2 }.distinct().take(6)
    }
}

/** Validation of a user-supplied API address: HTTPS, a real host, no embedded credentials. */
internal object EndpointPolicy {
    fun isAllowed(url: String?): Boolean {
        if (url.isNullOrBlank() || url.length > 512) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return "https".equals(uri.scheme, ignoreCase = true) && uri.userInfo == null && !uri.host.isNullOrBlank() && uri.fragment == null
    }

    fun hostOf(endpoint: String): Set<String> =
        setOfNotNull(runCatching { URI(endpoint.trim()).host?.lowercase() }.getOrNull())

    fun normalize(url: String?): String? = url?.trim()?.trimEnd('/')?.takeIf { isAllowed(it) }
}

/** Which image addresses the app is willing to download. HTTPS only, and never local or private addresses. */
internal object ImageUrlPolicy {
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

    fun looksUsable(url: String?): Boolean =
        !url.isNullOrBlank() && url != "null" && url.length <= 4096 && url.trim().startsWith("https://", ignoreCase = true)

    fun isAllowed(url: String?, trustedHosts: Set<String> = emptySet()): Boolean {
        if (url.isNullOrBlank() || url.length > 4096) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        if (host in trustedHosts) return true
        if (uri.port != -1 && uri.port != 443) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan")) return false
        // Literal IP addresses are refused; image hosts use names.
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) return false
        return host.contains('.')
    }
}

/** One possible match returned by a provider. */
internal data class PerformerCandidate(
    val providerId: String,
    val providerName: String,
    val performerId: String,
    val name: String,
    val disambiguation: String?,
    val imageUrl: String?,
    val previewUrl: String?,
    /** Every usable photo of this performer, best first. */
    val images: List<String> = listOfNotNull(imageUrl),
)

internal enum class ProviderFailure(val message: String) {
    NO_KEY("Add your StashDB API key in Settings > Artwork."),
    UNAUTHORIZED("StashDB rejected the API key (401). Check or replace the key."),
    FORBIDDEN("StashDB refused this request (403). Your key may not have access."),
    RATE_LIMITED("StashDB's rate limit was reached (429). Try again later."),
    NETWORK("Could not reach the internet address. Check the connection."),
    BAD_RESPONSE("The answer could not be read, or the picture can't be used."),
}

internal class ProviderException(val failure: ProviderFailure, cause: Throwable? = null) : Exception(failure.message, cause)

/** Decides whether provider results are certain enough to apply without asking. */
internal object AutoMatch {
    sealed class Result {
        data class Match(val candidate: PerformerCandidate) : Result()
        object Ambiguous : Result()
        object NoMatch : Result()
    }

    /** Exact name matches first, then names starting with the search, then names containing it. */
    fun ranked(query: String, candidates: List<PerformerCandidate>): List<PerformerCandidate> {
        val wanted = NameCleaner.normalize(query)
        fun score(c: PerformerCandidate): Int {
            val name = NameCleaner.normalize(c.name)
            return when {
                name == wanted -> 0
                name.startsWith(wanted) || wanted.startsWith(name) -> 1
                name.contains(wanted) || wanted.contains(name) -> 2
                else -> 3
            }
        }
        return candidates.withIndex().sortedWith(compareBy({ score(it.value) }, { it.index })).map { it.value }
    }

    /**
     * Applies automatically only when exactly one candidate has the very same name, it has a picture, and no
     * disambiguation hints at another person of that name.
     */
    fun evaluate(query: String, candidates: List<PerformerCandidate>): Result {
        val wanted = NameCleaner.normalize(query)
        if (wanted.length < 3) return Result.NoMatch
        val exact = candidates.filter { NameCleaner.normalize(it.name) == wanted }
        return when {
            exact.isEmpty() -> Result.NoMatch
            exact.size > 1 -> Result.Ambiguous
            exact[0].imageUrl.isNullOrBlank() -> Result.NoMatch
            !exact[0].disambiguation.isNullOrBlank() -> Result.Ambiguous
            else -> Result.Match(exact[0])
        }
    }
}

/**
 * Minimal, bounded HTTPS client for StashDB and picture downloads: HTTPS only, short timeouts, a cap on bytes read,
 * no cookies, and API calls never follow redirects. The API key header is never logged.
 */
internal object ArtworkHttp {
    private const val MAX_JSON_BYTES = 2_000_000L

    suspend fun postJson(url: String, headers: Map<String, String>, body: String): String =
        String(request("POST", url, headers, body.toByteArray(Charsets.UTF_8), MAX_JSON_BYTES), Charsets.UTF_8)

    /** A picture's bytes as they are (so an animated GIF stays moving). */
    suspend fun downloadBytes(url: String, trustedHosts: Set<String> = emptySet()): ByteArray {
        if (!ImageUrlPolicy.isAllowed(url, trustedHosts)) throw ProviderException(ProviderFailure.BAD_RESPONSE)
        return request("GET", url, mapOf("Accept" to "image/*"), null, ImageUrlPolicy.MAX_IMAGE_BYTES, followRedirects = true, trustedHosts = trustedHosts)
    }

    fun isGif(bytes: ByteArray): Boolean =
        bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()

    private suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBytes: Long,
        followRedirects: Boolean = false,
        trustedHosts: Set<String> = emptySet(),
    ): ByteArray = withContext(Dispatchers.IO) {
        var target = url
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!target.startsWith("https://", ignoreCase = true)) throw ProviderException(ProviderFailure.BAD_RESPONSE)
            val connection = URL(target).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 SkyWing-Artwork/1.0")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                when {
                    code in 200..299 -> {
                        if (connection.contentLengthLong > maxBytes) throw ProviderException(ProviderFailure.BAD_RESPONSE)
                        val out = ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                if (out.size() > maxBytes) throw ProviderException(ProviderFailure.BAD_RESPONSE)
                            }
                        }
                        return@withContext out.toByteArray()
                    }
                    code in 300..399 && followRedirects && redirects < 3 -> {
                        val next = connection.getHeaderField("Location") ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
                        val resolved = URL(URL(target), next).toString()
                        if (!ImageUrlPolicy.isAllowed(resolved, trustedHosts)) throw ProviderException(ProviderFailure.BAD_RESPONSE)
                        target = resolved
                        redirects++
                    }
                    code == 401 -> throw ProviderException(ProviderFailure.UNAUTHORIZED)
                    code == 403 -> throw ProviderException(ProviderFailure.FORBIDDEN)
                    code == 429 -> throw ProviderException(ProviderFailure.RATE_LIMITED)
                    else -> throw ProviderException(ProviderFailure.BAD_RESPONSE)
                }
            } catch (e: ProviderException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw ProviderException(ProviderFailure.NETWORK, e)
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("unreachable")
    }
}

/**
 * StashDB, through its documented GraphQL endpoint, authenticated with the user's own API key in the `ApiKey`
 * header (https://guidelines.stashdb.org/docs/faq_getting_started/stashdb/accessing-stashdb/).
 */
internal object StashDb {
    const val ID = "stashdb"
    const val NAME = "StashDB"
    const val PAGE_SIZE = 10
    private const val ENDPOINT = "https://stashdb.org/graphql"
    private const val QUERY =
        "query Search(\$names: String!, \$page: Int!, \$perPage: Int!) { queryPerformers(input: {names: \$names, page: \$page, per_page: \$perPage}) " +
            "{ performers { id name disambiguation images { url width height } } } }"

    suspend fun search(query: String, apiKey: String, endpoint: String?, page: Int = 1): List<PerformerCandidate> {
        if (apiKey.isBlank()) throw ProviderException(ProviderFailure.NO_KEY)
        val payload = Json.write(
            mapOf("query" to QUERY, "variables" to mapOf("names" to query, "page" to page.coerceAtLeast(1), "perPage" to PAGE_SIZE)),
        )
        val body = ArtworkHttp.postJson(EndpointPolicy.normalize(endpoint) ?: ENDPOINT, mapOf("ApiKey" to apiKey, "Accept" to "application/json"), payload)
        return parse(body)
    }

    internal fun parse(body: String): List<PerformerCandidate> {
        val root = runCatching { Json.parse(body) as? Map<*, *> }.getOrNull() ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
        val errors = root["errors"] as? List<*>
        if (!errors.isNullOrEmpty()) {
            val text = errors.toString().lowercase()
            throw ProviderException(if ("unauthor" in text || "api key" in text || "forbidden" in text) ProviderFailure.UNAUTHORIZED else ProviderFailure.BAD_RESPONSE)
        }
        val performers = ((root["data"] as? Map<*, *>)?.get("queryPerformers") as? Map<*, *>)?.get("performers") as? List<*> ?: return emptyList()
        return performers.mapNotNull { item ->
            val p = item as? Map<*, *> ?: return@mapNotNull null
            val name = (p["name"] as? String)?.trim().orEmpty()
            val id = p["id"] as? String ?: ""
            if (name.isEmpty() || id.isBlank()) return@mapNotNull null
            val photos = rankedImages(p["images"] as? List<*>)
            PerformerCandidate(ID, NAME, id, name, (p["disambiguation"] as? String)?.takeIf { it.isNotBlank() && it != "null" }, photos.firstOrNull(), photos.firstOrNull(), photos)
        }
    }

    /** Every usable image, portrait-shaped and larger ones first (faces suit covers), without duplicates. */
    private fun rankedImages(images: List<*>?): List<String> {
        val scored = images.orEmpty().mapNotNull { entry ->
            val image = entry as? Map<*, *> ?: return@mapNotNull null
            val url = image["url"] as? String
            if (!ImageUrlPolicy.looksUsable(url)) return@mapNotNull null
            val w = (image["width"] as? Number)?.toLong() ?: 0
            val h = (image["height"] as? Number)?.toLong() ?: 0
            url!! to ((if (h >= w && w > 0) 1_000_000_000L else 0L) + minOf(w * h, 999_999_999L))
        }
        return scored.sortedByDescending { it.second }.map { it.first }.distinct().take(300)
    }
}

/** A small JSON reader and writer, enough for StashDB answers (objects, arrays, strings, numbers, booleans, null). */
internal object Json {
    fun parse(text: String): Any? = Reader(text).run { value().also { skip(); require(i == s.length) { "Trailing data" } } }

    fun write(value: Any?): String = buildString { put(value) }

    private fun StringBuilder.put(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> {
                append('"')
                value.forEach { c ->
                    when {
                        c == '"' -> append("\\\"")
                        c == '\\' -> append("\\\\")
                        c == '\n' -> append("\\n")
                        c == '\r' -> append("\\r")
                        c == '\t' -> append("\\t")
                        c < ' ' -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                }
                append('"')
            }
            is Number, is Boolean -> append(value.toString())
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { n, (k, v) -> if (n > 0) append(','); put(k.toString()); append(':'); put(v) }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { n, v -> if (n > 0) append(','); put(v) }
                append(']')
            }
            else -> put(value.toString())
        }
    }

    private class Reader(val s: String) {
        var i = 0
        var depth = 0

        fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skip()
            require(i < s.length) { "Unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Bad JSON at $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Bad JSON at $i" }
            i += word.length
            return v
        }

        private fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }

        private fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            require(++depth < 64) { "Too deep" }
            i++
            val out = ArrayList<Any?>()
            skip()
            if (s[i] == ']') { i++; depth--; return out }
            while (true) {
                out += value()
                skip()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> { depth--; return out }
                    else -> throw IllegalArgumentException("Bad JSON at $i")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            require(++depth < 64) { "Too deep" }
            i++
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (s[i] == '}') { i++; depth--; return out }
            while (true) {
                skip()
                val k = str()
                skip()
                require(s[i++] == ':') { "Bad JSON at $i" }
                out[k] = value()
                skip()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> { depth--; return out }
                    else -> throw IllegalArgumentException("Bad JSON at $i")
                }
            }
        }
    }
}
