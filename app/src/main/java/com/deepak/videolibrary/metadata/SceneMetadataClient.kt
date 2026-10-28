package com.deepak.videolibrary.metadata

import com.deepak.videolibrary.artwork.online.ArtworkHttp
import com.deepak.videolibrary.artwork.online.ProviderException
import com.deepak.videolibrary.artwork.online.ProviderFailure
import com.deepak.videolibrary.artwork.online.ProviderKeys
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class SceneMatch(val provider: String, val scene: JSONObject, val method: String) {
    val id: String get() = scene.optString("id")
    val title: String get() = scene.optString("title").ifBlank { scene.optString("code", "Untitled scene") }
    fun encoded(): JSONObject = JSONObject().put("provider", provider).put("method", method).put("scene", scene)
}

/** Read-only stash-box GraphQL API. Never transmits full SMB paths or credentials. */
class SceneMetadataClient(private val keys: ProviderKeys) {
    private fun endpoint(provider: String): String = when (provider) {
        SceneMetadataSettings.TPDB -> "https://theporndb.net/graphql"
        else -> "https://stashdb.org/graphql"
    }

    private suspend fun query(provider: String, document: String, variables: JSONObject = JSONObject()): JSONObject {
        val key = keys.get(provider) ?: throw ProviderException(ProviderFailure.NO_KEY)
        val body = JSONObject().put("query", document).put("variables", variables).toString()
        val response = ArtworkHttp.postJson(
            endpoint(provider),
            StashBoxHeaders.forProvider(provider, key),
            body,
        )
        val root = try { JSONObject(response) } catch (e: Exception) {
            throw ProviderException(ProviderFailure.NON_JSON, e)
        }
        root.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let {
            throw ProviderException(GraphQlFailureClassifier.classify(it.toString()))
        }
        return root.optJSONObject("data") ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
    }

    /**
     * Test the provider's authenticated READ operation rather than requiring one
     * provider's full-text search extension. No local filenames are transmitted.
     */
    suspend fun test(provider: String): Boolean =
        // Authenticated account query used by Stash-box. Avoids provider-specific
        // scene search fields during connection tests.
        query(provider, "query SkyWingConnection { me { name } }").optJSONObject("me") != null

    /** Provider -> index of the search variant that worked last, so known-bad ones are skipped. */
    private val workingSearch = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Stash-box schema searchScenes, with only public, read-only metadata fields. */
    suspend fun search(provider: String, filename: String): List<SceneMatch> = searchTerm(provider, cleanSearchTerm(filename))

    /** Searches with [rawTerm] exactly as given (a folder name keeps its dots), only tidied and length-limited. */
    suspend fun searchText(provider: String, rawTerm: String): List<SceneMatch> =
        searchTerm(provider, rawTerm.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim().take(160))

    private suspend fun searchTerm(provider: String, term: String): List<SceneMatch> {
        if (term.length < 4) return emptyList()
        if (provider == SceneMetadataSettings.TPDB) {
            // ThePornDB's own REST search works with real file names; its GraphQL search is only the fallback.
            var answered = false
            try {
                val found = tpdbRestSearch(term)
                answered = true
                if (found.isNotEmpty()) return found
            } catch (e: ProviderException) {
                if (e.failure in HARD_FAILURES) throw e
            }
            if (answered) return emptyList()
        }
        val variables = JSONObject().put("term", term)
        val variants = if (provider == SceneMetadataSettings.TPDB)
            listOf(TPDB_SCENE_SEARCH to "searchScene",
                SEARCH to "searchScenes", TPDB_TITLE_SEARCH to "queryScenes")
        else listOf(SEARCH to "searchScenes", TPDB_SCENE_SEARCH to "searchScene")
        val preferred = workingSearch[provider]
        val order = variants.indices.sortedBy { if (it == preferred) 0 else 1 }
        var schemaFailure: ProviderException? = null
        for (index in order) {
            val (document, field) = variants[index]
            try {
                val result = query(provider, document, variables)
                val matches = if (field == "searchScene") result.optJSONArray(field)
                    else result.optJSONObject(field)?.optJSONArray("scenes")
                workingSearch[provider] = index
                return sceneResults(provider, "TITLE", matches)
            } catch (e: ProviderException) {
                if (e.failure != ProviderFailure.GRAPHQL_SCHEMA) throw e
                if (preferred == index) workingSearch.remove(provider)
                schemaFailure = e
            }
        }
        throw schemaFailure ?: ProviderException(ProviderFailure.BAD_RESPONSE)
    }

    /**
     * ThePornDB REST scenes (`/scenes`): first the name as ThePornDB parses a file name, then plain title searches with
     * the name changed a little (before the first hyphen, then the first four words) until something is found.
     */
    private suspend fun tpdbRestSearch(term: String): List<SceneMatch> {
        val key = keys.get(SceneMetadataSettings.TPDB) ?: throw ProviderException(ProviderFailure.NO_KEY)
        var failure: ProviderException? = null
        var answered = false
        for ((param, text) in tpdbVariants(term)) {
            try {
                val body = ArtworkHttp.getJson(
                    "https://api.theporndb.net/scenes?$param=" + java.net.URLEncoder.encode(text, "UTF-8") + "&per_page=10",
                    StashBoxHeaders.forProvider(SceneMetadataSettings.TPDB, key),
                )
                answered = true
                val found = parseTpdbScenes(body)
                if (found.isNotEmpty()) return found
            } catch (e: ProviderException) {
                if (e.failure in HARD_FAILURES) throw e
                failure = e
            }
        }
        if (!answered && failure != null) throw failure
        return emptyList()
    }

    /** Exact hash matching uses the stash-box supported OSHASH/PHASH enum values. */
    suspend fun fingerprints(provider: String, hashes: Map<String, String>): List<SceneMatch> =
        fingerprintsBatch(provider, listOf(hashes)).firstOrNull().orEmpty()

    /**
     * Looks up many videos in ONE request, the way Stash's Scene Tagger does a whole page at once:
     * findScenesBySceneFingerprints takes one fingerprint list per video and answers in the same order.
     * The result has one entry per input (empty when that video had no usable hash or no match).
     */
    suspend fun fingerprintsBatch(provider: String, perVideo: List<Map<String, String>>): List<List<SceneMatch>> {
        if (perVideo.isEmpty()) return emptyList()
        val input = JSONArray()
        val sent = ArrayList<Int>()
        perVideo.forEachIndexed { index, hashes ->
            val fingerprintList = JSONArray()
            for ((algorithm, hash) in hashes) {
                if (algorithm !in setOf("OSHASH", "PHASH")) continue
                if (!hash.matches(Regex("[a-fA-F0-9]{1,16}"))) continue
                fingerprintList.put(JSONObject().put("algorithm", algorithm).put("hash", hash.lowercase(Locale.ROOT)))
            }
            if (fingerprintList.length() > 0) {
                input.put(fingerprintList)
                sent += index
            }
        }
        val out = MutableList(perVideo.size) { emptyList<SceneMatch>() }
        if (sent.isEmpty()) return out
        val result = query(provider, if (provider == SceneMetadataSettings.TPDB) HASH_SEARCH_TPDB else HASH_SEARCH, JSONObject().put("fps", input))
        val answers = result.optJSONArray("findScenesBySceneFingerprints")
        sent.forEachIndexed { position, index ->
            out[index] = sceneResults(provider, "FINGERPRINT", answers?.optJSONArray(position))
        }
        return out
    }

    private fun sceneResults(provider: String, method: String, input: JSONArray?): List<SceneMatch> {
        val out = ArrayList<SceneMatch>()
        if (input == null) return out
        for (i in 0 until minOf(input.length(), 20)) {
            val scene = input.optJSONObject(i) ?: continue
            if (scene.optString("id").isNotBlank()) out += SceneMatch(provider, scene, method)
        }
        return out.distinctBy { it.id }
    }

    companion object {
        private val HARD_FAILURES = setOf(ProviderFailure.UNAUTHORIZED, ProviderFailure.FORBIDDEN, ProviderFailure.NO_KEY, ProviderFailure.RATE_LIMITED, ProviderFailure.NETWORK)

        /** The searches tried for [term], in order: (REST parameter, text). */
        internal fun tpdbVariants(term: String): List<Pair<String, String>> {
            val out = LinkedHashSet<Pair<String, String>>()
            out += "parse" to term
            out += "q" to term
            term.substringBefore('-').trim().takeIf { it.length >= 4 && it != term }?.let { out += "q" to it }
            val words = term.split(' ').filter { it.isNotBlank() }
            if (words.size > 4) out += "q" to words.take(4).joinToString(" ")
            return out.toList()
        }

        /** ThePornDB REST scenes as the same scene shape the stash-box searches give (title, images, studio, performers, duration). */
        internal fun parseTpdbScenes(body: String): List<SceneMatch> {
            val root = try { JSONObject(body) } catch (e: Exception) { throw ProviderException(ProviderFailure.NON_JSON, e) }
            val data = root.optJSONArray("data") ?: return emptyList()
            fun text(o: JSONObject?, name: String): String = o?.optString(name).orEmpty().takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val out = ArrayList<SceneMatch>()
            for (i in 0 until minOf(data.length(), 20)) {
                val item = data.optJSONObject(i) ?: continue
                val id = text(item, "id").ifEmpty { text(item, "_id") }
                if (id.isEmpty()) continue
                val urls = LinkedHashSet<String>()
                val background = item.optJSONObject("background")
                listOf(text(background, "large"), text(background, "full"), text(background, "medium"), text(item, "poster"), text(item, "image"))
                    .filter { com.deepak.videolibrary.artwork.online.ImageUrlPolicy.looksUsable(it) }
                    .forEach { urls += it }
                val images = JSONArray().also { array -> urls.forEach { array.put(JSONObject().put("url", it)) } }
                val performers = JSONArray()
                item.optJSONArray("performers")?.let { list ->
                    for (p in 0 until list.length()) {
                        val name = text(list.optJSONObject(p), "name")
                        if (name.isNotEmpty()) performers.put(JSONObject().put("performer", JSONObject().put("name", name)))
                    }
                }
                val scene = JSONObject()
                    .put("id", id)
                    .put("title", text(item, "title"))
                    .put("details", text(item, "description"))
                    .put("date", text(item, "date"))
                    .put("duration", item.optInt("duration", 0))
                    .put("studio", JSONObject().put("name", text(item.optJSONObject("site"), "name")))
                    .put("images", images)
                    .put("performers", performers)
                out += SceneMatch(SceneMetadataSettings.TPDB, scene, "TITLE")
            }
            return out.distinctBy { it.id }
        }

        private const val FIELDS = "id title code details date duration director studio { name } images { url } performers { performer { name } } tags { name }"
        private val SEARCH = "query SceneText(\$term: String!) { searchScenes(term: \$term, per_page: 10) { scenes { $FIELDS } } }"
        // Minimal poster-first field set, for TPDB servers with a narrower GraphQL schema.
        private val TPDB_SCENE_SEARCH =
            "query SceneTextLegacy(\$term: String!) { searchScene(term: \$term, limit: 10) { id title images { url } } }"
        private val TPDB_TITLE_SEARCH =
            "query SceneTitle(\$term: String!) { queryScenes(input: { title: \$term, page: 1, per_page: 10 }) { scenes { id title images { url } } } }"
        private val HASH_SEARCH = "query SceneHash(\$fps: [[FingerprintQueryInput!]!]!) { findScenesBySceneFingerprints(fingerprints: \$fps) { $FIELDS } }"
        private val HASH_SEARCH_TPDB =
            "query SceneHash(\$fps: [[FingerprintQueryInput!]!]!) { findScenesBySceneFingerprints(fingerprints: \$fps) { id title images { url } } }"

        internal fun cleanSearchTerm(filename: String): String {
            val noExtension = filename.substringAfterLast('/').substringBeforeLast('.', filename.substringAfterLast('/'))
            return noExtension.replace(Regex("[._]+"), " ")
                .replace(Regex("\\b(2160p|1080p|720p|4k|h264|h265|hevc|x264|x265|mp4|mkv|web[- ]?dl)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim().take(160)
        }
    }
}
