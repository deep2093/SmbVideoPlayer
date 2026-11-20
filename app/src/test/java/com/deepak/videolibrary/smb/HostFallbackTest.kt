package com.deepak.videolibrary.smb

import org.junit.Assert.assertEquals
import org.junit.Test

class HostFallbackTest {
    @Test fun parsesCommaSpaceAndSmbForms() {
        assertEquals(listOf("192.168.71.249", "nas.local"), HostFallback.parse(" 192.168.71.249,  smb://nas.local/share ; 192.168.71.249"))
        assertEquals(emptyList<String>(), HostFallback.parse("  "))
    }

    @Test fun lastAnsweredGoesFirstThenTheMainAddress() {
        assertEquals(listOf("192.168.71.250", "192.168.71.249"), HostFallback.order("192.168.71.250", listOf("192.168.71.249"), null))
        assertEquals(listOf("192.168.71.249", "192.168.71.250"), HostFallback.order("192.168.71.250", listOf("192.168.71.249"), "192.168.71.249"))
        assertEquals(listOf("192.168.71.250", "192.168.71.249"), HostFallback.order("192.168.71.250", listOf("192.168.71.249"), "192.168.71.248"))
        HostFallback.remember("192.168.71.250", "192.168.71.249")
        assertEquals("192.168.71.249", HostFallback.lastAnswered("192.168.71.250"))
    }

    @Test fun splitsHostAndPort() {
        assertEquals("nas" to null, HostFallback.split("nas"))
        assertEquals("192.168.71.250" to 4450, HostFallback.split(" 192.168.71.250:4450 "))
        assertEquals("nas" to null, HostFallback.split("nas:99999"))
        assertEquals("fe80::1" to null, HostFallback.split("fe80::1"))
        assertEquals("fe80::1" to 445, HostFallback.split("[fe80::1]:445"))
    }
}
