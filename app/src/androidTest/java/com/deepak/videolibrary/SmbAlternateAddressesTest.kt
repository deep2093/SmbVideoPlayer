package com.deepak.videolibrary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbProfile
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the disposable SMB server started by the device CI job. */
@RunWith(AndroidJUnit4::class)
class SmbAlternateAddressesTest {
    @Test fun opensSameShareWhenPrimaryAddressDoesNotAnswer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("smbFixture") == "true")
        val profile = SmbProfile(
            host = "127.0.0.1",
            share = "fixtures",
            rootPath = "",
            username = "vlfixture",
            password = "ci-fixture-only",
            alternateHosts = listOf("10.0.2.2"),
        )
        SmbConnection(profile).use { connection ->
            connection.open()
            assertTrue(connection.list("Movies").any { it.name == "Tom Cruise - Fixture.mp4" })
        }
    }
}
