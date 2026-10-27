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
    suspend fun search(provider: String, filename: String): List<SceneMatch> {
        val term = cleanSearchTerm(filename)
        if (term.length < 4) return emptyList()
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
