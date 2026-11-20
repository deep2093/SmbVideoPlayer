package com.deepak.videolibrary.smb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ServerIdentityTest {
    private val real = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val other = UUID.fromString("99999999-8888-7777-6666-555555555555")

    @After fun reset() = ServerIdentity.clearForTest()

    @Test fun mainAddressIsAlwaysAcceptedAndRemembered() {
        ServerIdentity.check("192.168.71.250", "192.168.71.250", real)
        ServerIdentity.learn("192.168.71.250", "192.168.71.250", real)
        assertEquals(real.toString(), ServerIdentity.known("192.168.71.250"))
        // A replaced router answering on the main address simply updates the remembered server.
        ServerIdentity.check("192.168.71.250", "192.168.71.250", other)
        ServerIdentity.learn("192.168.71.250", "192.168.71.250", other)
        assertEquals(other.toString(), ServerIdentity.known("192.168.71.250"))
    }

    @Test fun alternateWithTheSameServerIsAccepted() {
        ServerIdentity.learn("192.168.71.250", "192.168.71.250", real)
        ServerIdentity.check("192.168.71.250", "192.168.71.249", real)
    }

    @Test(expected = SmbIdentityException::class)
    fun alternateWithADifferentServerIsRefusedBeforeLogin() {
        ServerIdentity.learn("192.168.71.250", "192.168.71.250", real)
        ServerIdentity.check("192.168.71.250", "192.168.71.249", other)
    }

    @Test fun firstSuccessfulServerIsRememberedWhenNothingIsKnown() {
        ServerIdentity.check("192.168.71.250", "192.168.71.249", real)
        ServerIdentity.learn("192.168.71.250", "192.168.71.249", real)
        assertEquals(real.toString(), ServerIdentity.known("192.168.71.250"))
        // An alternate never replaces what is already known.
        ServerIdentity.learn("192.168.71.250", "192.168.71.248", other)
        assertEquals(real.toString(), ServerIdentity.known("192.168.71.250"))
    }

    @Test fun serversWithoutAnIdAreNotChecked() {
        ServerIdentity.learn("nas", "nas", real)
        ServerIdentity.check("nas", "nas2", UUID(0L, 0L))
        ServerIdentity.check("nas", "nas2", null)
        ServerIdentity.learn("other", "other", UUID(0L, 0L))
        assertNull(ServerIdentity.known("other"))
    }
}
