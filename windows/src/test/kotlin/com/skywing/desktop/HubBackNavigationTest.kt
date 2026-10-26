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
