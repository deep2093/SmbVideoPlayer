package com.skywing.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubBackNavigationTest {
    private val data = OrganizerData(
        own = listOf(OwnFolder("custom", "Custom"), OwnFolder("child", "Child", parentId = "custom")),
        sets = listOf(VirtualSet("virtual", "Virtual"), VirtualSet("nested", "Nested", parentId = "virtual")),
    )

    @Test fun backStepsOutOfNestedCustomAndVirtualFolders() {
        val hub = HubState()
        hub.ownId = "child"
        hub.ownGroup = "group"
        assertTrue(hub.navigateBack(data))
        assertNull(hub.ownGroup)
        assertEquals("child", hub.ownId)
        assertTrue(hub.navigateBack(data))
        assertEquals("custom", hub.ownId)
        assertTrue(hub.navigateBack(data))
        assertNull(hub.ownId)

        hub.tab = 1
        hub.setId = "nested"
        hub.setGroup = "performer"
        assertTrue(hub.navigateBack(data))
        assertNull(hub.setGroup)
        assertTrue(hub.navigateBack(data))
        assertEquals("virtual", hub.setId)
        assertTrue(hub.navigateBack(data))
        assertNull(hub.setId)
        assertFalse(hub.navigateBack(data))
    }

    @Test fun backLooksOnlyAtTheTabOnScreen() {
        // A Custom folder's actor page is left open, and the Virtual tab shows an actor page.
        val hub = HubState().apply { ownId = "child"; ownGroup = "group"; tab = 1; setId = "nested"; setGroup = "performer" }
        assertTrue(hub.navigateBack(data))
        assertNull(hub.setGroup, "the first Esc closes the actor page on screen")
        assertEquals(actorFocusKey("performer"), hub.focusTile)
        assertEquals("group", hub.ownGroup, "the Custom tab keeps its place")
        assertEquals("child", hub.ownId)
        // Back on the Custom tab, Esc there leaves the Virtual folder alone.
        hub.tab = 0
        assertTrue(hub.navigateBack(data))
        assertNull(hub.ownGroup)
        assertEquals("nested", hub.setId)
    }

    @Test fun aDriveFolderStepsBackToItsOwnTabsRoots() {
        val drives = data.copy(
            own = listOf(OwnFolder("custom", "Custom", folders = listOf("C:\\Own"))),
            sets = listOf(VirtualSet("virtual", "Virtual", directFolders = listOf("C:\\Own\\Clips"))),
        )
        // On the Custom tab the Virtual folder's root is not a root, so the drive folder goes up one level.
        val hub = HubState().apply { ownId = "custom"; setId = "virtual"; drivePath = "C:\\Own\\Clips" }
        assertTrue(hub.navigateBack(drives))
        assertEquals("C:\\Own", hub.drivePath)
        // On the Virtual tab it is a root, so the drive folder closes.
        val set = HubState().apply { tab = 1; ownId = "custom"; setId = "virtual"; drivePath = "C:\\Own\\Clips" }
        assertTrue(set.navigateBack(drives))
        assertNull(set.drivePath)
    }

    @Test fun backLeavesSelectionBeforeNavigatingUp() {
        val hub = HubState()
        hub.ownId = "child"
        hub.folderPage = "own:child"
        hub.folderKeys = setOf("file:one")
        assertTrue(hub.navigateBack(data))
        assertNull(hub.folderPage)
        assertEquals("child", hub.ownId)
    }
}
