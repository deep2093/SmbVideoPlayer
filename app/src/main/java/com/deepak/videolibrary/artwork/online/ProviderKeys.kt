package com.deepak.videolibrary.artwork.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provider API keys. They are stored only as AES-GCM ciphertext under a key in the Android Keystore, are never
 * written to logs, and are not part of the repository. If the Keystore cannot be used the key simply is not saved.
 */
class ProviderKeys(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("artwork-provider-keys", Context.MODE_PRIVATE)

    init {
        // ThePornDB was removed (its API is paid): forget any token an older version saved for it.
        if (prefs.contains(com.deepak.videolibrary.artwork.ProviderIds.RETIRED_THE_PORN_DB)) {
            prefs.edit().remove(com.deepak.videolibrary.artwork.ProviderIds.RETIRED_THE_PORN_DB).apply()
        }
    }

    fun has(providerId: String): Boolean = prefs.contains(providerId)

    fun get(providerId: String): String? =
        prefs.getString(providerId, null)?.let { decrypt(it) }?.takeIf { it.isNotBlank() }

    /** Returns false when the key could not be stored securely. */
    fun save(providerId: String, key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed.length > 512 || trimmed.any { it.isWhitespace() || it.code < 32 }) return false
        val encrypted = try {
            encrypt(trimmed)
        } catch (e: Exception) {
            return false
        }
        prefs.edit().putString(providerId, encrypted).apply()
        return true
    }

    fun clear(providerId: String) {
        prefs.edit().remove(providerId).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "artwork_provider_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
