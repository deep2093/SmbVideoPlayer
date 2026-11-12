package com.skywing.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import com.skywing.desktop.playback.EmbeddedMpv
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
        fun GetWindowLongW(hwnd: Pointer, index: Int): Int
    }
    private val user32 by lazy { Native.load("user32", User32::class.java) }

    private fun <T> edt(block: () -> T): T {
        val task = FutureTask(block)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail(message)
    }
    private fun canvases(component: Component): List<Canvas> =
        (if (component.javaClass == Canvas::class.java) listOf(component as Canvas) else emptyList()) +
            (if (component is Container) component.components.flatMap(::canvases) else emptyList())

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
            fun key(code: Int, release: Boolean = true) {
                edt {
                    val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    manager.dispatchEvent(KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED))
                    if (release) manager.dispatchEvent(KeyEvent(canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED))
                }
            }
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
                    if (rendererReport.contains("preference: NVIDIA D3D11")) {
                        assertTrue(rendererReport.contains("Renderer: NVIDIA"), rendererReport)
                    }
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
                    val center = edt { window.bounds.let { java.awt.Point(it.x + it.width / 2, it.y + it.height / 2) } }
                    robot.mouseMove(center.x, center.y)
                    fun overlaysVisible() = edt { window.ownedWindows.count { it.name?.startsWith("skywing-") == true && it.isVisible } == 2 }
                    await("Fullscreen controls were not shown") { overlaysVisible() }
                    // Fractional Windows DPI can round the Swing surface up by one logical pixel.
                    await("Video must fill fullscreen") { edt { kotlin.math.abs(canvas.height - window.height) <= 1 && kotlin.math.abs(canvas.width - window.width) <= 1 } }
                    val videoBounds = edt { Rectangle(canvas.bounds) }
                    val captures = File("build/reports/tests/test/screenshots").apply { mkdirs() }
                    robot.waitForIdle()
                    ImageIO.write(robot.createScreenCapture(edt { window.bounds }), "png", File(captures, "fullscreen-controls.png"))
                    await("Fullscreen controls did not auto-hide") { !overlaysVisible() }
                    assertEquals(videoBounds, edt { Rectangle(canvas.bounds) }, "Auto-hide resized the video")
                    key(KeyEvent.VK_M)
                    await("Keyboard input did not reveal controls") { overlaysVisible() }
                    await("Controls did not hide again after keyboard input") { !overlaysVisible() }
                    robot.mouseMove(center.x + 20, center.y)
                    await("Native video mouse motion did not reveal controls") { overlaysVisible() }
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
        val log = File.createTempFile("skywing-close-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java.exe").absolutePath,
            "-Dcompose.application.resources.dir=${System.getProperty("compose.application.resources.dir", "")}",
            "-cp", System.getProperty("skywing.test.runtimeClasspath"), "com.skywing.desktop.MainKt"
        ).redirectErrorStream(true).redirectOutput(log).start()
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
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
            log.delete()
        }
    }
}

