package com.deepak.videolibrary.metadata

/**
 * Provider-specific Stash-box GraphQL authentication.
 *
 * StashDB accepts ApiKey; ThePornDB requires Authorization: Bearer.
 * Do not log these headers or retry credentials at another provider endpoint.
 */
internal object StashBoxHeaders {
    fun forProvider(provider: String, apiKey: String): Map<String, String> =
        if (provider == SceneMetadataSettings.TPDB)
            mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json")
        else
            mapOf("ApiKey" to apiKey, "Accept" to "application/json")
}
