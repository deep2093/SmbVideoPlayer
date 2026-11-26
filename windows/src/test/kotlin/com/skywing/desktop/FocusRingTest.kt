package com.skywing.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.skywing.desktop.ui.adaptive.LocalFocusVisible
import com.skywing.desktop.ui.adaptive.followFocusVisibility
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M3 item 9 (M3 design brief 5.7, A6): a control with keyboard focus shows a 2 dp ring in the theme's focus colour,
 * but only while the keyboard is in use; a click or tap hides it, as Windows hides focus rectangles.
 */
@OptIn(ExperimentalTestApi::class)
class FocusRingTest {
    @get:Rule val compose = createComposeRule()

    private var focusColour = Color.Unspecified

    private fun button(focusVisible: Boolean) = compose.setContent {
        focusColour = LocalSkyTheme.current.colors.focus
        CompositionLocalProvider(LocalFocusVisible provides focusVisible) {
            SkyIconButton(SkyIcon.Play, "Play", {}, Modifier.testTag("button"))
        }
    }

    /** The pixel 1 dp in from the left edge, half way down: inside the ring when it is drawn. */
    private fun edge(): Color {
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("button").captureToImage().toPixelMap()
        return pixels[1, pixels.height / 2]
    }

    @Test fun aFocusedControlShowsTheRingWhileTheKeyboardIsInUse() {
        button(focusVisible = true)
        compose.onNodeWithTag("button").requestFocus()
        assertEquals(focusColour, edge())
    }

    @Test fun noRingAfterAClickOrTap() {
        button(focusVisible = false)
        compose.onNodeWithTag("button").requestFocus()
        assertNotEquals(focusColour, edge())
    }

    @Test fun noRingWithoutFocus() {
        button(focusVisible = true)
        assertNotEquals(focusColour, edge())
    }

    @Test fun aKeyShowsTheRingAndAPressHidesIt() {
        val visible = mutableStateOf(false)
        compose.setContent {
            Box(Modifier.followFocusVisibility(visible)) { SkyIconButton(SkyIcon.Play, "Play", {}, Modifier.testTag("button")) }
        }
        compose.onNodeWithTag("button").requestFocus().performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()
        assertTrue(visible.value)
        compose.onNodeWithTag("button").performClick()
        compose.waitForIdle()
        assertFalse(visible.value)
    }
}
