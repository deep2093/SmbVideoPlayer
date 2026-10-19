package com.deepak.videolibrary.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.deepak.videolibrary.smb.SmbProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SmbSettings(
    val host: String = "",
    val share: String = "",
    val rootPath: String = "",
    val username: String = "",
    val password: String = "",
    val savePassword: Boolean = false,
    val domain: String = "",
    val requireSigning: Boolean = false,
    val requireEncryption: Boolean = false,
)

/**
 * Connection settings. The password is only written to disk when "Save password" is on,
 * and then only as AES-GCM ciphertext under a key that lives in the Android Keystore.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("smb_settings", Context.MODE_PRIVATE)

    /** Password typed this session when "Save password" is off. Memory only. */
    @Volatile
    private var sessionPassword: String = ""

    @Volatile private var cached: SmbSettings? = null

    @Synchronized
    fun load(): SmbSettings {
        cached?.let { return it }
        val save = prefs.getBoolean(KEY_SAVE, false)
        val password = if (save) {
            prefs.getString(KEY_PASSWORD, null)?.let { decrypt(it) } ?: ""
        } else {
            sessionPassword
        }
        return SmbSettings(
            host = prefs.getString(KEY_HOST, "") ?: "",
            share = prefs.getString(KEY_SHARE, "") ?: "",
            rootPath = prefs.getString(KEY_ROOT, "") ?: "",
            username = prefs.getString(KEY_USER, "") ?: "",
            password = password,
            savePassword = save,
            domain=prefs.getString("domain", "") ?: "",
            requireSigning=prefs.getBoolean("require_signing",false),
            requireEncryption=prefs.getBoolean("require_encryption",false),
        ).also { cached = it }
    }

    @Synchronized
    fun save(settings: SmbSettings) {
        // Encrypt before touching preferences or the session password.
        val encrypted = if (settings.savePassword) encrypt(settings.password) else null
        val editor = prefs.edit()
            .putString(KEY_HOST, settings.host.trim())
            .putString(KEY_SHARE, settings.share.trim())
            .putString(KEY_ROOT, normalizeRoot(settings.rootPath))
            .putString(KEY_USER, settings.username.trim())
            .putBoolean(KEY_SAVE, settings.savePassword)
            .putString("domain",settings.domain.trim())
            .putBoolean("require_signing",settings.requireSigning)
            .putBoolean("require_encryption",settings.requireEncryption)
        if (settings.savePassword) {
            editor.putString(KEY_PASSWORD, encrypted)
            sessionPassword = ""
        } else {
            editor.remove(KEY_PASSWORD)
            sessionPassword = settings.password
        }
        editor.apply()
        cached = settings.copy(host=settings.host.trim(),share=settings.share.trim(),rootPath=normalizeRoot(settings.rootPath),username=settings.username.trim(),domain=settings.domain.trim())
    }

    /** Null when host or share is missing. */
    fun loadProfile(): SmbProfile? {
        val s = load()
        if (s.host.isBlank() || s.share.isBlank()) return null
        return SmbProfile(
            host = s.host,
            share = s.share,
            rootPath = normalizeRoot(s.rootPath),
            username = s.username,
            password = s.password,
        domain=s.domain,requireSigning=s.requireSigning,requireEncryption=s.requireEncryption,
        )
    }

    fun toProfile(s: SmbSettings): SmbProfile = SmbProfile(
        host = s.host,
        share = s.share,
        rootPath = normalizeRoot(s.rootPath),
        username = s.username,
        password = s.password,
        domain=s.domain,requireSigning=s.requireSigning,requireEncryption=s.requireEncryption,
    )

    // ---- Keystore-backed encryption ----

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
        val data = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    } catch (e: Exception) {
        // Key lost or data corrupt: treat as "no saved password" instead of crashing.
        null
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "smb_password_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12

        private const val KEY_HOST = "host"
        private const val KEY_SHARE = "share"
        private const val KEY_ROOT = "root"
        private const val KEY_USER = "user"
        private const val KEY_SAVE = "save_password"
        private const val KEY_PASSWORD = "password_enc"

        fun normalizeRoot(raw: String): String =
            raw.trim().replace('\\', '/').trim('/')
    }
}
