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
            com.deepak.videolibrary.metadata.StashBoxHeaders.forProvider(
                com.deepak.videolibrary.metadata.SceneMetadataSettings.TPDB, apiKey,
            ),
            request,
        )
        return try {
            parseResult(body, "queryPerformers")
        } catch (e: ProviderException) {
            if (e.failure != ProviderFailure.GRAPHQL_SCHEMA) throw e
            // TPDB can implement either modern or legacy Stash-box search.
            val variants = listOf(
                "query PerformerSearch(\$term: String!) { searchPerformers(term: \$term, limit: 10) { performers { id name disambiguation images { url width height } } } }" to "searchPerformers",
                "query PerformerSearchLegacy(\$term: String!) { searchPerformer(term: \$term, limit: 10) { id name disambiguation images { url width height } } }" to "searchPerformer",
            )
            if (page > 1) return emptyList()
            var schemaError: ProviderException = e
            for ((fallbackQuery, field) in variants) {
                val fallback = JSONObject().put("query", fallbackQuery)
                    .put("variables", JSONObject().put("term", query)).toString()
                try {
                    val reply = ArtworkHttp.postJson(
                        EndpointPolicy.normalize(endpoint) ?: "https://theporndb.net/graphql",
                        com.deepak.videolibrary.metadata.StashBoxHeaders.forProvider(
                            com.deepak.videolibrary.metadata.SceneMetadataSettings.TPDB, apiKey,
                        ),
                        fallback,
                    )
                    return parseResult(reply, field)
                } catch (ex: ProviderException) {
                    if (ex.failure != ProviderFailure.GRAPHQL_SCHEMA) throw ex
                    schemaError = ex
                }
            }
            throw schemaError
        }
    }
    private fun parseResult(body: String, field: String): List<PerformerCandidate> {
        val root = try { JSONObject(body) } catch (e: Exception) {
            throw ProviderException(ProviderFailure.NON_JSON, e)
        }
        root.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let {
            throw ProviderException(com.deepak.videolibrary.metadata.GraphQlFailureClassifier.classify(it.toString()))
        }
        val data = root.optJSONObject("data") ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
        // Legacy searchPerformer gives an array directly, modern searchPerformers
        // and queryPerformers have an object containing performers[].
        val list = if (field == "searchPerformer") data.optJSONArray(field)
            else data.optJSONObject(field)?.optJSONArray("performers")
        if (list == null) return emptyList()
        val normalized = JSONObject().put("data",
            JSONObject().put("queryPerformers", JSONObject().put("performers", list)))
        return StashDbProvider.parse(normalized.toString()).map {
            it.copy(providerId = id, providerName = displayName)
        }
    }

}
