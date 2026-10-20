package com.deepak.videolibrary.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTextTest {
    // Placeholder values only; this is not a real key.
    private val sample = """
        [Interface]
        PrivateKey = PLACEHOLDER_NOT_A_REAL_KEY
        Address = 10.0.0.2/32

        [Peer]
        PublicKey = PLACEHOLDER_PUBLIC
        AllowedIPs = 192.168.71.0/24
        Endpoint = vpn.example.invalid:51820
    """.trimIndent()

    @Test
    fun addsIncludedApplicationsDirectlyUnderInterface() {
        val lines = ConfigText.restrictToApp(sample, "com.deepak.videolibrary").lines()
        assertEquals("[Interface]", lines[0])
        assertEquals("IncludedApplications = com.deepak.videolibrary", lines[1])
    }

    @Test
    fun replacesExistingApplicationLists() {
        val text = sample.replace(
            "Address = 10.0.0.2/32",
            "Address = 10.0.0.2/32\nexcludedapplications = com.other\nIncludedApplications = com.someone.else",
        )
        val result = ConfigText.restrictToApp(text, "com.deepak.videolibrary")
        assertEquals(1, result.lines().count { it.startsWith("IncludedApplications") })
        assertFalse(result.contains("com.other"))
        assertFalse(result.contains("com.someone.else"))
    }

    @Test
    fun keepsEverythingElseUntouched() {
        val result = ConfigText.restrictToApp(sample, "com.deepak.videolibrary")
        for (line in sample.lines()) assertTrue("missing: $line", result.lines().contains(line))
    }

    @Test
    fun handlesDebugPackageSuffix() {
        val result = ConfigText.restrictToApp(sample, "com.deepak.videolibrary.preview")
        assertTrue(result.contains("IncludedApplications = com.deepak.videolibrary.preview"))
    }

    @Test
    fun leavesTextWithoutInterfaceSectionUnchanged() {
        val text = "[Peer]\nPublicKey = x"
        assertEquals(text, ConfigText.restrictToApp(text, "com.deepak.videolibrary"))
    }

    @Test
    fun headerMatchIsCaseInsensitive() {
        val result = ConfigText.restrictToApp("[interface]\nPrivateKey = x", "pkg.name")
        assertEquals("IncludedApplications = pkg.name", result.lines()[1])
    }
}
