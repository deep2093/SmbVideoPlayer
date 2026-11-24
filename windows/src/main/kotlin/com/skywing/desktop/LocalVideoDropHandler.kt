package com.skywing.desktop

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.SwingUtilities

/**
 * The video a drop plays: the first video file in an Explorer file list, as "Open with" plays it (B37). Folders,
 * other files and files no longer there are passed over; null when there is no video or the list can't be read.
 */
internal fun droppedVideo(transferable: Transferable): File? = try {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) null
    else (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
        ?.filterIsInstance<File>()
        ?.firstOrNull { it.isFile && VideoFiles.isVideo(it.name) }
} catch (_: Exception) {
    // The source went away, or the list is in a form AWT can't give.
    null
}

/**
 * Drops on the video surface. The surface is a native window of its own, so Compose's drop target, which takes drops
 * everywhere else in the window, never sees them. The handler doesn't ask AWT to create another video surface.
 */
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
        val selected = droppedVideo(event.transferable)
        event.dropComplete(selected != null)
        if (selected != null) SwingUtilities.invokeLater { open(selected) }
    }
}
