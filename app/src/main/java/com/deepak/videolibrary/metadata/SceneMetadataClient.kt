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
            throw ProviderException(ProviderFailure.BAD_RESPONSE, e)
        }
        val errors = root.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val message = errors.toString().lowercase(Locale.ROOT)
            throw ProviderException(
                when {
                    "unauthor" in message || "not authorized" in message || "not authenticated" in message ||
                        "authentication" in message || "api key" in message ||
                        "invalid token" in message || "invalid credential" in message ||
                        "forbidden" in message -> ProviderFailure.UNAUTHORIZED
                    "rate limit" in message -> ProviderFailure.RATE_LIMITED
                    else -> ProviderFailure.BAD_RESPONSE
                },
            )
        }
        return root.optJSONObject("data") ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
    }

    /**
     * Test the provider's authenticated READ operation rather than requiring one
     * provider's full-text search extension. No local filenames are transmitted.
     */
    suspend fun test(provider: String): Boolean = if (provider == SceneMetadataSettings.TPDB) {
        query(provider, "query TestSceneMetadata { queryScenes(input: { page: 1, per_page: 1 }) { scenes { id } } }")
            .optJSONObject("queryScenes") != null
    } else {
        query(provider, "query TestSceneMetadata { searchScenes(term: \"example\", per_page: 1) { scenes { id } } }")
            .optJSONObject("searchScenes") != null
    }

    /** Stash-box schema searchScenes, with only public, read-only metadata fields. */
    suspend fun search(provider: String, filename: String): List<SceneMatch> {
        val term = cleanSearchTerm(filename)
        if (term.length < 4) return emptyList()
        val variables = JSONObject().put("term", term)
        val (result, field) = try {
            query(provider, SEARCH, variables) to "searchScenes"
        } catch (e: ProviderException) {
            // TPDB's proprietary stash-box implementation may omit StashDB's
            // full-text query. Fall back to the standard scene query input.
            if (provider != SceneMetadataSettings.TPDB || e.failure != ProviderFailure.BAD_RESPONSE) throw e
            query(provider, TPDB_TITLE_SEARCH, variables) to "queryScenes"
        }
        return sceneResults(provider, "TITLE", result.optJSONObject(field)?.optJSONArray("scenes"))
    }

    /** Exact hash matching uses the stash-box supported OSHASH/PHASH enum values. */
    suspend fun fingerprints(provider: String, hashes: Map<String, String>): List<SceneMatch> {
        if (hashes.isEmpty()) return emptyList()
        val fingerprintList = JSONArray()
        for ((algorithm, hash) in hashes) {
            if (algorithm !in setOf("OSHASH", "PHASH")) continue
            if (!hash.matches(Regex("[a-fA-F0-9]{1,16}"))) continue
            fingerprintList.put(JSONObject().put("algorithm", algorithm).put("hash", hash.lowercase(Locale.ROOT)))
        }
        if (fingerprintList.length() == 0) return emptyList()
        val input = JSONArray().put(fingerprintList)
        val result = query(provider, HASH_SEARCH, JSONObject().put("fps", input))
        val perVideo = result.optJSONArray("findScenesBySceneFingerprints")?.optJSONArray(0)
        return sceneResults(provider, "FINGERPRINT", perVideo)
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
        private val TPDB_TITLE_SEARCH =
            "query SceneTitle(\$term: String!) { queryScenes(input: { title: \$term, page: 1, per_page: 10 }) { scenes { id title images { url } } } }"
        private val HASH_SEARCH = "query SceneHash(\$fps: [[FingerprintQueryInput!]!]!) { findScenesBySceneFingerprints(fingerprints: \$fps) { $FIELDS } }"

        internal fun cleanSearchTerm(filename: String): String {
            val noExtension = filename.substringAfterLast('/').substringBeforeLast('.', filename.substringAfterLast('/'))
            return noExtension.replace(Regex("[._]+"), " ")
                .replace(Regex("\\b(2160p|1080p|720p|4k|h264|h265|hevc|x264|x265|mp4|mkv|web[- ]?dl)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim().take(160)
        }
    }
}
