package com.deepak.videolibrary

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.settings.SettingsStore
import com.deepak.videolibrary.ui.AppRoot
import com.deepak.videolibrary.ui.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FolderNavigationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun openingAndClosingRealVirtualFolderRestoresVisibleCard() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val services = ServiceLocator(app,"navigation-test-${UUID.randomUUID()}",SettingsStore(app))
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = LibraryViewModel(app,services) as T
        })[LibraryViewModel::class.java]
        val visible = mutableStateOf(true)
        try {
            repeat(80) { i ->
                services.db.dao().insertVideo(VideoEntity(relativePath="Test/$i.mp4",name="$i.mp4",parentDir="Test",size=1,modified=1,groupKey="scroll %03d".format(i),lastSeenSession=1,addedAt=1))
            }
            rule.setContent { if(visible.value) MaterialTheme { AppRoot(vm) } }
            rule.waitUntil(15000) { rule.onAllNodesWithTag("virtual-folder-grid").fetchSemanticsNodes().isNotEmpty() && vm.groups.value.size == 80 }
            rule.onNodeWithTag("virtual-folder-grid").performScrollToIndex(40)
            rule.waitForIdle()
            val grid = rule.onNodeWithTag("virtual-folder-grid").fetchSemanticsNode().boundsInRoot
            val matches = SemanticsMatcher("scroll folder title") { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Scroll ") } == true }
            val card = rule.onAllNodes(matches,useUnmergedTree=true).fetchSemanticsNodes()
                .filter { it.boundsInRoot.top >= grid.top && it.boundsInRoot.bottom <= grid.bottom }
                .minWith(compareBy({it.boundsInRoot.top},{it.boundsInRoot.left}))
            val title = card.config[SemanticsProperties.Text].first().text
            val top = card.boundsInRoot.top
            rule.onNodeWithText(title).performClick()
            rule.onNodeWithContentDescription("Back to folders").assertExists().performClick()
            rule.waitForIdle()
            rule.onNodeWithText(title).assertIsDisplayed()
            val restored = rule.onNodeWithText(title,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top
            assertEquals(top,restored,1f)
        } finally {
            rule.runOnIdle { visible.value = false }
            rule.waitForIdle()
            owner.viewModelStore.clear()
            services.db.close()
        }
    }
}
