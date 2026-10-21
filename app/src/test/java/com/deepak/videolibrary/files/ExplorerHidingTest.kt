package com.deepak.videolibrary.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerHidingTest {
    @Test fun systemFoldersAreRecognised() {
        assertTrue(ExplorerHiding.isSystemName("\$RECYCLE.BIN"))
        assertTrue(ExplorerHiding.isSystemName("System Volume Information"))
        assertTrue(ExplorerHiding.isSystemName(".TPDLNA"))
        assertTrue(ExplorerHiding.isSystemName("@eaDir"))
        assertFalse(ExplorerHiding.isSystemName("Movies"))
        assertFalse(ExplorerHiding.isSystemName("1"))
    }

    @Test fun hiddenFoldersHideEverythingInside() {
        val hidden = setOf("Movies/Old")
        assertTrue(ExplorerHiding.isHiddenPath("Movies/Old", hidden))
        assertTrue(ExplorerHiding.isHiddenPath("Movies/Old/a.mp4", hidden))
        assertFalse(ExplorerHiding.isHiddenPath("Movies/Older", hidden))
        assertFalse(ExplorerHiding.isHiddenPath("Movies", hidden))
    }

    @Test fun systemHidingCanBeTurnedOff() {
        assertTrue(ExplorerHiding.shouldHide("\$RECYCLE.BIN", "\$RECYCLE.BIN", hideSystem = true, hiddenPaths = emptySet()))
        assertFalse(ExplorerHiding.shouldHide("\$RECYCLE.BIN", "\$RECYCLE.BIN", hideSystem = false, hiddenPaths = emptySet()))
    }
}
