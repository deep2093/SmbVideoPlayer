package com.skywing.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * M3 design brief 5.4, found by the M3 runner's width sweep: the video at the top stays in view while the window's
 * width changes the number of columns.
 */
class GridAnchorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theFirstVideoStaysOnTheFirstLineAcrossColumnChanges() {
        val state = LazyGridState()
        var columns by mutableIntStateOf(4)
        compose.setContent {
            Box(Modifier.size(800.dp, 500.dp)) {
                LazyVerticalGrid(GridCells.Fixed(columns), state = state) {
                    items((0 until 48).toList(), key = { it }) { Box(Modifier.aspectRatio(16f / 11f)) }
                }
                KeepFirstItemAcrossColumns(state)
            }
        }
        compose.waitForIdle()
        // Video 25 (index 24) starts the first line, with most of that line scrolled off the top.
        compose.runOnIdle { state.requestScrollToItem(24, scrollOffset = 100) }
        compose.waitForIdle()
        for (next in listOf(5, 4, 3, 2)) {
            columns = next
            compose.waitForIdle()
            val info = state.layoutInfo
            val firstRow = info.visibleItemsInfo.minOf { it.row }
            val firstLine = info.visibleItemsInfo.filter { it.row == firstRow }.map { it.index }
            assertTrue(24 in firstLine, "at $next columns the first line is $firstLine")
        }
    }
}
