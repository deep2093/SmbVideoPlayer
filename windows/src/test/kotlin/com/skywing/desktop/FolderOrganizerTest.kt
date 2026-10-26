package com.skywing.desktop

import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderOrganizerTest {
    private fun entry(path: String, size: Long = 1, modified: Long = 1) =
        WindowsMediaEntry(path, "PC", "PC", "This PC", size, modified)

    private val library = listOf(
        entry("C:\\Videos\\Films\\Tom Cruise, Nicole Kidman - Eyes.mp4", size = 10, modified = 5),
        entry("C:\\Videos\\Films\\Tom Cruise - Top Gun.mkv", size = 20, modified = 9),
        entry("C:\\Videos\\Films\\Old\\Brad Pitt movie part 2.mp4", size = 5, modified = 1),
        entry("C:\\Videos\\Other\\Peter - holiday.mp4"),
        entry("C:\\Videos\\Other\\Holiday with peter.mp4"),
    )

    @Test fun namesFollowTheThreeSwitches() {
        assertEquals(listOf("Tom Cruise", "Nicole Kidman"), WindowsVirtualNames.of("Tom Cruise, Nicole Kidman - Eyes.mp4"))
        assertEquals(listOf("Brad Pitt"), WindowsVirtualNames.of("Brad Pitt movie part 2.mp4"))
        assertEquals(listOf("Tom Cruise, Nicole Kidman"), WindowsVirtualNames.of("Tom Cruise, Nicole Kidman - Eyes.mp4", GroupingSwitches(splitAtCommas = false)))
        assertEquals(listOf("Brad Pitt Movie"), WindowsVirtualNames.of("Brad Pitt movie part 2.mp4", GroupingSwitches(firstTwoWords = false)))
    }

    @Test fun membersIncludeFolderContentsAndSingleFiles() {
        val shown = FolderLogic.members(listOf("c:\\videos\\films"), listOf("C:\\Videos\\Other\\Peter - holiday.mp4"), library)
        assertEquals(4, shown.size)
        assertFalse(FolderLogic.isWithin("C:\\Videos\\FilmsExtra\\a.mp4", "C:\\Videos\\Films"))
    }

    @Test fun selfFillingFoldersMatchNamesOrFolders() {
        assertEquals(2, FolderLogic.matchVideos(MatchFolder("1", "Peter", "peter"), library).size)
        assertEquals(2, FolderLogic.matchVideos(MatchFolder("2", "Films", "", "C:\\Videos\\Films", includeSubfolders = false), library).size)
        assertEquals(3, FolderLogic.matchVideos(MatchFolder("3", "Films", "", "C:\\Videos\\Films", includeSubfolders = true), library).size)
    }

    @Test fun virtualGroupsApplyRenamesHiddenAndHandFiling() {
        val set = VirtualSet("s", "Movies", folders = listOf("C:\\Videos\\Films"))
        val videos = FolderLogic.members(set.folders, set.files, library)
        val groups = FolderLogic.buildGroups(set, videos, GroupingSwitches(), emptyMap())
        assertEquals(setOf("tom cruise", "nicole kidman", "brad pitt"), groups.map { it.identity }.toSet())
        assertEquals(2, groups.first { it.identity == "tom cruise" }.videos.size)

        val renamed = set.copy(renames = mapOf("nicole kidman" to "Tom Cruise"), hidden = setOf("brad pitt"))
        val joined = FolderLogic.buildGroups(renamed, videos, GroupingSwitches(), emptyMap())
        assertEquals(listOf("tom cruise"), joined.map { it.identity })

        val filed = FolderLogic.buildGroups(set, videos, GroupingSwitches(), mapOf(library[2].path.lowercase() to "Favourites"))
        assertTrue(filed.any { it.identity == "favourites" })
        assertFalse(filed.any { it.identity == "brad pitt" })

        val bySize = FolderLogic.sorted(groups, "SIZE", descending = true)
        assertEquals("tom cruise", bySize.first().identity)
    }

    @Test fun organizerSavesAndLoadsEverything() {
        val dir = Files.createTempDirectory("skywing-folders")
        val store = FolderOrganizerStore(dir.resolve("folders.bin"))
        val organizer = Organizer(store) { listOf(WindowsCustomFolder("Old", listOf("C:\\Videos\\Other\\Peter - holiday.mp4"))) }
        assertEquals(listOf("Old"), organizer.data.own.map { it.name })

        val id = organizer.createOwn("Mine")!!
        organizer.setOwnMembers(id, listOf("C:\\Videos\\Films"), listOf("C:\\Videos\\Films\\Tom Cruise - Top Gun.mkv", "C:\\Videos\\Other\\Peter - holiday.mp4"))
        val setId = organizer.createSet("Movies")!!
        organizer.setSetMembers(setId, listOf("C:\\Videos\\Films"), emptyList())
        organizer.setDirectSetMembers(setId, listOf("\\\\server\\share\\films"), listOf(library[3].path))
        organizer.renameSubfolder(setId, "nicole kidman", "nicole")
        organizer.addDriveFolders(listOf("C:\\Videos\\Films", "C:\\Videos\\Films"), includeSubfolders = true)
        organizer.createCollection("Peter")
        organizer.assign(listOf("C:\\Videos\\Other\\Holiday with peter.mp4"), "Peter")
        organizer.updatePrefs { it.copy(columns = 4, listMode = true, tabs = listOf(1, 0), tabNames = mapOf(1 to "Drive"), tallPages = setOf("custom")) }
        organizer.stepCardSize(VIDEOS_PAGE, 1)
        organizer.selectSort("SIZE")
        organizer.selectSort("SIZE")

        val again = Organizer(FolderOrganizerStore(dir.resolve("folders.bin")))
        val own = again.data.own.first { it.id == id }
        // A file inside a chosen folder is not kept twice.
        assertEquals(listOf("C:\\Videos\\Other\\Peter - holiday.mp4"), own.files)
        assertEquals(mapOf("nicole kidman" to "Nicole"), again.data.sets.first().renames)
        assertEquals(listOf("\\\\server\\share\\films"), again.data.sets.first().directFolders)
        assertEquals(listOf(library[3].path), again.data.sets.first().directFiles)
        assertEquals(1, again.data.match.count { it.sourcePath != null })
        assertEquals(listOf(1, 0), again.data.prefs.tabs)
        assertEquals("Drive", again.data.prefs.tabName(1))
        // A grid size button gives way over a fixed column count.
        assertEquals(0, again.data.prefs.columns)
        assertEquals(210, again.data.prefs.videoCardSize)
        assertTrue(again.data.prefs.isTall("custom"))
        assertTrue(again.data.prefs.descending)
        assertEquals("Peter", again.data.assignments["c:\\videos\\other\\holiday with peter.mp4"])

        again.deleteOwn(id)
        assertNull(again.data.own.firstOrNull { it.id == id })
        assertNull(again.createOwn("bad/name"))
    }

    @Test fun moreVideosCanBeAddedToAnExistingVirtualSubfolder() {
        val dir = Files.createTempDirectory("skywing-virtual-add")
        val path = dir.resolve("folders.bin")
        val organizer = Organizer(FolderOrganizerStore(path))
        val id = organizer.createSet("People")!!
        organizer.addToSetGroup(id, "Favourites", emptyList(), listOf(library[0].path), library)
        organizer.addToSetGroup(id, "Favourites", emptyList(), listOf(library[3].path), library)
        val saved = Organizer(FolderOrganizerStore(path)).data.sets.first { it.id == id }
        val videos = FolderLogic.members(saved.folders, saved.files, library)
        val favourites = FolderLogic.buildGroups(saved, videos, GroupingSwitches(), emptyMap()).first { it.identity == "favourites" }
        assertEquals(setOf(library[0].path, library[3].path), favourites.videos.map { it.path }.toSet())
    }

    @Test fun cardSizesStepWithinTheirRange() {
        assertEquals(170, CardSizes.step(0, -1))
        assertEquals(470, CardSizes.step(420, 1))
        assertEquals(104, CardSizes.step(120, -1))
        assertEquals(88, CardSizes.step(88, -1))
        assertEquals(580, CardSizes.step(580, 1))
    }

    @Test fun otherAddressesAreTriedAndPathsKeepTheMainAddress() {
        assertEquals(listOf("192.168.71.249", "nas"), ShareHosts.parse("192.168.71.249, \\\\nas\\videos ; 192.168.71.249"))
        assertEquals(listOf("b", "a", "c"), ShareHosts.order("a", listOf("b", "c"), "B"))

        // Something listens on a local port for the "other address"; the main one points at a closed port.
        ServerSocket(0).use { open ->
            val closed = ServerSocket(0).let { val port = it.localPort; it.close(); port }
            val main = "127.0.0.1:$closed"
            val other = "127.0.0.1:${open.localPort}"
            ShareHosts.register("\\\\$main\\share", other)
            assertEquals(other, ShareHosts.resolve(main))
            val kept = "\\\\$main\\share\\film.mp4"
            assertEquals("\\\\$other\\share\\film.mp4", ShareHosts.reachable(kept))
            assertEquals(kept, ShareHosts.canonical(ShareHosts.reachable(kept)))
        }
    }
}
