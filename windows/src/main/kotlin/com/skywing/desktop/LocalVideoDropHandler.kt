package com.skywing.desktop

import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.SwingUtilities

/** Accept an Explorer file list without asking AWT to create another video surface. */
internal class LocalVideoDropHandler(private val open: (File) -> Unit) : DropTargetAdapter() {
    override fun dragEnter(event: DropTargetDragEvent) {
        if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            event.acceptDrag(DnDConstants.ACTION_COPY)
        } else {
            event.rejectDrag()
        }
    }

    override fun drop(event: DropTargetDropEvent) {
        if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            event.rejectDrop()
            return
        }
        event.acceptDrop(DnDConstants.ACTION_COPY)
        val selected = try {
            val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            files?.filterIsInstance<File>()?.firstOrNull { it.isFile }
        } catch (_: Exception) {
            null
        }
        event.dropComplete(selected != null)
        if (selected != null) SwingUtilities.invokeLater { open(selected) }
    }
}
