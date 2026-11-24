package com.skywing.desktop.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.delay

/** The width classes of the window's content (M3 design brief 5.1). */
internal enum class WidthClass {
    /** Under 720 dp: top tabs. */
    Compact,

    /** 720 to 1199 dp: the 72 dp rail. */
    Medium,

    /** 1200 dp and up: the 240 dp rail. */
    Expanded;

    companion object {
        fun of(width: Dp): WidthClass = when {
            width < 720.dp -> Compact
            width < 1200.dp -> Medium
            else -> Expanded
        }
    }
}

/** The last input used. Mouse covers the keyboard too; the controller arrives in M5. */
internal enum class InputMode { Mouse, Touch }

internal enum class NavigationShape { TopTabs, NarrowRail, WideRail }

/** One answer for every screen (plan 6.6): what the window's width and the last input used call for. */
@Immutable
internal data class Adaptive(val width: Dp, val input: InputMode) {
    val widthClass: WidthClass get() = WidthClass.of(width)

    val navigation: NavigationShape
        get() = when (widthClass) {
            WidthClass.Compact -> NavigationShape.TopTabs
            WidthClass.Medium -> NavigationShape.NarrowRail
            WidthClass.Expanded -> NavigationShape.WideRail
        }

    val railWidth: Dp
        get() = when (navigation) {
            NavigationShape.TopTabs -> 0.dp
            NavigationShape.NarrowRail -> 72.dp
            NavigationShape.WideRail -> 240.dp
        }

    val pagePadding: Dp
        get() = when (widthClass) {
            WidthClass.Compact -> 16.dp
            WidthClass.Medium -> 24.dp
            WidthClass.Expanded -> 32.dp
        }

    val gridGap: Dp get() = if (widthClass == WidthClass.Compact) 12.dp else 16.dp

    /** Settings shows its category list beside the page from 900 dp (decision O1). */
    val settingsTwoPanes: Boolean get() = width >= 900.dp
    val settingsListWidth: Dp get() = if (widthClass == WidthClass.Expanded) 288.dp else 240.dp

    val touch: Boolean get() = input == InputMode.Touch
    val minTarget: Dp get() = if (touch) 48.dp else 40.dp
    val rowHeight: Dp get() = if (touch) 56.dp else 40.dp
    val bodySize: TextUnit get() = if (touch) 16.sp else 14.sp

    /** Actions that appear on hover; with touch they are always shown (⋮) instead. */
    val hoverActions: Boolean get() = !touch

    /**
     * How many card columns fit in [contentWidth] (M3 design brief 5.4): two in Compact; else as many as fit at 180 dp
     * (Medium) or 220 dp (Expanded) each, and one more whenever a card would be wider than 320 dp.
     */
    fun gridColumns(contentWidth: Dp): Int {
        if (widthClass == WidthClass.Compact) return 2
        val least = if (widthClass == WidthClass.Medium) 180.dp else 220.dp
        var columns = ((contentWidth + gridGap) / (least + gridGap)).toInt().coerceAtLeast(1)
        while ((contentWidth - gridGap * (columns - 1)) / columns > MAX_CARD) columns++
        return columns
    }

    private companion object {
        val MAX_CARD = 320.dp
    }
}

/** Grid columns by [Adaptive.gridColumns]: as many as the grid's width calls for, sharing it equally. */
internal data class AutoColumns(private val adaptive: Adaptive) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = adaptive.gridColumns(availableSize.toDp())
        val cells = availableSize - spacing * (count - 1)
        val each = cells / count
        val extra = cells % count
        return List(count) { each + if (it < extra) 1 else 0 }
    }
}

internal val LocalAdaptive = staticCompositionLocalOf { Adaptive(1280.dp, InputMode.Mouse) }

/** How long the window's width must stay the same before the layout follows it. */
internal const val WIDTH_SETTLE_MS = 100L

/** [width], read again 100 ms after the last change, so a resize in progress doesn't relayout on every step. */
@Composable
internal fun rememberSettledWidth(width: Dp): Dp {
    var settled by remember { mutableStateOf(width) }
    LaunchedEffect(width) {
        if (width != settled) {
            delay(WIDTH_SETTLE_MS)
            settled = width
        }
    }
    return settled
}

internal object InputModes {
    /**
     * The input mode after one pointer event. A press decides it: a touch when Compose names one or when Windows has
     * hidden the cursor (it does while it turns a touch into mouse input for AWT). A mouse move with no button down
     * and the cursor shown returns to Mouse. Nothing changes during a gesture or on a scroll, and in Mouse mode a move
     * doesn't ask Windows anything.
     */
    fun next(current: InputMode, type: PointerEventType, touchPointer: Boolean, buttonsDown: Boolean, cursorSuppressed: () -> Boolean): InputMode =
        when (type) {
            PointerEventType.Press -> if (touchPointer || cursorSuppressed()) InputMode.Touch else InputMode.Mouse
            PointerEventType.Move ->
                if (current == InputMode.Touch && !buttonsDown && !touchPointer && !cursorSuppressed()) InputMode.Mouse else current
            else -> current
        }
}

/** Follows the last input used into [mode], from every pointer event in this area, seen before anything handles it. */
internal fun Modifier.followInputMode(mode: MutableState<InputMode>): Modifier = pointerInput(mode) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val next = InputModes.next(
                mode.value,
                event.type,
                touchPointer = event.changes.any { it.type == PointerType.Touch },
                buttonsDown = event.changes.any { it.pressed },
                cursorSuppressed = WindowsCursor::suppressed,
            )
            if (next != mode.value) mode.value = next
        }
    }
}

/** Whether Windows has hidden the cursor for touch or pen input (GetCursorInfo's CURSOR_SUPPRESSED); only read. */
private object WindowsCursor {
    @Structure.FieldOrder("cbSize", "flags", "hCursor", "x", "y")
    class CursorInfo : Structure() {
        @JvmField var cbSize: Int = 0
        @JvmField var flags: Int = 0
        @JvmField var hCursor: Pointer? = null
        @JvmField var x: Int = 0
        @JvmField var y: Int = 0
    }

    private interface User32 : StdCallLibrary {
        fun GetCursorInfo(info: CursorInfo): Boolean
    }

    private const val CURSOR_SUPPRESSED = 0x2
    private val user32: User32? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    fun suppressed(): Boolean = runCatching {
        val api = user32 ?: return false
        val info = CursorInfo().apply { cbSize = size() }
        api.GetCursorInfo(info) && (info.flags and CURSOR_SUPPRESSED) != 0
    }.getOrDefault(false)
}
