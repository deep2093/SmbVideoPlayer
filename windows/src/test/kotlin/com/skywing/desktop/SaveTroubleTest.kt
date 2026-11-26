package com.skywing.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.google.gson.JsonObject
import java.nio.file.Files
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A change that can't be saved shows a short note instead of failing silently. */
class SaveTroubleTest {
    @get:Rule val compose = createComposeRule()

    private val folder = Files.createTempDirectory("skywing-savetrouble")

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    @Test fun aSaveThePersonMadeShowsTheNoteEveryTime() {
        val trouble = SaveTrouble()
        trouble.failed(background = false)
        assertTrue(trouble.showing)
        trouble.close()
        assertFalse(trouble.showing)
        trouble.failed(background = false)
        assertTrue(trouble.showing)
    }

    @Test fun backgroundSavesShowTheNoteOncePerSession() {
        val trouble = SaveTrouble()
        trouble.failed(background = true)
        assertTrue(trouble.showing)
        trouble.close()
        trouble.failed(background = true)
        assertFalse(trouble.showing)
        trouble.failed(background = false)
        assertTrue(trouble.showing)
    }

    @Test fun aFailedWriteTellsTheNoteAndStillThrows() {
        val trouble = SaveTrouble()
        // A plain file where the folder should be, so the write can't make its folder.
        val blocker = Files.writeString(folder.resolve("blocker"), "")
        assertFailsWith<Exception> { JsonFile(blocker.resolve("x.json"), trouble = trouble).write(JsonObject()) }
        assertTrue(trouble.showing)
    }

    @Test fun aGoodWriteSaysNothing() {
        val trouble = SaveTrouble()
        JsonFile(folder.resolve("ok.json"), trouble = trouble).write(JsonObject())
        assertFalse(trouble.showing)
    }

    @Test fun theNoteShowsAndCloseHidesIt() {
        val trouble = SaveTrouble()
        compose.setContent { SaveNotice(trouble) }
        compose.onNodeWithText("Couldn't save your last change.").assertDoesNotExist()
        trouble.failed(background = false)
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't save your last change.").assertExists()
        compose.onNodeWithText("If this keeps happening, check that your disk isn't full.").assertExists()
        compose.onNodeWithText("Close").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't save your last change.").assertDoesNotExist()
        trouble.failed(background = false)
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't save your last change.").assertExists()
    }
}
