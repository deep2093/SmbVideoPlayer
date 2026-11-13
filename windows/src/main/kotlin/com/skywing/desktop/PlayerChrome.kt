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

/** A monotonic inactivity deadline; protected interactions get a fresh grace period. */
internal class PlayerChromeClock {
    private var lastInput = 0L
    fun activity(now: Long) { lastInput = now }
    fun visible(now: Long, fullscreen: Boolean, heldOpen: Boolean): Boolean {
        if (!fullscreen || heldOpen) activity(now)
        return !fullscreen || heldOpen || now - lastInput < 2500L
    }
}

@Composable
internal fun rememberPlayerChrome(window: Window, fullscreen: Boolean, heldOpen: Boolean, onCursorVisible: (Boolean) -> Unit = {}): Pair<Boolean, () -> Unit> {
    val clock = remember { PlayerChromeClock() }
    val cursorController = remember(window) { PlayerCursor(window) }
    var visible by remember { mutableStateOf(true) }
    val latestHeldOpen by rememberUpdatedState(heldOpen)
    val cursorVisible by rememberUpdatedState(onCursorVisible)
    fun activity() { clock.activity(System.nanoTime() / 1_000_000); visible = true }
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
            if (window.isFocused && ((!wasFocused) ||
                    (pointer != previousPointer && pointer != null && window.bounds.contains(pointer)))) activity()
            previousPointer = pointer
            wasFocused = window.isFocused
            visible = clock.visible(System.nanoTime() / 1_000_000, fullscreen, latestHeldOpen)
            val showCursor = !fullscreen || visible || !window.isFocused
            if (showCursor != lastCursorVisible) { cursorController.setHidden(!showCursor); cursorVisible(showCursor); lastCursorVisible = showCursor }
            delay(100)
        }
    }
    return visible to ::activity
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
