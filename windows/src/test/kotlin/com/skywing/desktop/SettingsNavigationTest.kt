package com.skywing.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M3 item 7 (M3 design brief 5.6): Back, Esc, the mouse back button and Settings again return to the list. */
class SettingsNavigationTest {
    @Test fun backClosesTheOpenPageWhenTheListIsHidden() {
        val nav = SettingsNavigation()
        nav.open = "Playback"
        assertTrue(nav.back(listShown = false))
        assertNull(nav.open)
    }

    @Test fun backOnTheListIsLeftToTheShell() {
        val nav = SettingsNavigation()
        assertFalse(nav.back(listShown = false))
    }

    @Test fun withTwoPanesTheListStaysAndBackKeepsThePage() {
        val nav = SettingsNavigation()
        nav.open = "Playback"
        assertFalse(nav.back(listShown = true))
        assertEquals("Playback", nav.open)
    }
}
