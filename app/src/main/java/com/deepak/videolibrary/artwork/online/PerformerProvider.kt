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
)

enum class ProviderFailure(val message: String) {
    NO_KEY("Add an API key for this provider in Settings > Artwork."),
    UNAUTHORIZED("The provider rejected the API key (401). Check or replace the key."),
    FORBIDDEN("The provider refused this request (403). Your key may not have access."),
    RATE_LIMITED("The provider's rate limit was reached (429). Try again later."),
    NETWORK("Could not reach the provider. Check the connection."),
    BAD_RESPONSE("The provider sent a response the app could not read."),
    BLOCKED_BY_SETTINGS("Downloads are blocked by your network settings (Wi-Fi only or metered)."),
}

class ProviderException(val failure: ProviderFailure, cause: Throwable? = null) : Exception(failure.message, cause)

/** A documented performer metadata service. Each one is a separate implementation. */
interface PerformerProvider {
    val id: String
    val displayName: String

    /** Searches by name. Throws [ProviderException] with a clear reason on failure. */
    suspend fun search(query: String, apiKey: String, endpoint: String? = null): List<PerformerCandidate>
}
