package com.skywing.desktop

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.prefs.Preferences

/** A file in SkyWing's library folder, `%LOCALAPPDATA%\SkyWing\library`, next to the library index. */
internal fun libraryFile(name: String): Path {
    val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
    return Paths.get(base, "SkyWing", "library", name)
}

/** The registry node [name] under [type]'s package, or null when there is none (asking doesn't make one). */
internal fun registryNode(type: Class<*>, name: String): Preferences? = runCatching {
    val parent = Preferences.userNodeForPackage(type)
    if (parent.nodeExists(name)) parent.node(name) else null
}.getOrNull()

/** Removes a registry node whose values a JSON file now holds. */
internal fun Preferences.removeMoved() {
    val parent = parent()
    removeNode()
    parent?.flush()
}

/**
 * A small store kept as one JSON object in one file (B20). It is written whole to a temporary file that is then moved
 * over the old one, so a crash or a full disk leaves the last good file, never half of one.
 */
internal class JsonFile(val path: Path) {
    sealed interface Read {
        data class Found(val root: JsonObject) : Read
        /** No file yet. */
        data object Missing : Read
        /** The file can't be read now (another program may have it open). Nothing may be written over it. */
        data object Locked : Read
    }

    /**
     * Reads the file. A file that isn't a JSON object (cut short, or changed by hand) is moved aside to
     * `<name>.damaged` and counts as missing, so the next write starts a fresh file without destroying the old one.
     */
    fun read(): Read {
        val text = try {
            Files.readString(path)
        } catch (_: NoSuchFileException) {
            return Read.Missing
        } catch (_: Exception) {
            return Read.Locked
        }
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        if (root != null) return Read.Found(root)
        return try {
            Files.move(path, path.resolveSibling(path.fileName.toString() + ".damaged"), StandardCopyOption.REPLACE_EXISTING)
            Read.Missing
        } catch (_: Exception) {
            Read.Locked
        }
    }

    fun write(root: JsonObject) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(temporary, root.toString())
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
