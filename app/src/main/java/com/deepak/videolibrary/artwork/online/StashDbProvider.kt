package com.deepak.videolibrary.artwork.online

import com.deepak.videolibrary.artwork.ProviderIds
import org.json.JSONObject

/**
 * StashDB, through its documented GraphQL endpoint, authenticated with the user's own API key in the `ApiKey`
 * header (https://guidelines.stashdb.org/docs/faq_getting_started/stashdb/accessing-stashdb/).
 */
class StashDbProvider : PerformerProvider {
    override val id = ProviderIds.STASH_DB
    override val displayName = "StashDB"

    override suspend fun search(query: String, apiKey: String, endpoint: String?): List<PerformerCandidate> {
        if (apiKey.isBlank()) throw ProviderException(ProviderFailure.NO_KEY)
        val payload = JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject().put("names", query))
            .toString()
        val body = ArtworkHttp.postJson(EndpointPolicy.normalize(endpoint) ?: ENDPOINT, mapOf("ApiKey" to apiKey, "Accept" to "application/json"), payload)
        return parse(body)
    }

    companion object {
        private const val ENDPOINT = "https://stashdb.org/graphql"
        private const val QUERY =
            "query Search(\$names: String!) { queryPerformers(input: {names: \$names, page: 1, per_page: 10}) " +
                "{ performers { id name disambiguation images { url width height } } } }"

        internal fun parse(body: String): List<PerformerCandidate> {
            val root = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw ProviderException(ProviderFailure.BAD_RESPONSE, e)
            }
            val errors = root.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val text = errors.toString().lowercase()
                throw ProviderException(if ("unauthor" in text || "api key" in text || "forbidden" in text) ProviderFailure.UNAUTHORIZED else ProviderFailure.BAD_RESPONSE)
            }
            val performers = root.optJSONObject("data")?.optJSONObject("queryPerformers")?.optJSONArray("performers") ?: return emptyList()
            val out = ArrayList<PerformerCandidate>()
            for (i in 0 until performers.length()) {
                val item = performers.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val performerId = item.optString("id")
                if (name.isEmpty() || performerId.isBlank()) continue
                val photos = rankedImages(item)
                val url = photos.firstOrNull()
                out += PerformerCandidate(
                    providerId = ProviderIds.STASH_DB,
                    providerName = "StashDB",
                    performerId = performerId,
                    name = name,
                    disambiguation = item.optString("disambiguation").takeIf { it.isNotBlank() && it != "null" },
                    imageUrl = url,
                    previewUrl = url,
                    images = photos,
                )
            }
            return out
        }

        /** The best image: portrait-shaped first (faces suit portrait covers), otherwise the largest usable one. */
        internal fun bestImage(item: JSONObject): String? = rankedImages(item).firstOrNull()

        /** Every usable image, portrait-shaped and larger ones first, without duplicates (at most [MAX_PHOTOS]). */
        internal fun rankedImages(item: JSONObject): List<String> {
            val images = item.optJSONArray("images") ?: return emptyList()
            val scored = ArrayList<Pair<String, Long>>()
            for (i in 0 until images.length()) {
                val image = images.optJSONObject(i) ?: continue
                val url = image.optString("url")
                if (!ImageUrlPolicy.looksUsable(url)) continue
                val w = image.optInt("width")
                val h = image.optInt("height")
                val score = (if (h >= w && w > 0) 1_000_000_000L else 0L) + minOf(w.toLong() * h, 999_999_999L)
                scored += url to score
            }
            // Stable sort: equal scores keep the provider's order.
            return scored.sortedByDescending { it.second }.map { it.first }.distinct().take(MAX_PHOTOS)
        }

        const val MAX_PHOTOS = 12
    }
}
