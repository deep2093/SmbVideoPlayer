package com.deepak.videolibrary.downloads

/** Naming rules for files saved to the device. */
object DownloadPaths {
    /** Everything the app downloads goes under this folder in the device's Download folder. */
    const val ROOT = "SkyWing"
    // Completed and in-progress pre-rename downloads retain their stored MediaStore URI.
    // No path rewrite, move or deletion is performed for the historical Download/Veyra directory.

    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** A single file or folder name that every Android file system accepts. */
    fun cleanSegment(name: String): String {
        val cleaned = name.replace(illegal, "_").trim().trim('.').take(120)
        return cleaned.ifBlank { "_" }
    }

    /** A sub-folder path made of clean segments (empty parts dropped). */
    fun cleanFolder(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/") { cleanSegment(it) }

    /** The MediaStore relative path for [targetFolder] (always ends with a slash). */
    fun relativePath(targetFolder: String): String {
        val sub = cleanFolder(targetFolder)
        return if (sub.isEmpty()) "Download/$ROOT/" else "Download/$ROOT/$sub/"
    }

    /**
     * Where a file found while downloading a whole folder goes: the folder's own name, then the path below it.
     * `folder = "Shows/Ann"`, `file = "Shows/Ann/S1/e1.mp4"` gives `Ann/S1`.
     */
    fun folderTarget(folder: String, file: String): String {
        val base = folder.trim('/')
        val name = base.substringAfterLast('/')
        val below = file.trim('/').removePrefix(base).trim('/').substringBeforeLast('/', "")
        return cleanFolder(if (below.isEmpty()) name else "$name/$below")
    }

    fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "ts" -> "video/mp2t"
            "srt" -> "application/x-subrip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }
}
