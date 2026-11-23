package com.skywing.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B29: a mapped drive letter is a network share, like a \\server path, and Windows is asked again when it may change. */
class NetworkPathsTest {
    private val share = "\\\\nas\\films"

    /** Z: is mapped and connected; Y: is remembered but not connected now, so Windows doesn't call it a network drive. */
    private fun letters(clock: () -> Long = { 0L }, remote: (Char) -> Boolean = { it == 'Z' }) = DriveLetters(
        isRemote = remote,
        shareOf = { letter -> when (letter) { 'Z' -> share; 'Y' -> "\\\\nas\\tv"; else -> null } },
        clock = clock,
    )

    @Test fun mappedLettersAndShareAddressesAreBothNetworkShares() {
        val drives = letters()
        assertEquals("network share", drives.kindOf("Z:\\Films\\a.mkv"))
        assertEquals("network share", drives.kindOf("z:\\a.mkv"))
        assertEquals("network share", drives.kindOf("$share\\a.mkv"))
        assertEquals("network share", drives.kindOf("Y:\\a.mkv"))
        assertEquals("local file", drives.kindOf("C:\\Videos\\a.mkv"))
        assertEquals("none", drives.kindOf(null))
        assertFalse(drives.isNetwork("a.mkv"))
    }

    @Test fun aMappedLetterIsTurnedIntoTheSharesOwnAddress() {
        val drives = letters()
        assertEquals("$share\\Films\\a.mkv", drives.uncOf("Z:\\Films\\a.mkv"))
        assertEquals("$share\\Films\\a.mkv", drives.uncOf("Z:/Films/a.mkv"))
        assertEquals(share, drives.uncOf("Z:\\"))
        assertNull(drives.uncOf("C:\\Videos\\a.mkv"))
        assertNull(drives.uncOf("$share\\a.mkv"))
    }

    @Test fun aLetterThatChangesWhileSkyWingRunsIsSeenWithinSeconds() {
        var now = 0L
        var asked = 0
        var mapped = false
        val drives = letters(clock = { now }, remote = { asked++; mapped })
        assertFalse(drives.isNetwork("X:\\a.mkv"))
        mapped = true
        now += 1_000
        assertFalse(drives.isNetwork("X:\\a.mkv"), "within the keep time the last answer stands")
        assertEquals(1, asked)
        now += 5_000
        assertTrue(drives.isNetwork("X:\\a.mkv"))
        assertEquals(2, asked)
    }
}
