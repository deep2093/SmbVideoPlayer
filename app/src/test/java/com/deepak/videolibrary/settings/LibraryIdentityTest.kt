package com.deepak.videolibrary.settings

import org.junit.Assert.*
import org.junit.Test

class LibraryIdentityTest {
    @Test fun credentialsDoNotChangeIdentityButAccountRootAndShareDo() {
        val base = SmbSettings(host="NAS",share="Movies",username="Alice",rootPath="Film/Sub")
        val id = LibraryIdentity.of(base)
        assertEquals(id,LibraryIdentity.of(base.copy(host=" nas ",share="movies",password="secret",savePassword=true,requireEncryption=true)))
        assertEquals(id,LibraryIdentity.of(base.copy(rootPath="/Film\\Sub/")))
        assertEquals(id,LibraryIdentity.of(base.copy(alternateHosts="192.168.71.249")))
        assertNotEquals(id,LibraryIdentity.of(base.copy(rootPath="Film")))
        assertNotEquals(id,LibraryIdentity.of(base.copy(username="Bob")))
        assertNotEquals(id,LibraryIdentity.of(base.copy(share="Other")))
        assertNotEquals(id,LibraryIdentity.of(base.copy(domain="WORK")))
        assertFalse(id.contains("Alice"))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsStartingFolderTraversal() {
        SettingsStore.normalizeRoot("Film/../Private")
    }
}
