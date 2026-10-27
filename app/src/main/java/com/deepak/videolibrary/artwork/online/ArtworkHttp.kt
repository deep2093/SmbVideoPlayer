package com.deepak.videolibrary.artwork.online

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal, bounded HTTPS client for provider calls and image downloads. HTTPS only, short timeouts, a hard cap on
 * bytes read, no cookies, and API requests never follow redirects. Request headers (which hold API keys) are never
 * logged.
 */
internal object ArtworkHttp {
    private const val MAX_JSON_BYTES = 1_000_000L

    suspend fun getJson(url: String, headers: Map<String, String>): String = String(request("GET", url, headers, null, MAX_JSON_BYTES), Charsets.UTF_8)

    suspend fun postJson(url: String, headers: Map<String, String>, body: String): String =
        String(request("POST", url, headers, body.toByteArray(Charsets.UTF_8), MAX_JSON_BYTES), Charsets.UTF_8)

    /**
     * Downloads an image and returns it decoded, at most [maxWidth] wide. Rejects non-HTTPS addresses, oversized
     * downloads, absurd dimensions and files that are not images.
     */
    suspend fun downloadImage(url: String, maxWidth: Int, trustedHosts: Set<String> = emptySet()): Bitmap {
        if (!ImageUrlPolicy.isAllowed(url, trustedHosts)) throw ProviderException(ProviderFailure.BAD_RESPONSE)
        val bytes = request("GET", url, mapOf("Accept" to "image/*"), null, ImageUrlPolicy.MAX_IMAGE_BYTES, followHttpsRedirects = true, trustedHosts = trustedHosts)
        return withContext(Dispatchers.Default) { decode(bytes, maxWidth) ?: throw ProviderException(ProviderFailure.BAD_RESPONSE) }
    }

    /** Downloads a picture's bytes as they are (for animated GIFs), with the same checks as [downloadImage]. */
    suspend fun downloadBytes(url: String, trustedHosts: Set<String> = emptySet()): ByteArray {
        if (!ImageUrlPolicy.isAllowed(url, trustedHosts)) throw ProviderException(ProviderFailure.BAD_RESPONSE)
        return request("GET", url, mapOf("Accept" to "image/*"), null, ImageUrlPolicy.MAX_IMAGE_BYTES, followHttpsRedirects = true, trustedHosts = trustedHosts)
    }

    /** True for GIF data ("GIF87a" or "GIF89a"). */
    fun isGif(bytes: ByteArray): Boolean =
        bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()

    internal fun decode(bytes: ByteArray, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth > ImageUrlPolicy.MAX_DIMENSION || bounds.outHeight > ImageUrlPolicy.MAX_DIMENSION) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBytes: Long,
        followHttpsRedirects: Boolean = false,
        trustedHosts: Set<String> = emptySet(),
    ): ByteArray = withContext(Dispatchers.IO) {
        var target = url
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!target.startsWith("https://", ignoreCase = true)) throw ProviderException(ProviderFailure.BAD_RESPONSE)
            val connection = URL(target).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                // Credentials only go to the host they were meant for.
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                connection.setRequestProperty("User-Agent", "SkyWing-Artwork/1.0")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                when {
                    code in 200..299 -> {
                        val declared = connection.contentLengthLong
                        if (declared > maxBytes) throw ProviderException(ProviderFailure.BAD_RESPONSE)
                        val out = ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                if (out.size() > maxBytes) throw ProviderException(ProviderFailure.BAD_RESPONSE)
                            }
                        }
                        return@withContext out.toByteArray()
                    }
                    code in 300..399 && followHttpsRedirects && redirects < 3 -> {
                        val next = connection.getHeaderField("Location") ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
                        val resolved = URL(URL(target), next).toString()
                        if (!ImageUrlPolicy.isAllowed(resolved, trustedHosts)) throw ProviderException(ProviderFailure.BAD_RESPONSE)
                        target = resolved
                        redirects++
                    }
                    code == 401 -> throw ProviderException(ProviderFailure.UNAUTHORIZED)
                    code == 403 -> throw ProviderException(ProviderFailure.FORBIDDEN)
                    code == 429 -> throw ProviderException(ProviderFailure.RATE_LIMITED)
                    code in 500..599 -> throw ProviderException(ProviderFailure.SERVER_ERROR)
                    else -> throw ProviderException(ProviderFailure.BAD_RESPONSE)
                }
            } catch (e: ProviderException) {
                throw e
            } catch (e: IOException) {
                throw ProviderException(ProviderFailure.NETWORK, e)
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("unreachable")
    }
}
