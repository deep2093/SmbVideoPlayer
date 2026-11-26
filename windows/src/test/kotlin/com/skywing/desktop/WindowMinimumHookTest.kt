package com.skywing.desktop

import org.junit.Assume.assumeTrue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/** WindowMinimum's hook on a real Win32 frame: installed once per window, and Windows then keeps it at 640 × 480 dp. */
class WindowMinimumHookTest {
    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test fun installingTwiceKeepsOneHook() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val frame = edt { Frame("SkyWing window minimum test").apply { setBounds(80, 80, 800, 600); isVisible = true } }
        try {
            assertTrue(edt { WindowMinimum.install(frame) }, "The hook goes in")
            assertTrue(edt { WindowMinimum.install(frame) }, "A second install keeps the one hook")
            assertTrue(edt { WindowMinimum.installed(frame) })
            edt { frame.setSize(100, 100) }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline && edt { frame.width < 640 || frame.height < 480 }) Thread.sleep(50)
            val size = edt { frame.size }
            // Java units are dp; the outer frame is never smaller than the visible one.
            assertTrue(size.width >= 640 && size.height >= 480, "Asked for 100 × 100, the frame is ${size.width} × ${size.height} dp")
        } finally {
            edt { frame.dispose() }
        }
    }
}
