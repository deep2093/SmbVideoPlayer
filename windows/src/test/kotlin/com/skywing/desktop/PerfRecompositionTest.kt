package com.skywing.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue

/** The performance recorder's recomposition count comes from Compose itself, so it must grow when Compose recomposes. */
class PerfRecompositionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theRecompositionTotalGrowsWithEveryRecompositionThatChangesSomething() {
        var count by mutableIntStateOf(0)
        compose.setContent { Text("Count $count") }
        compose.waitForIdle()
        val before = PerfMonitor.recompositionTotal()
        repeat(5) {
            count++
            compose.waitForIdle()
        }
        val grown = PerfMonitor.recompositionTotal() - before
        assertTrue(grown >= 5, "Compose recomposed 5 times, but the total grew by $grown")
    }
}
