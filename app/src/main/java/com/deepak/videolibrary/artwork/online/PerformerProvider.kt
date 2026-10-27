package com.deepak.videolibrary.artwork.online

/** One possible match returned by a provider. [imageUrl] is the picture to use; [previewUrl] a smaller one to show. */
data class PerformerCandidate(
    val providerId: String,
    val providerName: String,
    val performerId: String,
    val name: String,
    val disambiguation: String?,
    val imageUrl: String?,
    val previewUrl: String?,
    /** Every usable photo of this performer, best first (starts with [imageUrl]). */
    val images: List<String> = listOfNotNull(imageUrl),
)

enum class ProviderFailure(val message: String) {
    NO_KEY("Add this provider's API key in Settings > Scene metadata."),
    UNAUTHORIZED("The provider rejected the API key (401). Check or replace the key."),
    FORBIDDEN("The provider refused this request (403). Your key may not have access."),
    RATE_LIMITED("The provider's rate limit was reached (429). Try again later."),
    NETWORK("Could not reach the provider. Check the connection."),
    BAD_RESPONSE("The provider returned an unexpected API response."),
    NON_JSON("The provider returned a webpage or non-JSON response instead of GraphQL data."),
    GRAPHQL_SCHEMA("The provider does not support this GraphQL operation."),
    SERVER_ERROR("The provider returned a temporary server error. Try again later."),
    BLOCKED_BY_SETTINGS("Downloads are blocked by your network settings (Wi-Fi only or metered)."),
}

class ProviderException(val failure: ProviderFailure, cause: Throwable? = null) : Exception(failure.message, cause)

/** A documented performer metadata service. Each one is a separate implementation. */
interface PerformerProvider {
    val id: String
    val displayName: String

    /** Searches by name; [page] starts at 1 and gives the next [PAGE_SIZE] matches. Throws [ProviderException] with a clear reason on failure. */
    suspend fun search(query: String, apiKey: String, endpoint: String? = null, page: Int = 1): List<PerformerCandidate>

    companion object {
        /** Matches asked for per page; a page with fewer than this many is the last one. */
        const val PAGE_SIZE = 10
    }
}
