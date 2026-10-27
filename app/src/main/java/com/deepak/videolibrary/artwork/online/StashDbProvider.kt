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

    override suspend fun search(query: String, apiKey: String, endpoint: String?, page: Int): List<PerformerCandidate> {
        if (apiKey.isBlank()) throw ProviderException(ProviderFailure.NO_KEY)
        val payload = JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject().put("names", query).put("page", page.coerceAtLeast(1)).put("perPage", PerformerProvider.PAGE_SIZE))
            .toString()
        val body = ArtworkHttp.postJson(EndpointPolicy.normalize(endpoint) ?: ENDPOINT, mapOf("ApiKey" to apiKey, "Accept" to "application/json"), payload)
        return parse(body)
    }

    companion object {
        private const val ENDPOINT = "https://stashdb.org/graphql"
        private const val QUERY =
            "query Search(\$names: String!, \$page: Int!, \$perPage: Int!) { queryPerformers(input: {names: \$names, page: \$page, per_page: \$perPage}) " +
                "{ performers { id name disambiguation images { url width height } } } }"

        internal fun parse(body: String): List<PerformerCandidate> {
            val root = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw ProviderException(ProviderFailure.NON_JSON, e)
            }
            val errors = root.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                // Same classification as scene lookups: a schema mismatch or rate limit is not a bad key.
                throw ProviderException(com.deepak.videolibrary.metadata.GraphQlFailureClassifier.classify(errors.toString()))
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

        /**
         * Every usable image, best first, without duplicates (at most [MAX_PHOTOS], which is far more than a profile
         * has). Portrait-shaped images come first (faces suit portrait covers). Among them, the smallest one that is
         * still at least [TARGET_WIDTH] wide wins: covers are decoded to about that size anyway, and pulling a
         * multi-megabyte original from an overseas CDN only to shrink it was a large part of slow portrait lookups.
         * Images smaller than that follow, largest first. Unknown sizes keep the provider's order after sized ones.
         */
        internal fun rankedImages(item: JSONObject): List<String> {
            val images = item.optJSONArray("images") ?: return emptyList()
            data class Scored(val url: String, val portrait: Boolean, val bigEnough: Boolean, val area: Long)
            val scored = ArrayList<Scored>()
            for (i in 0 until images.length()) {
                val image = images.optJSONObject(i) ?: continue
                val url = image.optString("url")
                if (!ImageUrlPolicy.looksUsable(url)) continue
                val w = image.optInt("width")
                val h = image.optInt("height")
                scored += Scored(url, h >= w && w > 0, w >= TARGET_WIDTH, minOf(w.toLong() * h, 999_999_999L))
            }
            // Stable sort: equal scores keep the provider's order.
            return scored.sortedWith(
                compareByDescending<Scored> { it.portrait }
                    .thenByDescending { it.bigEnough }
                    .thenBy { if (it.bigEnough) it.area else -it.area },
            ).map { it.url }.distinct().take(MAX_PHOTOS)
        }

        /** Width a downloaded cover needs; anything larger is downscaled after download. */
        const val TARGET_WIDTH = 1000

        /** Every picture of a profile is offered (the dialog shows them a page at a time); this only guards against absurd responses. */
        const val MAX_PHOTOS = 300
    }
}
