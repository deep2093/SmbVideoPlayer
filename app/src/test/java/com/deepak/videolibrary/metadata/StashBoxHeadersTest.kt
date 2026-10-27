package com.deepak.videolibrary.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StashBoxHeadersTest {
    @Test fun tpdbUsesBearerTokenAndNeverApiKey() {
        val headers = StashBoxHeaders.forProvider(SceneMetadataSettings.TPDB, "example-token")
        assertEquals("Bearer example-token", headers["Authorization"])
        assertFalse(headers.containsKey("ApiKey"))
    }

    @Test fun stashDbKeepsItsExistingApiKeyHeader() {
        val headers = StashBoxHeaders.forProvider(SceneMetadataSettings.STASHDB, "example-token")
        assertEquals("example-token", headers["ApiKey"])
        assertNull(headers["Authorization"])
    }
}
