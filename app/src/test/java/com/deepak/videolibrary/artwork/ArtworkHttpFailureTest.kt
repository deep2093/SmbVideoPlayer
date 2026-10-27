package com.deepak.videolibrary.artwork

import com.deepak.videolibrary.artwork.online.ArtworkHttp
import com.deepak.videolibrary.artwork.online.ProviderFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkHttpFailureTest {
    @Test fun graphQlValidationErrorOn422IsASchemaMismatchNotAnUnreadableReply() {
        // gqlgen (stash-box) answers an unknown field with HTTP 422 and a JSON errors body.
        val body = """{"errors":[{"message":"Cannot query field \"searchScenes\" on type \"Query\"."}],"data":null}"""
        assertEquals(ProviderFailure.GRAPHQL_SCHEMA, ArtworkHttp.failureFor(422, body.toByteArray(), api = true))
    }

    @Test fun authErrorInsideA400BodyIsReportedAsAuthentication() {
        val body = """{"errors":[{"message":"Not authorized"}]}"""
        assertEquals(ProviderFailure.UNAUTHORIZED, ArtworkHttp.failureFor(400, body.toByteArray(), api = true))
    }

    @Test fun webPageInsteadOfGraphQlIsNonJson() {
        assertEquals(ProviderFailure.NON_JSON, ArtworkHttp.failureFor(404, "<html>Not found</html>".toByteArray(), api = true))
    }

    @Test fun statusCodesKeepTheirMeaning() {
        assertEquals(ProviderFailure.UNAUTHORIZED, ArtworkHttp.failureFor(401, null, api = true))
        assertEquals(ProviderFailure.RATE_LIMITED, ArtworkHttp.failureFor(429, null, api = true))
        assertEquals(ProviderFailure.SERVER_ERROR, ArtworkHttp.failureFor(503, null, api = true))
    }

    @Test fun missingImageIsJustABadResponse() {
        assertEquals(ProviderFailure.BAD_RESPONSE, ArtworkHttp.failureFor(404, "<html/>".toByteArray(), api = false))
    }
}
