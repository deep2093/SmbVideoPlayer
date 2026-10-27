package com.deepak.videolibrary.artwork.online

import com.deepak.videolibrary.artwork.ProviderIds
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * ThePornDB stash-box-compatible performer search. Uses the same encrypted API key
 * entered in Settings > Scene metadata (no duplicate credentials).
 */
class ThePornDbProvider : PerformerProvider {
    override val id: String = ProviderIds.TPDB
    override val displayName = "ThePornDB"

    private class Variant(val name: String, val field: String, val document: String, val paged: Boolean)

    override suspend fun search(query: String, apiKey: String, endpoint: String?, page: Int): List<PerformerCandidate> {
        if (apiKey.isBlank()) throw ProviderException(ProviderFailure.NO_KEY)
        val url = EndpointPolicy.normalize(endpoint) ?: DEFAULT_ENDPOINT
        // TPDB can implement either modern or legacy Stash-box search. The first variant that works is
        // remembered per endpoint, so later searches skip the ones that are known to fail.
        val candidates = VARIANTS.filter { page <= 1 || it.paged }
        if (candidates.isEmpty()) return emptyList()
        val ordered = working[url]?.let { name -> candidates.sortedBy { if (it.name == name) 0 else 1 } } ?: candidates
        var schemaError: ProviderException? = null
        for (variant in ordered) {
            val variables = if (variant.paged) {
                JSONObject().put("names", query).put("page", page.coerceAtLeast(1)).put("perPage", PerformerProvider.PAGE_SIZE)
            } else {
                JSONObject().put("term", query)
            }
            val request = JSONObject().put("query", variant.document).put("variables", variables).toString()
            try {
                val body = ArtworkHttp.postJson(
                    url,
                    com.deepak.videolibrary.metadata.StashBoxHeaders.forProvider(
                        com.deepak.videolibrary.metadata.SceneMetadataSettings.TPDB, apiKey,
                    ),
                    request,
                )
                val result = parseResult(body, variant.field)
                working[url] = variant.name
                return result
            } catch (e: ProviderException) {
                if (e.failure != ProviderFailure.GRAPHQL_SCHEMA) throw e
                if (working[url] == variant.name) working.remove(url)
                schemaError = e
            }
        }
        // Later pages only exist for the paged query; without it there is simply nothing more.
        if (page > 1) return emptyList()
        throw schemaError ?: ProviderException(ProviderFailure.BAD_RESPONSE)
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

    private companion object {
        const val DEFAULT_ENDPOINT = "https://theporndb.net/graphql"
        const val FIELDS = "id name disambiguation images { url width height }"

        /** Endpoint -> name of the query variant that last worked there. */
        private val working = ConcurrentHashMap<String, String>()

        private val VARIANTS = listOf(
            Variant(
                "queryPerformers", "queryPerformers",
                "query Search(\$names: String!, \$page: Int!, \$perPage: Int!) { queryPerformers(input: {names: \$names, page: \$page, per_page: \$perPage}) { performers { $FIELDS } } }",
                paged = true,
            ),
            Variant(
                "searchPerformers", "searchPerformers",
                "query PerformerSearch(\$term: String!) { searchPerformers(term: \$term, limit: 10) { performers { $FIELDS } } }",
                paged = false,
            ),
            Variant(
                "searchPerformer", "searchPerformer",
                "query PerformerSearchLegacy(\$term: String!) { searchPerformer(term: \$term, limit: 10) { $FIELDS } }",
                paged = false,
            ),
        )
    }
}
