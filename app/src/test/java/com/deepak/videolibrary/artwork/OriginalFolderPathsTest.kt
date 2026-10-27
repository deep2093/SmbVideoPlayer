package com.deepak.videolibrary.artwork

import com.deepak.videolibrary.data.OriginalRootStore
import org.junit.Assert.assertEquals
import org.junit.Test

class OriginalFolderPathsTest {
    @Test fun coversAddedFoldersAndEveryRealSubfolderOnly() {
        val paths = OriginalFolderPaths.within(
            listOf("Movies/Tom"),
            listOf("Movies/Tom/Scene A", "Movies/Tom/Set/Part 1", "Movies/Other", "Movies/Tommy"),
        )
        assertEquals(listOf("Movies/Tom", "Movies/Tom/Scene A", "Movies/Tom/Set/Part 1", "Movies/Tom/Set"), paths)
    }

    @Test fun rootsAreTrimmedDeduplicatedAndNotNested() {
        assertEquals(listOf("Movies/Tom"), OriginalRootStore.normalize(listOf("/Movies/Tom/", "Movies/Tom", "Movies/Tom/Sub", "")))
    }
}
