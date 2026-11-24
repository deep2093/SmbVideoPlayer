package com.skywing.desktop.automation

import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Robot
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.TransferHandler

/**
 * Drags files onto a point of the screen with the real pointer, the way a drag from Explorer arrives: a small window
 * of the test's own offers an Explorer-style file list over OLE drag and drop, and the pointer carries it across.
 * Points are in [Win32.bounds] coordinates. It takes the pointer, so only tests that may take the desktop use it.
 */
internal object FileDrag {
    fun drop(files: List<File>, x: Int, y: Int) {
        val robot = Robot()
        lateinit var source: JFrame
        SwingUtilities.invokeAndWait { source = sourceWindow(files) }
        try {
            robot.waitForIdle()
            val hwnd = Pointer.nativeValue(checkNotNull(Native.getComponentPointer(source)) { "no drag source window" })
            val from = Win32.bounds(hwnd).let { (it.left + it.right) / 2 to (it.top + it.bottom) / 2 }
            Win32.moveCursor(from.first, from.second)
            robot.delay(300)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            // Small steps, so the source sees a drag start and the target sees the pointer arrive over it.
            val steps = 40
            for (step in 1..steps) {
                Win32.moveCursor(from.first + (x - from.first) * step / steps, from.second + (y - from.second) * step / steps)
                robot.delay(25)
            }
            robot.delay(400)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.delay(500)
        } finally {
            SwingUtilities.invokeAndWait { source.dispose() }
        }
    }

    private fun sourceWindow(files: List<File>): JFrame {
        val label = JLabel("SkyWing test files")
        label.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent) = COPY
            override fun createTransferable(c: JComponent): Transferable = FileList(files)
        }
        val drag = object : MouseAdapter() {
            private var started = false
            override fun mouseDragged(e: MouseEvent) {
                if (started) return
                started = true
                label.transferHandler.exportAsDrag(label, e, TransferHandler.COPY)
            }
        }
        label.addMouseMotionListener(drag)
        return JFrame("SkyWing test drag source").apply {
            isUndecorated = true
            isAlwaysOnTop = true
            contentPane.add(label)
            setBounds(40, 40, 160, 80)
            isVisible = true
        }
    }

    private class FileList(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return files
        }
    }
}
