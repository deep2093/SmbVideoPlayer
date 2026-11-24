package com.skywing.desktop

import androidx.compose.runtime.*
import java.awt.AWTEvent
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlinx.coroutines.delay
import javax.swing.SwingUtilities

/**
 * When the full-screen bars show: for 2 s after the last input (M3 item 10), and while something holds them open,
 * with a fresh 2 s once it lets go. A click or tap on the video hides shown bars at once, until the next input, and
 * shows hidden ones.
 */
internal class PlayerChromeClock {
    private var lastInput = 0L
    private var shownAt = 0L
    private var dismissed = false
    private var quietUntil = Long.MIN_VALUE

    private fun showing(now: Long) = !dismissed && now - lastInput < HIDE_AFTER_MS

    fun activity(now: Long) {
        if (!showing(now)) shownAt = now
        lastInput = now
        dismissed = false
    }

    /** The pointer moved: input, unless it is the move of the tap that has just hidden the bars. */
    fun pointerMoved(now: Long) {
        if (now >= quietUntil) activity(now)
    }

    /** A click or tap on the video. The tap that has just shown the bars (its move came first) leaves them shown. */
    fun videoClick(now: Long) {
        if (showing(now) && now - shownAt >= TAP_GRACE_MS) {
            dismissed = true
            quietUntil = now + TAP_GRACE_MS
        } else {
            activity(now)
        }
    }

    fun visible(now: Long, fullscreen: Boolean, heldOpen: Boolean): Boolean {
        if (!fullscreen) {
            activity(now)
            return true
        }
        if (dismissed) return false
        if (heldOpen) lastInput = now
        return now - lastInput < HIDE_AFTER_MS
    }

    private companion object {
        const val HIDE_AFTER_MS = 2_000L
        const val TAP_GRACE_MS = 300L
    }
}

@Composable
internal fun rememberPlayerChrome(window: Window, fullscreen: Boolean, heldOpen: Boolean, onCursorVisible: (Boolean) -> Unit = {}): Triple<Boolean, () -> Unit, () -> Unit> {
    val clock = remember { PlayerChromeClock() }
    val cursorController = remember(window) { PlayerCursor(window) }
    var visible by remember { mutableStateOf(true) }
    val latestHeldOpen by rememberUpdatedState(heldOpen)
    val latestFullscreen by rememberUpdatedState(fullscreen)
    val cursorVisible by rememberUpdatedState(onCursorVisible)
    fun activity() { clock.activity(System.nanoTime() / 1_000_000); visible = true }
    fun videoClick() {
        if (!latestFullscreen) return
        val now = System.nanoTime() / 1_000_000
        clock.videoClick(now)
        visible = clock.visible(now, true, latestHeldOpen)
    }
    DisposableEffect(window) {
        val listener = AWTEventListener { event ->
            val input = event as? InputEvent ?: return@AWTEventListener
            // Enter/exit/move events can be synthesized when hiding bars resizes the
            // Canvas. Only physical pointer changes (polled below) should wake it.
            val intentional = input is KeyEvent || input.id == MouseEvent.MOUSE_PRESSED ||
                input.id == MouseEvent.MOUSE_RELEASED || input.id == MouseEvent.MOUSE_WHEEL
            if (intentional && belongsToPlayer(SwingUtilities.getWindowAncestor(input.component), window)) activity()
        }
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        onDispose { toolkit.removeAWTEventListener(listener); cursorController.close(); cursorVisible(true) }
    }
    LaunchedEffect(window, fullscreen) {
        activity()
        var previousPointer = MouseInfo.getPointerInfo()?.location
        var wasFocused = window.isFocused
        var lastCursorVisible: Boolean? = null
        while (true) {
            // A native libmpv child can consume mouse motion before AWT sees it.
            // Poll position only; do not install global input hooks or capture other apps' input.
            val pointer = MouseInfo.getPointerInfo()?.location
            if (window.isFocused && !wasFocused) activity()
            else if (window.isFocused && pointer != previousPointer && pointer != null && window.bounds.contains(pointer)) {
                clock.pointerMoved(System.nanoTime() / 1_000_000)
            }
            previousPointer = pointer
            wasFocused = window.isFocused
            visible = clock.visible(System.nanoTime() / 1_000_000, fullscreen, latestHeldOpen)
            val showCursor = !fullscreen || visible || !window.isFocused
            if (showCursor != lastCursorVisible) { cursorController.setHidden(!showCursor); cursorVisible(showCursor); lastCursorVisible = showCursor }
            delay(100)
        }
    }
    return Triple(visible, ::activity, ::videoClick)
}

/** AWT owns the parent cursor even when libmpv owns the video child HWND. */
internal class PlayerCursor(private val window: Window) : AutoCloseable {
    private val saved = java.util.IdentityHashMap<java.awt.Component, java.awt.Cursor?>()
    private val blank by lazy {
        Toolkit.getDefaultToolkit().createCustomCursor(
            java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            java.awt.Point(0, 0), "SkyWing hidden cursor")
    }
    fun setHidden(hidden: Boolean) {
        if (!hidden) { close(); return }
        fun visit(component: java.awt.Component) {
            if (component === window || component is java.awt.Canvas) {
                if (!saved.containsKey(component)) saved[component] = if (component.isCursorSet) component.cursor else null
                component.cursor = blank
            }
            if (component is java.awt.Container) component.components.forEach(::visit)
        }
        visit(window)
    }
    override fun close() {
        saved.forEach { (component, cursor) -> component.cursor = cursor }
        saved.clear()
    }
}
