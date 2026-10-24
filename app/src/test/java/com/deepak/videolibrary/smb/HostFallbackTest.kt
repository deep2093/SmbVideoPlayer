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
}
