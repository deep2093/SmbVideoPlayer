package com.deepak.videolibrary.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the imported WireGuard `.conf`, which contains a private key.
 *
 * - Encrypted with AES-256-GCM under a non-exportable Android Keystore key.
 * - Written under [Context.getNoBackupFilesDir], which Android never backs up or transfers.
 * - The plain text is only ever held in memory for the moment a tunnel is built.
 * - Nothing here logs configuration content.
 */
class WireGuardConfigStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.noBackupFilesDir, "wireguard")
    private val file = File(dir, "tunnel.conf.enc")

    @Synchronized
    fun hasConfig(): Boolean = file.isFile && file.length() > 0

    /** Returns the stored configuration text, or null when none exists or the key was lost. */
    @Synchronized
    fun load(): String? {
        if (!hasConfig()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= GCM_IV_BYTES) return null
            val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
            val data = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            // Keystore key lost (e.g. restored device) or file corrupt: behave as "no config".
            null
        }
    }

    /** Replaces any existing configuration atomically. */
    @Synchronized
    @Throws(IOException::class)
    fun save(text: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create storage folder")
        val tmp = File(dir, "tunnel.conf.enc.tmp")
        tmp.writeBytes(encrypted)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not save configuration")
        }
    }

    /** Deletes the file and the Keystore key so no key material remains. */
    @Synchronized
    fun clear() {
        file.delete()
        File(dir, "tunnel.conf.enc.tmp").delete()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wireguard_config_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
    }
}

/** Non-secret VPN preferences. */
class VpnPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)

    var autoConnect: Boolean
        get() = prefs.getBoolean("auto_connect", false)
        set(value) { prefs.edit().putBoolean("auto_connect", value).apply() }

    /** Route only this app through the tunnel (VpnService.Builder.addAllowedApplication). */
    var appOnly: Boolean
        get() = prefs.getBoolean("app_only", true)
        set(value) { prefs.edit().putBoolean("app_only", value).apply() }
}
