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
internal fun rememberPlayerChrome(window: Window, fullscreen: Boolean, heldOpen: Boolean): Pair<Boolean, () -> Unit> {
    val clock = remember { PlayerChromeClock() }
    var visible by remember { mutableStateOf(true) }
    val latestHeldOpen by rememberUpdatedState(heldOpen)
    fun activity() { clock.activity(System.nanoTime() / 1_000_000); visible = true }
    DisposableEffect(window) {
        val listener = AWTEventListener { event ->
            val input = event as? InputEvent ?: return@AWTEventListener
            // Enter/exit/move events can be synthesized when hiding bars resizes the
            // Canvas. Only physical pointer changes (polled below) should wake it.
            val intentional = input is KeyEvent || input.id == MouseEvent.MOUSE_PRESSED ||
                input.id == MouseEvent.MOUSE_RELEASED || input.id == MouseEvent.MOUSE_WHEEL
            if (intentional && SwingUtilities.getWindowAncestor(input.component) === window) activity()
        }
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        onDispose { toolkit.removeAWTEventListener(listener) }
    }
    LaunchedEffect(window, fullscreen) {
        activity()
        var previousPointer = MouseInfo.getPointerInfo()?.location
        var wasFocused = window.isFocused
        while (true) {
            // A native libmpv child can consume mouse motion before AWT sees it.
            // Poll position only; do not install global input hooks or capture other apps' input.
            val pointer = MouseInfo.getPointerInfo()?.location
            if (window.isFocused && ((!wasFocused) ||
                    (pointer != previousPointer && pointer != null && window.bounds.contains(pointer)))) activity()
            previousPointer = pointer
            wasFocused = window.isFocused
            visible = clock.visible(System.nanoTime() / 1_000_000, fullscreen, latestHeldOpen)
            delay(100)
        }
    }
    return visible to ::activity
}
