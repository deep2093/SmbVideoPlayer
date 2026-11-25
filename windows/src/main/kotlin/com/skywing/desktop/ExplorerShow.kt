package com.skywing.desktop

import java.io.File
import kotlin.concurrent.thread

/** The command that opens File Explorer on [target]: with [select], on its folder with the file marked. */
internal fun explorerCommand(target: File, select: Boolean): List<String> =
    if (select) listOf("explorer.exe", "/select," + target.absolutePath) else listOf("explorer.exe", target.absolutePath)

/** Shows [target] in File Explorer. Starts the process off the screen thread; never writes, and a failure is ignored. */
internal fun showInExplorer(target: File, select: Boolean) {
    thread(isDaemon = true, name = "show-in-explorer") {
        runCatching { ProcessBuilder(explorerCommand(target, select)).start() }
    }
}
