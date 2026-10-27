package com.deepak.videolibrary.artwork.online

import com.deepak.videolibrary.artwork.ProviderIds
import org.json.JSONObject

/**
 * ThePornDB stash-box-compatible performer search. Uses the same encrypted API key
 * entered in Settings > Scene metadata (no duplicate credentials).
 */
class ThePornDbProvider : PerformerProvider {
    override val id: String = ProviderIds.TPDB
    override val displayName = "ThePornDB"

    override suspend fun search(query: String, apiKey: String, endpoint: String?, page: Int): List<PerformerCandidate> {
        if (apiKey.isBlank()) throw ProviderException(ProviderFailure.NO_KEY)
        val document = "query Search(\$names: String!, \$page: Int!, \$perPage: Int!) { queryPerformers(input: {names: \$names, page: \$page, per_page: \$perPage}) " +
            "{ performers { id name disambiguation images { url width height } } } }"
        val variables = JSONObject()
            .put("names", query)
            .put("page", page.coerceAtLeast(1))
            .put("perPage", PerformerProvider.PAGE_SIZE)
        val request = JSONObject().put("query", document).put("variables", variables).toString()
        val body = ArtworkHttp.postJson(
            EndpointPolicy.normalize(endpoint) ?: "https://theporndb.net/graphql",
            mapOf("ApiKey" to apiKey, "Accept" to "application/json"),
            request,
        )
        return StashDbProvider.parse(body).map { it.copy(providerId = id, providerName = displayName) }
    }
}
