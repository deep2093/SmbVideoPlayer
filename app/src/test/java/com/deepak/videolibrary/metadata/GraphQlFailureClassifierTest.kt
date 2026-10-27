package com.deepak.videolibrary.metadata
import com.deepak.videolibrary.artwork.online.ProviderFailure
import org.junit.Assert.assertEquals
import org.junit.Test
class GraphQlFailureClassifierTest {
    @Test fun unknownQueryIsNotMistakenForBadApiKey() {
        assertEquals(ProviderFailure.GRAPHQL_SCHEMA,
            GraphQlFailureClassifier.classify("Cannot query field queryScenes on type Query"))
    }
    @Test fun rejectedTokenIsReportedAsAuthenticationError() {
        assertEquals(ProviderFailure.UNAUTHORIZED,
            GraphQlFailureClassifier.classify("Not authorized; invalid token"))
    }
    @Test fun rateLimitProducesAnActionableError() {
        assertEquals(ProviderFailure.RATE_LIMITED,
            GraphQlFailureClassifier.classify("Too many requests: rate limit"))
    }
}