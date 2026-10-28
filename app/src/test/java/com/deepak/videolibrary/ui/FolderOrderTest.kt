package com.deepak.videolibrary.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderOrderTest {
    @Test fun placedFoldersComeFirstInSavedOrder() {
        val items = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("e", "c", "a", "b", "d"), FolderOrder.apply(items, listOf("e", "c"), { it }).let { listOf(it[0], it[1], it[2], it[3], it[4]) })
    }

    @Test fun fifthMovesToThird() {
        assertEquals(listOf("a", "b", "e", "c", "d"), FolderOrder.move(listOf("a", "b", "c", "d", "e"), 4, 2))
    }

    @Test fun unknownSavedKeysAreIgnored() {
        assertEquals(listOf("b", "a"), FolderOrder.apply(listOf("a", "b"), listOf("zzz", "b"), { it }))
    }
}
