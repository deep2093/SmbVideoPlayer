package com.skywing.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import com.skywing.desktop.playback.EmbeddedMpv
import com.skywing.testsupport.IsolatedPreferencesFactory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.ptr.IntByReference
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.FutureTask
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.IIOImage
import javax.imageio.metadata.IIOMetadataNode
import javax.swing.SwingUtilities
import kotlin.test.*

/** Real Windows HWND/Compose regression, not a mock of the fullscreen implementation. */
class FullscreenWindowTest {
    private interface User32 : StdCallLibrary {
        interface WindowCallback : StdCallLibrary.StdCallCallback {
            fun invoke(hwnd: Pointer, data: Pointer?): Boolean
        }
        fun EnumWindows(callback: WindowCallback, data: Pointer?): Boolean
        fun GetWindowThreadProcessId(hwnd: Pointer, pid: IntByReference): Int
        fun PostMessageW(hwnd: Pointer, message: Int, wparam: Pointer?, lparam: Pointer?): Boolean
        fun IsWindowVisible(hwnd: Pointer): Boolean
        fun GetCursorInfo(info: Pointer): Boolean
        fun LoadCursorW(instance: Pointer?, name: Pointer): Pointer?
        /** [point] is a POINT passed by value on x64: y in the high 32 bits, x in the low. */
        fun WindowFromPoint(point: Long): Pointer?
        fun GetClassNameW(hwnd: Pointer, name: CharArray, size: Int): Int
        fun DrawIconEx(dc: Pointer, x: Int, y: Int, icon: Pointer, width: Int, height: Int, step: Int, brush: Pointer?, flags: Int): Boolean
        fun GetWindowLongW(hwnd: Pointer, index: Int): Int
    }
    private interface Gdi32 : StdCallLibrary {
        fun CreateCompatibleDC(dc: Pointer?): Pointer?
        fun CreateDIBSection(dc: Pointer, info: Pointer, usage: Int, bits: com.sun.jna.ptr.PointerByReference, section: Pointer?, offset: Int): Pointer?
        fun SelectObject(dc: Pointer, obj: Pointer): Pointer?
        fun DeleteObject(obj: Pointer): Boolean
        fun DeleteDC(dc: Pointer): Boolean
        fun GdiFlush(): Boolean
    }
    private val gdi by lazy { Native.load("gdi32", Gdi32::class.java) }
    /** Transparent custom cursors can have CURSOR_SHOWING set; inspect the actual pixels. */
    private fun cursorHasPixels(): Boolean = com.sun.jna.Memory(24).use { info ->
        info.clear(); info.setInt(0, 24)
        check(user32.GetCursorInfo(info))
        if (info.getInt(4) and 1 == 0) return@use false
        val icon = info.getPointer(8) ?: return@use false
        val dc = checkNotNull(gdi.CreateCompatibleDC(null))
        try {
            com.sun.jna.Memory(40).use { bitmapInfo ->
                bitmapInfo.clear(); bitmapInfo.setInt(0, 40); bitmapInfo.setInt(4, 64); bitmapInfo.setInt(8, -64)
                bitmapInfo.setShort(12, 1); bitmapInfo.setShort(14, 32)
                val bits = com.sun.jna.ptr.PointerByReference()
                val bitmap = checkNotNull(gdi.CreateDIBSection(dc, bitmapInfo, 0, bits, null, 0))
                val old = checkNotNull(gdi.SelectObject(dc, bitmap))
                try {
                    repeat(4096) { bits.value.setInt(it * 4L, 0x00123456) }
                    check(user32.DrawIconEx(dc, 0, 0, icon, 64, 64, 0, null, 3))
                    gdi.GdiFlush()
                    bits.value.getIntArray(0, 4096).any { it and 0x00ffffff != 0x00123456 }
                } finally { gdi.SelectObject(dc, old); gdi.DeleteObject(bitmap) }
            }
        } finally { gdi.DeleteDC(dc) }
    }
    private val user32 by lazy { Native.load("user32", User32::class.java) }

    private fun <T> edt(block: () -> T): T {
        val task = FutureTask(block)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
    private fun await(message: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        val context = edt {
            java.awt.Window.getWindows().filter { it is Frame && it.title == "SkyWing fullscreen regression" }
                .joinToString { "focused=${it.isFocused}, bounds=${it.bounds}, cursor=${it.cursor}, overlays=" +
                    it.ownedWindows.joinToString { child -> "${child.name}:${child.isVisible}:${child.bounds}" } }
        }
        fail("$message; pointer=${java.awt.MouseInfo.getPointerInfo()?.location}; ${cursorState()}; $context")
    }

    /**
     * For failure messages: the cursor Windows shows and the class of the window under the pointer. The cursor check
     * failed twice on 23 Nov 2026 with SkyWing's hidden cursor set and the controls hidden; this says what showed.
     */
    private fun cursorState(): String = runCatching {
        com.sun.jna.Memory(24).use { info ->
            info.clear(); info.setInt(0, 24)
            check(user32.GetCursorInfo(info))
            val handle = info.getPointer(8)
            val name = systemCursors.entries.firstOrNull { user32.LoadCursorW(null, Pointer(it.key.toLong())) == handle }?.value
            val showing = if (info.getInt(4) and 1 == 0) " (not showing)" else ""
            val point = (info.getInt(20).toLong() shl 32) or (info.getInt(16).toLong() and 0xFFFF_FFFFL)
            val under = user32.WindowFromPoint(point)?.let { hwnd -> CharArray(256).let { String(it, 0, user32.GetClassNameW(hwnd, it, it.size)) } }
            "Windows cursor=${name ?: "not a system cursor"}$showing; window under the pointer=${under ?: "none"}"
        }
    }.getOrElse { "cursor state unavailable: ${it.message}" }

    private val systemCursors = mapOf(
        32512 to "arrow", 32513 to "I-beam", 32514 to "wait", 32515 to "cross", 32646 to "size all", 32648 to "no",
        32649 to "hand", 32650 to "app starting", 32651 to "help",
    )
    private fun canvases(component: Component): List<Canvas> =
        (if (component.javaClass == Canvas::class.java) listOf(component as Canvas) else emptyList()) +
            (if (component is Container) component.components.flatMap(::canvases) else emptyList())

    /** Sixty alternating one-second frames in a GIF. */
    private fun writeTestGif(media: File) {
        // Exercise decoded video, without depending on an audio endpoint
        // on a hosted Windows runner. Sixty alternating one-second frames.
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            ImageIO.createImageOutputStream(media).use { output ->
                writer.output = output
                writer.prepareWriteSequence(null)
                repeat(60) { frame ->
                    val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
                    for (x in 0 until 32) for (y in 0 until 32) image.setRGB(x, y,
                        if (frame % 2 == 0) 0x2255AA else 0xAA5522)
                    val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
                    val tree = metadata.getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                    val control = tree.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                    control.setAttribute("delayTime", "100")
                    metadata.setFromTree("javax_imageio_gif_image_1.0", tree)
                    writer.writeToSequence(IIOImage(image, null, metadata), null)
                }
                writer.endWriteSequence()
            }
        } finally {
            writer.dispose()
        }
    }

    private fun dispatchKey(target: Component, code: Int, release: Boolean = true) {
        edt {
            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            manager.dispatchEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED))
            if (release) manager.dispatchEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED))
        }
    }

    /** A point on the video in screen column [x] that no player control window covers, even a hidden one. */
    private fun videoPointClearOfControls(window: java.awt.Window, canvas: Canvas, x: Int): Point = edt {
        val video = Rectangle(canvas.locationOnScreen, canvas.size)
        // Include hidden overlays: moving the pointer can reveal them again.
        val overlays = window.ownedWindows.filter {
            it.isDisplayable && it.name?.startsWith("skywing-") == true
        }.map { Rectangle(it.bounds) }
        val cuts = (listOf(video.y, video.y + video.height) + overlays.flatMap {
            listOf(it.y, it.y + it.height)
        }).map { it.coerceIn(video.y, video.y + video.height) }.distinct().sorted()
        val band = checkNotNull(cuts.zipWithNext().filter { (top, bottom) ->
            bottom > top && overlays.none { it.contains(x, top + (bottom - top) / 2) }
        }.maxByOrNull { (top, bottom) -> bottom - top }) { "No video area outside the player controls" }
        Point(x, band.first + (band.second - band.first) / 2)
    }

    /** Moves the pointer to [point] from 12 pixels to its left, so that it arrives by real movement. */
    private fun movePointerTo(robot: Robot, point: Point) {
        val approach = Point(point.x - 12, point.y)
        robot.mouseMove(approach.x, approach.y)
        await("Pointer did not reach the approach point $approach") {
            java.awt.MouseInfo.getPointerInfo()?.location == approach
        }
        robot.waitForIdle()
        robot.mouseMove(point.x, point.y)
        robot.waitForIdle()
    }

    private fun prepareInactivity(window: java.awt.Window, canvas: Canvas, robot: Robot): java.awt.Point {
        // Native setup and other test windows can change focus after the initial
        // request. Inactivity requires a focused player and no control hover.
        edt { window.toFront(); window.requestFocus(); canvas.requestFocusInWindow() }
        await("Inactivity target did not gain focus") { edt { window.isFocused } }
        robot.waitForIdle()
        val point = videoPointClearOfControls(window, canvas, x = edt { canvas.locationOnScreen.x + canvas.width / 2 })
        // Establish real movement before testing a stationary cursor. A move to
        // the current position may leave native mouse tracking uninitialized.
        movePointerTo(robot, point)
        await("Inactivity pointer did not reach the video") {
            java.awt.MouseInfo.getPointerInfo()?.location == point && edt { window.isFocused }
        }
        return point
    }

    private fun awaitInactivity(window: java.awt.Window, point: java.awt.Point,
                                message: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        await(message, timeoutMillis) {
            assertTrue(edt { window.isFocused }, "Desktop focus changed during inactivity assertion: $message")
            assertEquals(point, java.awt.MouseInfo.getPointerInfo()?.location,
                "Desktop pointer moved during inactivity assertion: $message")
            condition()
        }
    }

    private fun inactivityFixture(): Pair<Frame, Canvas> = edt {
        val canvas = Canvas()
        val frame = Frame("SkyWing inactivity setup regression").apply {
            setBounds(80, 80, 800, 600)
            add(canvas)
            isVisible = true
            toFront()
        }
        frame to canvas
    }

    @Test fun inactivitySetupRecoversFocusFromAnotherWindow() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val (window, canvas) = inactivityFixture()
        val other = edt { Frame("SkyWing test focus competitor").apply {
            setBounds(1000, 80, 160, 120); isVisible = true; toFront(); requestFocus()
        } }
        try {
            await("Competitor did not gain focus") { edt { other.isFocused } }
            prepareInactivity(window, canvas, Robot())
            assertTrue(edt { window.isFocused }, "Inactivity checks require the target to own focus")
        } finally { edt { other.dispose(); window.dispose() } }
    }

    @Test fun inactivitySetupPlacesPointerOutsideOwnedControls() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val (window, canvas) = inactivityFixture()
        val overlay = edt { java.awt.Window(window).apply {
            name = "skywing-controls-overlay"
            focusableWindowState = false
            setBounds(window.x, window.y + window.height / 3, window.width, window.height * 2 / 3)
            isVisible = true
        } }
        try {
            val point = prepareInactivity(window, canvas, Robot())
            assertFalse(edt { overlay.bounds.contains(point) }, "The window center can be inside its controls")
            assertTrue(edt { Rectangle(canvas.locationOnScreen, canvas.size).contains(point) })
            assertEquals(point, java.awt.MouseInfo.getPointerInfo()?.location)
        } finally { edt { overlay.dispose(); window.dispose() } }
    }

    @Test fun composeEscapeRestoresSameVisibleFrameAndCanvas() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val player = EmbeddedMpv()
        val window = edt {
            ComposeWindow().apply {
                title = "SkyWing fullscreen regression"
                setBounds(80, 80, 1000, 720)
                setContent { SkyWingWindowContent(window, player, false, initialSection = "Now Playing") }
                isVisible = true
                toFront()
            }
        }
        try {
            await("Compose did not mount video Canvas") { edt { canvases(window).size == 1 } }
            val canvas = edt { canvases(window).single() }
            val frameHwnd = edt { Native.getComponentPointer(window) }
            val canvasHwnd = edt { Native.getComponentPointer(canvas) }
            val originalBounds = edt { Rectangle(window.bounds) }
            val decorations = user32.GetWindowLongW(frameHwnd, -16) and 0x00CF0000
            fun key(code: Int, release: Boolean = true) = dispatchKey(canvas, code, release)
            edt { canvas.requestFocusInWindow() }
            await("Test window did not gain focus") { edt { window.isFocused } }

            // Optional real libmpv playback is required by CI; no external media downloads.
            val nativePlayback = System.getenv("SKYWING_TEST_MPV") == "true"
            // libmpv's C API expects UTF-8 even on Windows. This name covers non-Latin letters,
            // accents, punctuation, and supplementary Unicode characters from real libraries.
            val mediaDir = java.nio.file.Files.createTempDirectory("skywing-fullscreen-").toFile()
            val media = File(mediaDir, "ғᴛ. @Rosa, à , ❤️❤️-.gif")
            try {
                if (nativePlayback) {
                    writeTestGif(media)
                    assertTrue(edt { player.attach(canvas) }, player.lastError)
                    assertTrue(player.open(media))
                    await("mpv did not start playback") { (player.playbackPosition() ?: 0.0) > 0.1 }
                    // A software-decoded GIF must bypass smoothing on every display. Exercise
                    // real libmpv property reads (including UTF-8 allocations), not a mock GPU.
                    com.skywing.desktop.playback.SmoothingMode.entries.forEach { mode ->
                        val status = player.updateSmoothing(mode)
                        assertFalse(status.capabilities.hardwareDecode)
                        assertFalse(status.capabilities.decision(mode).enabled)
                    }
                    val rendererReport = player.updateSmoothing(com.skywing.desktop.playback.SmoothingMode.OFF).report
                    // mpv was given an NVIDIA GPU's exact name (the GPU plan), so it draws on NVIDIA.
                    if (rendererReport.contains("preference: NVIDIA")) {
                        assertTrue(rendererReport.contains("Renderer: NVIDIA"), rendererReport)
                    }
                    // The live report names its build (commit, runtime, ed072bf) right under the version line.
                    assertEquals(com.skywing.desktop.playback.BuildIdentity.current.reportLine(), rendererReport.lines()[1], rendererReport)
                    val thumbnail = File(mediaDir, "thumbnail.jpg")
                    val extracted = com.skywing.desktop.playback.ThumbnailExtractor.extractDetailed(media, thumbnail, start = "0")
                    assertTrue(extracted.success && thumbnail.isFile, "Unicode thumbnail failed: ${extracted.reason}")
                    val duration = com.skywing.desktop.playback.ThumbnailExtractor.durationSeconds(media)
                    assertTrue(duration != null && duration in 59.0..61.0, "Frame picker could not read video duration: $duration")
                    val oneSecond = File(mediaDir, "one-second.jpg")
                    assertTrue(com.skywing.desktop.playback.ThumbnailExtractor.extract(media, oneSecond, start = "1", exact = true),
                        "Frame picker could not seek to a precise second")
                }
                if (nativePlayback) {
                    key(KeyEvent.VK_F)
                    await("F did not enter fullscreen") { edt { window.bounds == window.graphicsConfiguration.bounds } }
                    val robot = Robot()
                    fun cursorShowing(): Boolean = cursorHasPixels()
                    fun overlaysVisible() = edt { window.ownedWindows.count { it.name?.startsWith("skywing-") == true && it.isVisible } == 2 }
                    fun overlaysHidden() = edt { window.ownedWindows.none { it.name?.startsWith("skywing-") == true && it.isVisible } }
                    await("Fullscreen controls were not shown") { overlaysVisible() }
                    // Fractional Windows DPI can round the Swing surface up by one logical pixel.
                    await("Video must fill fullscreen") { edt { kotlin.math.abs(canvas.height - window.height) <= 1 && kotlin.math.abs(canvas.width - window.width) <= 1 } }
                    val videoBounds = edt { Rectangle(canvas.bounds) }
                    val captures = File("build/reports/tests/test/screenshots").apply { mkdirs() }
                    robot.waitForIdle()
                    ImageIO.write(robot.createScreenCapture(edt { window.bounds }), "png", File(captures, "fullscreen-controls.png"))
                    val center = prepareInactivity(window, canvas, robot)
                    awaitInactivity(window, center, "Fullscreen controls did not auto-hide") { overlaysHidden() }
                    awaitInactivity(window, center, "Fullscreen cursor did not hide with controls") { !cursorShowing() }
                    val otherWindow = edt { Frame("Cursor focus regression").apply {
                        setBounds(center.x - 100, center.y - 100, 200, 200); isVisible = true; toFront(); requestFocus()
                    } }
                    try {
                        await("Other window did not gain focus") { edt { otherWindow.isFocused } }
                        await("Cursor remained hidden after focus loss") { cursorShowing() }
                    } finally { edt { otherWindow.dispose(); window.toFront(); canvas.requestFocusInWindow() } }
                    await("Player did not regain focus") { edt { window.isFocused } }
                    val keyboardIdlePoint = prepareInactivity(window, canvas, robot)
                    assertEquals(videoBounds, edt { Rectangle(canvas.bounds) }, "Auto-hide resized the video")
                    key(KeyEvent.VK_M)
                    await("Keyboard input did not reveal controls") { overlaysVisible() }
                    await("Keyboard input did not restore cursor") { cursorShowing() }
                    awaitInactivity(window, keyboardIdlePoint, "Controls did not hide again after keyboard input") { overlaysHidden() }
                    robot.mouseMove(keyboardIdlePoint.x + 20, keyboardIdlePoint.y)
                    await("Native video mouse motion did not reveal controls") { overlaysVisible() }
                    await("Mouse motion did not restore cursor") { cursorShowing() }
                    val overlayBounds = edt { Rectangle(window.ownedWindows.single { it.name == "skywing-controls-overlay" }.bounds) }
                    robot.mouseMove(overlayBounds.x + overlayBounds.width / 2 - 20, overlayBounds.y + overlayBounds.height - 96)
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                    await("Overlay pause click failed") { player.isPaused() == true }
                    Thread.sleep(3000)
                    assertTrue(overlaysVisible(), "Paused controls must stay visible")
                    assertEquals(videoBounds, edt { Rectangle(canvas.bounds) }, "Showing controls resized the video")
                    assertEquals(canvasHwnd, edt { Native.getComponentPointer(canvas) })
                    key(KeyEvent.VK_SPACE)
                    key(KeyEvent.VK_ESCAPE)
                    await("Escape did not restore after auto-hide") { edt { window.bounds == originalBounds } }
                    robot.mouseMove(originalBounds.x + originalBounds.width / 2, originalBounds.y + originalBounds.height / 2)
                    await("Cursor remained hidden after fullscreen exit") { cursorShowing() }
                    await("Fullscreen overlays survived exit") { edt { window.ownedWindows.none { it.name?.startsWith("skywing-") == true && it.isDisplayable } } }
                }
                repeat(10) {
                    key(KeyEvent.VK_F, release = false)
                    await("F did not enter fullscreen") { edt { window.bounds == window.graphicsConfiguration.bounds } }
                    key(KeyEvent.VK_F, release = false) // held F must not toggle back
                    assertEquals(edt { window.graphicsConfiguration.bounds }, edt { window.bounds })
                    key(KeyEvent.VK_ESCAPE)
                    await("Escape did not restore bounds") { edt { window.bounds == originalBounds } }
                    edt {
                        assertTrue(window.isShowing)
                        assertTrue(canvas.isShowing)
                        assertEquals(frameHwnd, Native.getComponentPointer(window))
                        assertEquals(canvasHwnd, Native.getComponentPointer(canvas))
                        assertSame(canvas, canvases(window).single())
                    }
                    assertTrue(user32.IsWindowVisible(frameHwnd), "Native frame became hidden")
                    assertEquals(decorations, user32.GetWindowLongW(frameHwnd, -16) and 0x00CF0000)
                    // Release F after Esc to model the original held-key sequence.
                    edt { KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(
                        KeyEvent(canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.VK_F, KeyEvent.CHAR_UNDEFINED)) }
                    if (nativePlayback) {
                        assertTrue(edt { player.attach(canvas) }, player.lastError)
                        assertNotNull(player.playbackPosition())
                    }
                }
                if (nativePlayback) {
                    assertTrue(player.togglePause())
                    assertTrue(player.seekAbsolute(10.0))
                    assertTrue(player.toggleMute())
                    assertTrue(player.togglePause())
                    await("Playback stopped advancing after fullscreen exits") { (player.playbackPosition() ?: 0.0) > 10.1 }
                }
            } finally { mediaDir.deleteRecursively() }
        } finally {
            // Destroy mpv before disposing its parent; close also rejects delayed reattach.
            player.close()
            player.close()
            try { assertFalse(edt { player.attach(Canvas()) }) }
            finally { edt { window.dispose() } }
        }
    }

    /**
     * B32: someone who leaves the mouse resting where the fullscreen video appears must stop seeing the cursor. The
     * pointer moves only before fullscreen, to a spot left of the window that the fullscreen video covers clear of
     * the controls, and F enters fullscreen. Once the controls hide (2.5 s after the last input), Windows must stop
     * drawing the cursor within 3 s. composeEscapeRestoresSameVisibleFrameAndCanvas moves the pointer over the
     * fullscreen video first, which can hide this case.
     */
    @Test fun stillMouseOverFullscreenVideoHidesTheCursor() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(!GraphicsEnvironment.isHeadless())
        assumeTrue("Needs libmpv playback (SKYWING_TEST_MPV=true)", System.getenv("SKYWING_TEST_MPV") == "true")
        val player = EmbeddedMpv()
        val mediaDir = java.nio.file.Files.createTempDirectory("skywing-still-mouse-").toFile()
        val window = edt {
            ComposeWindow().apply {
                title = "SkyWing fullscreen regression"
                setBounds(80, 80, 1000, 720)
                setContent { SkyWingWindowContent(window, player, false, initialSection = "Now Playing") }
                isVisible = true
                toFront()
            }
        }
        try {
            await("Compose did not mount video Canvas") { edt { canvases(window).size == 1 } }
            val canvas = edt { canvases(window).single() }
            val originalBounds = edt { Rectangle(window.bounds) }
            fun fullscreen() = edt { window.bounds == window.graphicsConfiguration.bounds }
            fun overlaysVisible() = edt { window.ownedWindows.count { it.name?.startsWith("skywing-") == true && it.isVisible } == 2 }
            fun overlaysHidden() = edt { window.ownedWindows.none { it.name?.startsWith("skywing-") == true && it.isVisible } }
            fun focusPlayer() {
                edt { window.toFront(); window.requestFocus(); canvas.requestFocusInWindow() }
                await("Test window did not gain focus") { edt { window.isFocused } }
            }
            val media = File(mediaDir, "still-mouse.gif").also(::writeTestGif)
            assertTrue(edt { player.attach(canvas) }, player.lastError)
            assertTrue(player.open(media))
            await("mpv did not start playback") { (player.playbackPosition() ?: 0.0) > 0.1 }
            focusPlayer()

            // A first fullscreen visit finds the resting spot: left of the window, on the video, clear of the controls.
            dispatchKey(canvas, KeyEvent.VK_F)
            await("F did not enter fullscreen") { fullscreen() }
            await("Fullscreen controls were not shown") { overlaysVisible() }
            val screen = edt { Rectangle(window.graphicsConfiguration.bounds) }
            check(originalBounds.x - screen.x >= 40) { "The test window needs room on its left: $originalBounds on $screen" }
            val rest = videoPointClearOfControls(window, canvas, x = screen.x + (originalBounds.x - screen.x) / 2)
            dispatchKey(canvas, KeyEvent.VK_ESCAPE)
            await("Escape did not restore the window") { edt { window.bounds == originalBounds } }

            // The mouse moves now, outside the window, and never again.
            val robot = Robot()
            movePointerTo(robot, rest)
            await("Pointer did not reach its resting spot $rest") { java.awt.MouseInfo.getPointerInfo()?.location == rest }
            assertFalse(edt { window.bounds.contains(rest) }, "The resting spot must be outside the window before fullscreen")
            focusPlayer()
            dispatchKey(canvas, KeyEvent.VK_F)
            await("F did not enter fullscreen again") { fullscreen() }
            await("Fullscreen controls were not shown again") { overlaysVisible() }
            assertTrue(edt { Rectangle(canvas.locationOnScreen, canvas.size).contains(rest) }, "The fullscreen video must be under the pointer")
            assertFalse(edt { window.ownedWindows.any { it.name?.startsWith("skywing-") == true && it.bounds.contains(rest) } },
                "The pointer must not rest on the player controls")
            // Otherwise the B32 check below could pass on a cursor that never showed.
            assertTrue(cursorHasPixels(), "The cursor must show while the fullscreen controls do")
            awaitInactivity(window, rest, "Fullscreen controls did not auto-hide") { overlaysHidden() }
            awaitInactivity(window, rest, "B32: Windows still drew the cursor 3 s after the controls hid, with the pointer " +
                "resting on the video since before fullscreen", timeoutMillis = 3_000) { !cursorHasPixels() }
        } finally {
            // Destroy mpv before disposing its parent.
            player.close()
            edt { window.dispose() }
            mediaDir.deleteRecursively()
        }
    }

    @Test fun motionPanelRendersOnAndOffWithoutLegacyChoices() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val folder = File("build/reports/tests/test/screenshots").apply { mkdirs() }
        for (mode in com.skywing.desktop.playback.SmoothingMode.entries) {
            val window = edt { ComposeWindow().apply {
                title = "Motion controls regression"
                setBounds(100, 100, 620, 600)
                setContent {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(LocalSkyTheme.current.background).padding(24.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                        SmoothingOptions(mode, com.skywing.desktop.playback.SmoothingStatus(
                            capabilities = com.skywing.desktop.playback.SmoothingCapabilities(240.0, 24000.0/1001, true, true, false, 1920, 1080),
                            detail = "Holding 59.94 fps · 0 drops",
                            settings = com.skywing.desktop.playback.MotionSettings(1)), {})
                        SkyButton("Done", {})
                    }
                }
                isVisible = true
                toFront()
            } }
            try {
                val robot = Robot()
                robot.waitForIdle()
                Thread.sleep(600)
                assertTrue(edt { window.isShowing })
                ImageIO.write(robot.createScreenCapture(edt { window.bounds }), "png", File(folder, "motion-panel-" + mode.name + ".png"))
            } finally { edt { window.dispose() } }
        }
    }

    @Test fun maximizedAndRepeatedRequestsRestoreWithoutPeerRecreation() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val frame = edt { Frame().apply { setBounds(100, 100, 800, 600); add(Canvas()); isVisible = true } }
        try {
            edt { frame.extendedState = Frame.MAXIMIZED_BOTH }
            Robot().waitForIdle()
            val hwnd = edt { Native.getComponentPointer(frame) }
            val canvas = edt { frame.components.single() }
            val child = edt { Native.getComponentPointer(canvas) }
            edt {
                val controller = BorderlessFullscreen(frame)
                repeat(5) {
                    assertTrue(controller.set(true))
                    assertTrue(controller.set(true))
                    assertFalse(controller.set(false))
                    assertFalse(controller.set(false))
                    assertEquals(Frame.MAXIMIZED_BOTH, frame.extendedState)
                    assertEquals(hwnd, Native.getComponentPointer(frame))
                    assertEquals(child, Native.getComponentPointer(canvas))
                    assertTrue(frame.isVisible)
                }
            }
        } finally { edt { frame.dispose() } }
    }

    @Test fun closedPlayerCannotBeResurrectedByQueuedCanvasAttach() {
        val player = EmbeddedMpv()
        player.close()
        player.close()
        assertFalse(player.attach(Canvas()))
        assertFalse(player.togglePause())
        assertNull(player.playbackPosition())
    }

    @Test(timeout = 40000) fun closingProductionApplicationLeavesNoBackgroundProcess() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(!GraphicsEnvironment.isHeadless())
        // The app runs with its own preferences and data folders: its startup library sync must not scan the user's
        // folders and shares or write their sync status.
        val data = java.nio.file.Files.createTempDirectory("skywing-close-").toFile()
        val log = File(data, "app-output.txt")
        val preferences = File(data, "preferences.properties")
        val home = File(data, "home").apply { mkdirs() }
        val support = File(IsolatedPreferencesFactory::class.java.protectionDomain.codeSource.location.toURI())
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java.exe").absolutePath,
            "-Xbootclasspath/a:${support.absolutePath}",
            "-Djava.util.prefs.PreferencesFactory=${IsolatedPreferencesFactory::class.java.name}",
            "-D${IsolatedPreferencesFactory.FILE_PROPERTY}=${preferences.absolutePath}",
            "-Duser.home=${home.absolutePath}",
            "-Dcompose.application.resources.dir=${System.getProperty("compose.application.resources.dir", "")}",
            "-cp", System.getProperty("skywing.test.runtimeClasspath"), "com.skywing.desktop.MainKt"
        ).redirectErrorStream(true).redirectOutput(log).apply {
            environment()["LOCALAPPDATA"] = File(data, "LocalAppData").apply { mkdirs() }.absolutePath
            environment()["APPDATA"] = File(data, "AppData").apply { mkdirs() }.absolutePath
            environment()["USERPROFILE"] = home.absolutePath
        }.start()
        try {
            var frame: Pointer? = null
            await("Production window did not appear: ${log.readText()}") {
                user32.EnumWindows(object : User32.WindowCallback {
                    override fun invoke(hwnd: Pointer, data: Pointer?): Boolean {
                        val pid = IntByReference()
                        user32.GetWindowThreadProcessId(hwnd, pid)
                        if (pid.value.toLong() == process.pid() && user32.IsWindowVisible(hwnd)) frame = hwnd
                        return true
                    }
                }, null)
                frame != null
            }
            // Let the SwingPanel's queued mpv attach run before requesting close.
            Thread.sleep(1500)
            assertTrue(user32.PostMessageW(checkNotNull(frame), 0x0010, null, null)) // WM_CLOSE
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS),
                "SkyWing remained alive after close: ${log.readText()}")
            assertEquals(0, process.exitValue(), log.readText())
            assertTrue(preferences.isFile && preferences.readText().contains("sync"),
                "The app did not use its isolated preferences file: ${log.readText()}")
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
            data.deleteRecursively()
        }
    }
}

