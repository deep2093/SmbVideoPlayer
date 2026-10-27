package com.deepak.videolibrary.metadata
import com.deepak.videolibrary.artwork.online.ProviderFailure
import java.util.Locale

internal object GraphQlFailureClassifier {
    fun classify(message: String): ProviderFailure {
        val text = message.lowercase(Locale.ROOT)
        return when {
            listOf("unauthor", "not authorized", "not authenticated", "authentication",
                "api key", "invalid token", "invalid credential", "forbidden",
                "access denied", "permission denied").any { it in text } ->
                ProviderFailure.UNAUTHORIZED
            "rate limit" in text || "too many requests" in text -> ProviderFailure.RATE_LIMITED
            listOf("cannot query field", "unknown argument", "unknown type",
                "validation error", "was not provided", "is not defined").any { it in text } ->
                ProviderFailure.GRAPHQL_SCHEMA
            else -> ProviderFailure.BAD_RESPONSE
        }
    }
}
