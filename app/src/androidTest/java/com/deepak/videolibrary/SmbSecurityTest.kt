package com.deepak.videolibrary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbProfile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmbSecurityTest {
    @Test fun signingAndEncryptionReadFromDisposableSmb3Server() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("smbFixture")=="true")
        val profile=SmbProfile("10.0.2.2","fixtures","","vlfixture","ci-fixture-only",
            requireSigning=true,requireEncryption=true)
        SmbConnection(profile).use { conn ->
            conn.open()
            assertTrue(conn.list("Movies").any {it.name=="Tom Cruise - Fixture.mp4"})
            conn.openFile("Movies/Tom Cruise - Fixture.mp4").use { file ->
                assertTrue(file.read(ByteArray(1024),0,0,1024)>0)
            }
        }
    }
}
