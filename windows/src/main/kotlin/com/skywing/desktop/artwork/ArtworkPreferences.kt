package com.skywing.desktop.artwork

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import java.util.Base64
import java.util.prefs.Preferences

/** The artwork settings, kept in the user's Windows preferences. */
internal class ArtworkPreferences(private val node: Preferences = Preferences.userRoot().node("com/skywing/desktop/artwork")) {
    fun load(): ArtworkSettings {
        val d = ArtworkSettings()
        fun <T : Enum<T>> enumOf(key: String, fallback: T, values: Array<T>): T =
            values.firstOrNull { it.name == node.get(key, fallback.name) } ?: fallback
        return ArtworkSettings(
            enabled = node.getBoolean("enabled", d.enabled),
            videoMode = enumOf("video_mode", d.videoMode, ArtworkMode.entries.toTypedArray()),
            smbFolderMode = enumOf("smb_mode", d.smbFolderMode, ArtworkMode.entries.toTypedArray()),
            virtualFolderMode = enumOf("virtual_mode", d.virtualFolderMode, ArtworkMode.entries.toTypedArray()),
            customFolderMode = enumOf("custom_mode", enumOf("virtual_mode", d.virtualFolderMode, ArtworkMode.entries.toTypedArray()), ArtworkMode.entries.toTypedArray()),
            adultConsent = node.getBoolean("adult_consent", d.adultConsent),
            portraitsOnFolders = node.getBoolean("portraits_folders", d.portraitsOnFolders),
            portraitsOnVideos = node.getBoolean("portraits_videos", d.portraitsOnVideos),
            autoFetchOnScan = node.getBoolean("auto_fetch", d.autoFetchOnScan),
            collageTiles = node.getInt("collage_tiles", d.collageTiles),
            videoUseFirstName = node.getBoolean("video_first_name", d.videoUseFirstName),
            folderUseWholeName = node.getBoolean("folder_whole_name", d.folderUseWholeName),
            customEndpoint = node.get("endpoint", ""),
            smbFolderSelection = enumOf("smb_selection", d.smbFolderSelection, PortraitSelection.entries.toTypedArray()),
            virtualFolderSelection = enumOf("virtual_selection", d.virtualFolderSelection, PortraitSelection.entries.toTypedArray()),
            customFolderSelection = enumOf("custom_selection", enumOf("virtual_selection", d.virtualFolderSelection, PortraitSelection.entries.toTypedArray()), PortraitSelection.entries.toTypedArray()),
        ).sanitized()
    }

    fun save(s: ArtworkSettings) {
        node.putBoolean("enabled", s.enabled)
        node.put("video_mode", s.videoMode.name)
        node.put("smb_mode", s.smbFolderMode.name)
        node.put("virtual_mode", s.virtualFolderMode.name)
        node.put("custom_mode", s.customFolderMode.name)
        node.putBoolean("adult_consent", s.adultConsent)
        node.putBoolean("portraits_folders", s.portraitsOnFolders)
        node.putBoolean("portraits_videos", s.portraitsOnVideos)
        node.putBoolean("auto_fetch", s.autoFetchOnScan)
        node.putInt("collage_tiles", s.collageTiles)
        node.putBoolean("video_first_name", s.videoUseFirstName)
        node.putBoolean("folder_whole_name", s.folderUseWholeName)
        node.put("endpoint", s.customEndpoint)
        node.put("smb_selection", s.smbFolderSelection.name)
        node.put("virtual_selection", s.virtualFolderSelection.name)
        node.put("custom_selection", s.customFolderSelection.name)
        runCatching { node.flush() }
    }

    /** A short note about the last background lookup, shown in Settings. Never holds keys or paths. */
    var lastNote: String?
        get() = node.get("last_note", null)
        set(value) {
            if (value == null) node.remove("last_note") else node.put("last_note", value)
        }
}

/**
 * The StashDB API key. It is stored only encrypted with Windows' own data protection (DPAPI, tied to this Windows
 * user), is never written to logs, and is not part of the repository. If encryption is unavailable the key is not
 * saved.
 */
internal class ProviderKeys(private val node: Preferences = Preferences.userRoot().node("com/skywing/desktop/artwork-keys")) {
    fun has(): Boolean = node.get(StashDb.ID, null) != null

    fun get(): String? = node.get(StashDb.ID, null)?.let { runCatching { Dpapi.unprotect(Base64.getDecoder().decode(it)) }.getOrNull() }
        ?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

    /** False when the key is not acceptable or could not be stored securely. */
    fun save(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed.length > 512 || trimmed.any { it.isWhitespace() || it.code < 32 }) return false
        val sealed = runCatching { Dpapi.protect(trimmed.toByteArray(Charsets.UTF_8)) }.getOrNull() ?: return false
        node.put(StashDb.ID, Base64.getEncoder().encodeToString(sealed))
        runCatching { node.flush() }
        return true
    }

    fun clear() {
        node.remove(StashDb.ID)
        runCatching { node.flush() }
    }
}

/** Windows data protection (CryptProtectData), which encrypts with a key only this Windows user can use. */
private object Dpapi {
    @Structure.FieldOrder("cbData", "pbData")
    class Blob : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null
    }

    private interface Crypt32 : Library {
        fun CryptProtectData(input: Blob, description: WString?, entropy: Blob?, reserved: Pointer?, prompt: Pointer?, flags: Int, output: Blob): Boolean
        fun CryptUnprotectData(input: Blob, description: Pointer?, entropy: Blob?, reserved: Pointer?, prompt: Pointer?, flags: Int, output: Blob): Boolean
    }

    private interface Kernel32 : Library {
        fun LocalFree(memory: Pointer?): Pointer?
    }

    private val crypt: Crypt32 by lazy { Native.load("crypt32", Crypt32::class.java) }
    private val kernel: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    private const val UI_FORBIDDEN = 0x1

    private fun blobOf(bytes: ByteArray): Blob {
        val memory = Memory(bytes.size.toLong().coerceAtLeast(1))
        memory.write(0, bytes, 0, bytes.size)
        return Blob().apply { cbData = bytes.size; pbData = memory }
    }

    private fun take(out: Blob): ByteArray = try {
        out.pbData!!.getByteArray(0, out.cbData)
    } finally {
        kernel.LocalFree(out.pbData)
    }

    fun protect(plain: ByteArray): ByteArray {
        val out = Blob()
        check(crypt.CryptProtectData(blobOf(plain), WString("SkyWing"), null, null, null, UI_FORBIDDEN, out)) { "Encryption failed" }
        return take(out)
    }

    fun unprotect(sealed: ByteArray): ByteArray {
        val out = Blob()
        check(crypt.CryptUnprotectData(blobOf(sealed), null, null, null, null, UI_FORBIDDEN, out)) { "Decryption failed" }
        return take(out)
    }
}
