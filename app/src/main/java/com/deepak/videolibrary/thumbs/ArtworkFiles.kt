package com.deepak.videolibrary.thumbs

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Publish only fully encoded files; preserve existing artwork on write failure. */
internal object ArtworkFiles {
    fun publish(target: File, encode: (FileOutputStream) -> Unit) {
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Artwork storage unavailable" }
        val temporary = File.createTempFile("art-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temporary).use { output -> encode(output); output.fd.sync() }
            check(temporary.length() > 0) { "Empty artwork" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
