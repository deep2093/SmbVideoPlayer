package com.skywing.desktop.artwork

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/**
 * The single place screens, the background lookup and manual choices go through for cover art, like the Android
 * ArtworkRepository. Choices live in a small file and are mirrored in memory, so picking a cover while scrolling is
 * a map lookup. Stored pictures sit in their own folder; nothing on your drives or shares is ever written.
 *
 * Pictures you chose, and portraits already found, are never replaced by a later lookup (automatic or Look up now):
 * only Reset, Clear looked-up portraits or a new choice in Change cover changes them.
 */
internal class ArtworkRepository(
    private val root: Path = defaultRoot(),
    val preferences: ArtworkPreferences = ArtworkPreferences(),
    val keys: ProviderKeys = ProviderKeys(),
) {
    private val rows = ConcurrentHashMap<String, ArtworkOverride>()
    private val candidates = ConcurrentHashMap<String, List<PerformerCandidate>>()
    private val sequence = AtomicLong()
    private val lock = Any()

    /** Grows whenever settings or stored artwork change, so screens rebuild what they show. */
    var revision by mutableStateOf(0)
        private set

    var settings by mutableStateOf(preferences.load())
        private set

    val imageDir: File get() = root.resolve("images").toFile()
    val coverDir: File get() = root.resolve("covers").toFile()
    private val dataFile: Path get() = root.resolve("artwork.bin")

    init {
        runCatching { load() }
    }

    fun updateSettings(change: (ArtworkSettings) -> ArtworkSettings) {
        val next = change(settings).sanitized()
        preferences.save(next)
        settings = next
        revision++
    }

    fun row(key: String): ArtworkOverride? = rows[key]

    /**
     * The stored choice for a folder. A Virtual subfolder ("vset:<folder>:<name>") with nothing of its own uses the
     * cover its name has elsewhere ("virtual:<name>"), so a portrait found once is shown again and never looked up twice.
     */
    fun effectiveRow(key: String): ArtworkOverride? {
        rows[key]?.let { return it }
        if (!key.startsWith("vset:")) return null
        val name = key.removePrefix("vset:").substringAfter(':', "")
        return if (name.isEmpty()) null else rows["virtual:$name"]
    }

    /** The folder row a video with this name may take its portrait from. */
    fun nameRow(identity: String): ArtworkOverride? =
        rows["virtual:$identity"] ?: rows.values.firstOrNull { it.key.startsWith("vset:") && it.key.endsWith(":$identity") && it.imageName != null }

    fun imageFile(name: String?): File? = name?.let { File(imageDir, it) }?.takeIf { it.isFile }

    fun decideFolder(key: String): ArtworkDecision {
        val decision = ArtworkResolver.decide(ArtworkKeys.scopeOf(key), effectiveRow(key), null, settings)
        // The picture is gone (deleted by hand): fall back to the existing cover rather than showing nothing.
        if (decision.source == ArtworkSource.IMAGE && imageFile(decision.imageName) == null) return ArtworkDecision(ArtworkSource.LEGACY)
        return decision
    }

    fun decideVideo(path: String, nameIdentity: String?): ArtworkDecision {
        val group = nameIdentity?.let(::nameRow)
        val decision = ArtworkResolver.decide(ArtworkScope.VIDEO, rows[ArtworkKeys.video(path)], group, settings)
        if (decision.source == ArtworkSource.IMAGE && imageFile(decision.imageName) == null) return ArtworkDecision(ArtworkSource.LEGACY)
        return decision
    }

    fun hasOverride(key: String): Boolean = rows[key]?.manual == true

    // ---- Changing artwork ----

    /**
     * Stores [image] as the cover of [key], once for landscape cards (16:9) and once for taller cards (3:4), cut
     * around the same focus point. Manual choices beat every default; automatic ones (portraits found in the
     * background) never replace a manual choice.
     */
    suspend fun saveImage(
        key: String,
        mode: ArtworkMode,
        image: Image,
        manual: Boolean,
        candidate: PerformerCandidate? = null,
        focusX: Float = 0.5f,
        focusY: Float = 0.3f,
        zoom: Float = 1f,
    ) = withContext(Dispatchers.IO) {
        val wide = CoverCrop.apply(image, CoverCrop.CARD_ASPECT, focusX, focusY, IMAGE_WIDTH, zoom)
        val tall = CoverCrop.apply(image, CoverCrop.TALL_ASPECT, focusX, focusY, TALL_WIDTH, zoom)
        synchronized(lock) {
            if (!manual && rows[key]?.manual == true) return@synchronized
            val name = newName(key, "jpg")
            publish(File(imageDir, name), wide)
            publish(tallFile(File(imageDir, name)), tall)
            replace(key, ArtworkOverride(key, mode, manual, name, candidate?.providerId, candidate?.performerId, candidate?.name, ArtworkOverride.STATUS_OK, System.currentTimeMillis()))
        }
        changed()
    }

    /** Stores a moving cover exactly as given: an animated GIF ("gif") or a flip-book strip ("strip8.jpg"). */
    suspend fun saveAnimated(key: String, bytes: ByteArray, extension: String) = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ANIMATED_BYTES) { "The moving picture is too large" }
        synchronized(lock) {
            val name = newName(key, extension)
            publish(File(imageDir, name), bytes)
            replace(key, ArtworkOverride(key, ArtworkMode.CUSTOM, true, name, lookupStatus = ArtworkOverride.STATUS_OK, updatedAt = System.currentTimeMillis()))
        }
        changed()
    }

    /** Stores a choice that needs no picture (first frame, collage, automatic frame, none, existing) as a manual choice. */
    suspend fun setMode(key: String, mode: ArtworkMode) = withContext(Dispatchers.IO) {
        synchronized(lock) { replace(key, ArtworkOverride(key, mode, true, updatedAt = System.currentTimeMillis())) }
        changed()
    }

    /** Remembers that an automatic lookup finished without applying anything, so it is not repeated every time. */
    suspend fun recordLookup(key: String, status: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val existing = rows[key]
            if (existing != null && existing.manual) return@synchronized
            rows[key] = (existing ?: ArtworkOverride(key, ArtworkMode.PORTRAIT, false)).copy(lookupStatus = status, updatedAt = System.currentTimeMillis())
            persist()
        }
        changed()
    }

    /** Removes everything stored for [keys]: the item goes back to the default. Returns how many had something. */
    suspend fun clear(keys: Collection<String>): Int = withContext(Dispatchers.IO) {
        var removed = 0
        synchronized(lock) {
            keys.forEach { key ->
                rows.remove(key)?.let { removed++; it.imageName?.let(::deleteImage) }
                candidates.remove(key)
            }
            if (removed > 0) persist()
        }
        if (removed > 0) changed()
        removed
    }

    suspend fun clearAll(): Int = clear(rows.keys.toList())

    /** Removes every cover and portrait of folders; pictures chosen for videos stay. */
    suspend fun clearFolderArtwork(): Int = clear(rows.keys.filter { !it.startsWith("video:") })

    /** Removes every picture chosen for a video (frames, moving covers, pictures), so its thumbnail shows again. */
    suspend fun clearVideoArtwork(): Int = clear(rows.keys.filter { it.startsWith("video:") })

    /** Removes only automatically found portraits; pictures you chose are kept. */
    suspend fun clearFetchedPortraits(): Int = clear(rows.values.filter { !it.manual && it.imageName != null }.map { it.key })

    // ---- Suggestions waiting for the user ----

    /** Folders with suggestions to choose from. */
    fun reviewQueue(): List<ArtworkOverride> = rows.values
        .filter { !it.manual && it.imageName == null && it.lookupStatus in setOf(ArtworkOverride.STATUS_REVIEW, ArtworkOverride.STATUS_AMBIGUOUS) && candidates[it.key].orEmpty().isNotEmpty() }
        .sortedBy { it.key }

    fun suggestions(key: String): List<PerformerCandidate> = candidates[key].orEmpty()

    /** Keeps [found] for [key] so the user can pick one later. Never touches a manual choice. */
    suspend fun saveSuggestions(key: String, found: List<PerformerCandidate>) {
        if (rows[key]?.manual == true) return
        val keep = found.filter { !it.imageUrl.isNullOrBlank() }.distinctBy { it.providerId to it.performerId }.take(MAX_SUGGESTIONS)
        if (keep.isEmpty()) candidates.remove(key) else candidates[key] = keep
        recordLookup(key, if (keep.isNotEmpty()) ArtworkOverride.STATUS_REVIEW else ArtworkOverride.STATUS_NO_MATCH)
    }

    /** The user skipped a folder in Review suggestions: forget its suggestions and do not suggest again by itself. */
    suspend fun skipSuggestions(key: String) {
        candidates.remove(key)
        recordLookup(key, ArtworkOverride.STATUS_NO_MATCH)
    }

    // ---- Storage ----

    private fun changed() {
        revision++
    }

    private fun replace(key: String, row: ArtworkOverride) {
        val previous = rows[key]?.imageName
        rows[key] = row
        candidates.remove(key)
        persist()
        if (previous != null && previous != row.imageName) deleteImage(previous)
    }

    private fun newName(key: String, extension: String) = "${hash(key)}_${System.currentTimeMillis()}_${sequence.incrementAndGet()}.$extension"

    private fun publish(target: File, bytes: ByteArray) {
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeBytes(bytes)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun deleteImage(name: String) {
        val file = File(imageDir, name)
        file.delete()
        tallFile(file).delete()
    }

    private fun load() {
        val path = dataFile
        if (!Files.isRegularFile(path)) return
        DataInputStream(Files.newInputStream(path).buffered()).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return
            repeat(input.readInt().coerceIn(0, MAX_ROWS)) {
                val key = input.readUTF()
                val mode = input.readUTF()
                val row = ArtworkOverride(
                    key = key,
                    mode = ArtworkMode.entries.firstOrNull { m -> m.name == mode } ?: ArtworkMode.EXISTING,
                    manual = input.readBoolean(),
                    imageName = input.readUTF().ifEmpty { null },
                    providerId = input.readUTF().ifEmpty { null },
                    performerId = input.readUTF().ifEmpty { null },
                    performerName = input.readUTF().ifEmpty { null },
                    lookupStatus = input.readUTF(),
                    updatedAt = input.readLong(),
                )
                rows[row.key] = row
            }
            repeat(input.readInt().coerceIn(0, MAX_ROWS)) {
                val key = input.readUTF()
                candidates[key] = List(input.readInt().coerceIn(0, MAX_SUGGESTIONS)) {
                    val c = PerformerCandidate(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF().ifEmpty { null }, input.readUTF().ifEmpty { null }, input.readUTF().ifEmpty { null })
                    c.copy(images = listOfNotNull(c.imageUrl))
                }
            }
        }
    }

    private fun persist() {
        Files.createDirectories(root)
        val temporary = dataFile.resolveSibling("artwork.bin.tmp")
        DataOutputStream(Files.newOutputStream(temporary).buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            val all = rows.values.toList()
            out.writeInt(all.size)
            all.forEach { r ->
                out.writeUTF(r.key); out.writeUTF(r.mode.name); out.writeBoolean(r.manual)
                out.writeUTF(r.imageName.orEmpty()); out.writeUTF(r.providerId.orEmpty()); out.writeUTF(r.performerId.orEmpty())
                out.writeUTF(r.performerName.orEmpty()); out.writeUTF(r.lookupStatus); out.writeLong(r.updatedAt)
            }
            val waiting = candidates.entries.toList()
            out.writeInt(waiting.size)
            waiting.forEach { (key, list) ->
                out.writeUTF(key)
                out.writeInt(list.size)
                list.forEach { c ->
                    out.writeUTF(c.providerId); out.writeUTF(c.providerName); out.writeUTF(c.performerId); out.writeUTF(c.name)
                    out.writeUTF(c.disambiguation.orEmpty()); out.writeUTF(c.imageUrl.orEmpty()); out.writeUTF(c.previewUrl.orEmpty())
                }
            }
        }
        Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val IMAGE_WIDTH = 640
        const val TALL_WIDTH = 480
        const val MAX_SUGGESTIONS = 12
        const val MAX_ANIMATED_BYTES = 20_000_000
        private const val MAGIC = 0x53574152
        private const val VERSION = 1
        private const val MAX_ROWS = 500_000

        fun defaultRoot(): Path {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
            return Paths.get(base, "SkyWing", "artwork")
        }

        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

        /** The 3:4 version saved next to a chosen picture. */
        fun tallFile(file: File): File = File(file.parentFile, file.nameWithoutExtension + "_t.jpg")

        /** The picture for a card shape: the 3:4 version for taller cards when one exists, otherwise the picture itself. */
        fun forShape(file: File?, tall: Boolean): File? {
            if (!tall || file == null) return file
            val variant = tallFile(file)
            return if (variant.isFile) variant else file
        }
    }
}
