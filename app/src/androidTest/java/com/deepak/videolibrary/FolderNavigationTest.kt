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
    @Test fun originalFolderBackWaitsForTheCorrectRowsBeforeRestoring() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val services=ServiceLocator(app,"original-navigation-${UUID.randomUUID()}",SettingsStore(app))
        val owner=object:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
        val vm=ViewModelProvider(owner,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun<T:ViewModel> create(modelClass:Class<T>):T=LibraryViewModel(app,services) as T
        })[LibraryViewModel::class.java]
        val visible=mutableStateOf(true)
        val root=vm.settings.rootPath.trim('/')
        try {
            repeat(80) { i ->
                val parent=listOf(root,"Folder%03d".format(i)).filter(String::isNotBlank).joinToString("/")
                services.db.dao().insertVideo(VideoEntity(relativePath="$parent/A.mp4",name="A.mp4",parentDir=parent,size=1,modified=1,groupKey="A",lastSeenSession=1,addedAt=1))
            }
            vm.foldersTab=2
            rule.setContent { if(visible.value) MaterialTheme { AppRoot(vm) } }
            rule.waitUntil(15000) { rule.onAllNodesWithTag("original-folder-grid").fetchSemanticsNodes().isNotEmpty() && vm.originalListing.value.rows.size==80 }
            rule.onNodeWithTag("original-folder-grid").performScrollToIndex(40)
            rule.waitForIdle()
            val before=rule.onNodeWithText("Folder040",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top
            rule.onNodeWithText("Folder040").performClick()
            rule.waitUntil(15000) { vm.originalListing.value.path.endsWith("Folder040") && rule.onAllNodesWithText("A.mp4").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("Back").performClick() // the folder bar has a back arrow
            rule.waitUntil(15000) { vm.originalListing.value.path==root && rule.onAllNodesWithText("Folder040").fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle()
            rule.onNodeWithText("Folder040").assertIsDisplayed()
            assertEquals(before,rule.onNodeWithText("Folder040",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top,1f)
        } finally {
            rule.runOnIdle { visible.value=false }
            rule.waitForIdle(); owner.viewModelStore.clear(); services.db.close()
        }
    }

}
