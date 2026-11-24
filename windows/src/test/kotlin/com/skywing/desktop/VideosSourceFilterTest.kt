package com.skywing.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** B39: the source chip on the Videos page filters Continue watching as it filters the grid. */
class VideosSourceFilterTest {
    @get:Rule val compose = createComposeRule()

    private val folder: Path = Files.createTempDirectory("skywing-videos-source")

    // The network video's path is local on purpose: the chip goes by the source, and a test must not look up a host.
    private val onThisPc = folder.resolve("On This PC.mkv").toString()
    private val onAShare = folder.resolve("share").resolve("On A Share.mkv").toString()

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    @Test fun continueWatchingShowsOnlyTheChosenSourcesVideos() {
        val hub = HubState()
        show(hub, pc = listOf(onThisPc), network = listOf(onAShare), watched = listOf(onThisPc to 60.0, onAShare to 120.0))

        assertEquals(listOf(PC_PROGRESS, SHARE_PROGRESS), continued(), "All")
        compose.runOnIdle { hub.videoSource = 1 }
        assertEquals(listOf(PC_PROGRESS), continued(), "This PC")
        compose.runOnIdle { hub.videoSource = 2 }
        assertEquals(listOf(SHARE_PROGRESS), continued(), "Network")
    }

    @Test fun thisPcWithNoVideosOffersToAddAFolderInsteadOfShareVideos() {
        val hub = HubState().apply { videoSource = 1 }
        var asked = 0
        show(hub, pc = emptyList(), network = listOf(onAShare), watched = listOf(onAShare to 120.0), onAddFolder = { asked++ })

        assertEquals(emptyList(), continued())
        compose.onNodeWithText("Add folder").performClick()
        compose.waitForIdle()
        assertEquals(1, asked, "Add folder should open Files")
    }

    private fun show(
        hub: HubState,
        pc: List<String>,
        network: List<String>,
        watched: List<Pair<String, Double>>,
        onAddFolder: () -> Unit = {},
    ) {
        val store = WindowsMediaIndex(folder.resolve("media-index.bin")).apply {
            if (pc.isNotEmpty()) replaceSource("PC", "PC", "This PC", pc.map { FoundVideo(File(it), 1, 2) })
            if (network.isNotEmpty()) replaceSource("SMB:films", "SMB", "Films", network.map { FoundVideo(File(it), 1, 2) })
        }
        val library = LibraryModel(store).also { runBlocking { it.reload() } }
        val organizer = Organizer(FolderOrganizerStore(folder.resolve("folders.bin")))
        val history = watched.associate { (path, position) -> path.lowercase() to WindowsWatchRecord(path, position, 600.0, updated = 1) }
        compose.setContent {
            VideosScreen(library, organizer, hub, SelectionState(), history, { _, _ -> }, { _, _ -> }, {}, onAddFolder = onAddFolder)
        }
    }

    /** The progress lines shown in Continue watching; the grid's own cards show none. */
    private fun continued(): List<String> {
        compose.waitForIdle()
        return listOf(PC_PROGRESS, SHARE_PROGRESS).filter { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val PC_PROGRESS = "1:00 of 10:00"
        const val SHARE_PROGRESS = "2:00 of 10:00"
    }
}
