package com.skywing.desktop

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** A video a scan found, with the size and time its folder listing gave. */
internal data class FoundVideo(val file: File, val size: Long, val modified: Long)

/** The video files SkyWing lists, and the folder walk every scan uses. */
internal object VideoFiles {
    private val extensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "ts", "m2ts", "hevc", "flv", "mpg", "mpeg")

    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions

    /**
     * Adds the videos under [start] to [found], keyed by lower-case path (the first one found wins), until it holds
     * [limit]; returns true when it stopped there. Size and time come from the folder listing, so a folder costs one
     * listing, not one more request per file (which over a network drive is a round trip each). Links are not
     * followed. A folder that can't be read throws, since the videos found under [start] are then incomplete.
     */
    fun walk(start: Path, found: MutableMap<String, FoundVideo>, limit: Int, checkCancelled: () -> Unit = {}): Boolean {
        var full = false
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (!attributes.isRegularFile || attributes.isSymbolicLink || !isVideo(file.fileName.toString())) return FileVisitResult.CONTINUE
                val path = file.toAbsolutePath().normalize()
                found.putIfAbsent(path.toString().lowercase(), FoundVideo(path.toFile(), attributes.size(), attributes.lastModifiedTime().toMillis()))
                if (found.size < limit) return FileVisitResult.CONTINUE
                full = true
                return FileVisitResult.TERMINATE
            }
        })
        return full
    }
}
