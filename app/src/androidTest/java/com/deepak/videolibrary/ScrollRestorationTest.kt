package com.deepak.videolibrary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepak.videolibrary.ui.ScrollToTopOnChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the "stay where I was" behaviour of the library lists. */
@RunWith(AndroidJUnit4::class)
class ScrollRestorationTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun keepsPositionWhenReenteringAndResetsWhenKeyChanges() {
        lateinit var state: LazyGridState
        lateinit var scope: CoroutineScope
        var visible by mutableStateOf(true)
        var key by mutableStateOf("a")
        rule.setContent {
            state = rememberLazyGridState()
            scope = rememberCoroutineScope()
            if (visible) {
                ScrollToTopOnChange(key, state)
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.size(200.dp), state = state) {
                    items((0 until 200).toList()) { Box(Modifier.height(50.dp)) { Text("Item $it") } }
                }
            }
        }
        rule.runOnIdle { scope.launch { state.scrollToItem(40, 17) } }
        rule.waitForIdle()
        assertEquals(40, state.firstVisibleItemIndex)
        assertEquals(17, state.firstVisibleItemScrollOffset)

        // Leave and come back (folder opened and closed, player, tab switch): position is kept.
        rule.runOnIdle { visible = false }
        rule.waitForIdle()
        rule.runOnIdle { visible = true }
        rule.waitForIdle()
        assertEquals(40, state.firstVisibleItemIndex)
        assertEquals(17, state.firstVisibleItemScrollOffset)

        // A real filter change while visible still returns to the top.
        rule.runOnIdle { key = "b" }
        rule.waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex)
    }
}
