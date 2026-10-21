package com.deepak.videolibrary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnFoldersTest {
    @Test fun ownFoldersSurviveSaving() {
        val folders = listOf(
            OwnFolder("a", "Holiday", folders = listOf("Movies/Trip", ""), files = listOf("Clips/one.mp4")),
            OwnFolder("b", "Empty"),
        )
        val back = OwnFolderStore.decode(OwnFolderStore.encode(folders))
        assertEquals(folders, back)
        assertTrue(back[1].isEmpty)
    }

    @Test fun brokenOrIncompleteDataIsIgnored() {
        assertEquals(emptyList<OwnFolder>(), OwnFolderStore.decode("not json"))
        assertEquals(emptyList<OwnFolder>(), OwnFolderStore.decode(null))
        val partial = OwnFolderStore.decode("""[{"id":"x","name":""},{"id":"y","name":"Ok","files":["", "/a.mp4/"]}]""")
        assertEquals(listOf(OwnFolder("y", "Ok", files = listOf("a.mp4"))), partial)
    }

    @Test fun membersLimitTheLibraryQuery() {
        val scope = LibraryScope("nas/share", members = FolderMembers(listOf("Movies"), listOf("Clips/one.mp4")))
        val query = LibraryQueries.count(scope)
        assertTrue(query.sql.contains("v.relativePath=?"))
        assertTrue(query.sql.contains("substr(v.parentDir,1,length(?)+1)=?||'/'"))
        // key + three for the folder + one for the file
        assertEquals(5, query.argCount)
        val empty = LibraryQueries.count(LibraryScope("nas/share", members = FolderMembers(emptyList(), emptyList())))
        assertTrue(empty.sql.contains(" AND 0"))
    }
}
