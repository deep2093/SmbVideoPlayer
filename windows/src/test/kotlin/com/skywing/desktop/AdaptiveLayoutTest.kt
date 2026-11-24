package com.skywing.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skywing.desktop.ui.adaptive.Adaptive
import com.skywing.desktop.ui.adaptive.AutoColumns
import com.skywing.desktop.ui.adaptive.InputMode
import com.skywing.desktop.ui.adaptive.InputModes
import com.skywing.desktop.ui.adaptive.NavigationShape
import com.skywing.desktop.ui.adaptive.WidthClass
import com.skywing.desktop.ui.adaptive.rememberSettledWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/** M3 item 3 (M3 design brief 5.1): one answer for every screen, from the window's width and the last input used. */
class AdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theWidthClassesMeetAt720And1200Dp() {
        assertEquals(WidthClass.Compact, WidthClass.of(719.5.dp))
        assertEquals(WidthClass.Medium, WidthClass.of(720.dp))
        assertEquals(WidthClass.Medium, WidthClass.of(1199.dp))
        assertEquals(WidthClass.Expanded, WidthClass.of(1200.dp))
    }

    @Test fun eachWidthClassHasItsNavigationAndPadding() {
        val compact = Adaptive(680.dp, InputMode.Mouse)
        assertEquals(NavigationShape.TopTabs, compact.navigation)
        assertEquals(0.dp, compact.railWidth)
        assertEquals(16.dp, compact.pagePadding)
        assertEquals(12.dp, compact.gridGap)

        val medium = Adaptive(1000.dp, InputMode.Mouse)
        assertEquals(NavigationShape.NarrowRail, medium.navigation)
        assertEquals(72.dp, medium.railWidth)
        assertEquals(24.dp, medium.pagePadding)
        assertEquals(16.dp, medium.gridGap)

        val expanded = Adaptive(1300.dp, InputMode.Mouse)
        assertEquals(NavigationShape.WideRail, expanded.navigation)
        assertEquals(240.dp, expanded.railWidth)
        assertEquals(32.dp, expanded.pagePadding)
    }

    @Test fun settingsShowsTwoPanesFrom900Dp() {
        assertEquals(false, Adaptive(899.dp, InputMode.Mouse).settingsTwoPanes)
        assertEquals(true, Adaptive(900.dp, InputMode.Mouse).settingsTwoPanes)
        assertEquals(240.dp, Adaptive(1000.dp, InputMode.Mouse).settingsListWidth)
        assertEquals(288.dp, Adaptive(1300.dp, InputMode.Mouse).settingsListWidth)
    }

    @Test fun touchGetsBiggerTargetsRowsAndTextAndNoHoverActions() {
        val mouse = Adaptive(1300.dp, InputMode.Mouse)
        assertEquals(40.dp, mouse.minTarget)
        assertEquals(40.dp, mouse.rowHeight)
        assertEquals(14.sp, mouse.bodySize)
        assertEquals(true, mouse.hoverActions)

        val touch = Adaptive(1300.dp, InputMode.Touch)
        assertEquals(48.dp, touch.minTarget)
        assertEquals(56.dp, touch.rowHeight)
        assertEquals(16.sp, touch.bodySize)
        assertEquals(false, touch.hoverActions)
    }

    @Test fun gridColumnsFollowTheWidthClassAndKeepCardsAtMost320Dp() {
        // Compact: always two columns.
        assertEquals(2, Adaptive(680.dp, InputMode.Mouse).gridColumns(648.dp))
        // Medium: cards at least 180 dp; Expanded: at least 220 dp.
        assertEquals(4, Adaptive(1000.dp, InputMode.Mouse).gridColumns(880.dp))
        assertEquals(3, Adaptive(1300.dp, InputMode.Mouse).gridColumns(896.dp))
        assertEquals(9, Adaptive(2560.dp, InputMode.Mouse).gridColumns(2256.dp))
        // One card would be 340 dp wide: a second column instead.
        assertEquals(2, Adaptive(1000.dp, InputMode.Mouse).gridColumns(340.dp))
    }

    @Test fun autoColumnsShareTheWidthEquallyBetweenTheColumnsThatFit() {
        val sizes = with(AutoColumns(Adaptive(1000.dp, InputMode.Mouse))) { Density(1f).calculateCrossAxisCellSizes(880, 16) }
        assertEquals(listOf(208, 208, 208, 208), sizes)
        val uneven = with(AutoColumns(Adaptive(1000.dp, InputMode.Mouse))) { Density(1f).calculateCrossAxisCellSizes(882, 16) }
        assertEquals(834, uneven.sum())
    }

    @Test fun aPressDecidesTheInputModeFromThePointerOrTheHiddenCursor() {
        val press = PointerEventType.Press
        // Compose names a touch, when Windows passes it on as one.
        assertEquals(InputMode.Touch, InputModes.next(InputMode.Mouse, press, touchPointer = true, buttonsDown = true) { false })
        // Windows turns a touch into mouse input for AWT, but hides the cursor while it does.
        assertEquals(InputMode.Touch, InputModes.next(InputMode.Mouse, press, touchPointer = false, buttonsDown = true) { true })
        assertEquals(InputMode.Mouse, InputModes.next(InputMode.Touch, press, touchPointer = false, buttonsDown = true) { false })
    }

    @Test fun aMouseMoveBetweenGesturesReturnsToMouseModeButNeverMidGesture() {
        val move = PointerEventType.Move
        assertEquals(InputMode.Mouse, InputModes.next(InputMode.Touch, move, touchPointer = false, buttonsDown = false) { false })
        // A drag that began as a touch stays a touch to its end.
        assertEquals(InputMode.Touch, InputModes.next(InputMode.Touch, move, touchPointer = false, buttonsDown = true) { false })
        // The moves Windows makes for a touch come with the cursor hidden.
        assertEquals(InputMode.Touch, InputModes.next(InputMode.Touch, move, touchPointer = false, buttonsDown = false) { true })
        // Scrolling never changes the mode, and Mouse mode does not ask Windows on every move.
        assertEquals(InputMode.Touch, InputModes.next(InputMode.Touch, PointerEventType.Scroll, touchPointer = false, buttonsDown = false) { false })
        assertEquals(InputMode.Mouse, InputModes.next(InputMode.Mouse, move, touchPointer = false, buttonsDown = false) { error("not asked") })
    }

    @Test fun theWidthIsReadAgain100MsAfterTheLastResize() {
        compose.mainClock.autoAdvance = false
        var window by mutableStateOf(1300.dp)
        var settled: Dp = 0.dp
        compose.setContent { settled = rememberSettledWidth(window) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1300.dp, settled, "the first width is used at once")

        window = 1000.dp
        compose.mainClock.advanceTimeBy(60)
        assertEquals(1300.dp, settled)
        window = 700.dp
        compose.mainClock.advanceTimeBy(60)
        assertEquals(1300.dp, settled, "a resize that goes on keeps the layout still")
        compose.mainClock.advanceTimeBy(80)
        assertEquals(700.dp, settled)
    }
}
