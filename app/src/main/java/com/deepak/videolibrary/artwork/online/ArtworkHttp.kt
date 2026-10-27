package com.deepak.videolibrary.artwork.online

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.deepak.videolibrary.metadata.GraphQlFailureClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal, bounded HTTPS client for provider calls and image downloads. HTTPS only, short timeouts, a hard cap on
 * bytes read, no cookies. API requests only follow same-host HTTPS redirects that keep the method (307/308), so
 * credentials never leave the host they were meant for. Request headers (which hold API keys) are never logged.
 *
 * Connections are kept alive between requests: a response body is always read to the end and closed, and the
 * connection is only torn down after an error. Re-doing DNS, TCP and TLS for every call to overseas providers was
 * the main cost of artwork and scene lookups, far more than the bytes transferred.
 */
internal object ArtworkHttp {
    private const val MAX_JSON_BYTES = 4_000_000L
    private const val MAX_ERROR_BYTES = 256_000L
    private const val MAX_REDIRECTS = 3

    suspend fun getJson(url: String, headers: Map<String, String>): String =
        String(request("GET", url, headers, null, MAX_JSON_BYTES, api = true), Charsets.UTF_8)

    suspend fun postJson(url: String, headers: Map<String, String>, body: String): String =
        String(request("POST", url, headers, body.toByteArray(Charsets.UTF_8), MAX_JSON_BYTES, api = true), Charsets.UTF_8)

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

    /**
     * Turns a non-2xx API reply into a failure. GraphQL servers (stash-box runs on gqlgen) answer an invalid query
     * with HTTP 4xx and a JSON `errors` body; that body is what tells a schema mismatch apart from a bad key, so it
     * is read instead of being discarded.
     */
    internal fun failureFor(code: Int, body: ByteArray?, api: Boolean): ProviderFailure = when {
        code == 401 -> ProviderFailure.UNAUTHORIZED
        code == 403 -> ProviderFailure.FORBIDDEN
        code == 429 -> ProviderFailure.RATE_LIMITED
        code in 500..599 -> ProviderFailure.SERVER_ERROR
        !api -> ProviderFailure.BAD_RESPONSE
        else -> {
            val text = body?.let { String(it, Charsets.UTF_8) }.orEmpty()
            val root = runCatching { JSONObject(text) }.getOrNull()
            val errors = root?.optJSONArray("errors")
            when {
                errors != null && errors.length() > 0 -> GraphQlFailureClassifier.classify(errors.toString())
                root == null && text.isNotBlank() -> ProviderFailure.NON_JSON
                else -> ProviderFailure.BAD_RESPONSE
            }
        }
    }

    private suspend fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = it.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) throw ProviderException(ProviderFailure.BAD_RESPONSE)
            }
        }
        return out.toByteArray()
    }

    private suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBytes: Long,
        followHttpsRedirects: Boolean = false,
        trustedHosts: Set<String> = emptySet(),
        api: Boolean = false,
    ): ByteArray = withContext(Dispatchers.IO) {
        var target = url
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!target.startsWith("https://", ignoreCase = true)) throw ProviderException(ProviderFailure.BAD_RESPONSE)
            val connection = URL(target).openConnection() as HttpURLConnection
            // Only a fully read and closed body lets the platform put the connection back in its pool.
            var reusable = false
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
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    if (connection.contentLengthLong > maxBytes) throw ProviderException(ProviderFailure.BAD_RESPONSE)
                    val bytes = readBounded(connection.inputStream, maxBytes)
                    reusable = true
                    return@withContext bytes
                }
                if (code in 300..399 && redirects < MAX_REDIRECTS) {
                    val next = connection.getHeaderField("Location") ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
                    val current = URL(target)
                    val resolved = URL(current, next)
                    val allowed = if (followHttpsRedirects) {
                        ImageUrlPolicy.isAllowed(resolved.toString(), trustedHosts)
                    } else {
                        // API calls: same host only (the request carries the API key), and only codes that keep
                        // the method and body, so a POST is never silently turned into a GET.
                        (code == 307 || code == 308) && resolved.protocol.equals("https", ignoreCase = true) &&
                            resolved.host.equals(current.host, ignoreCase = true) && resolved.userInfo == null
                    }
                    if (!allowed) throw ProviderException(ProviderFailure.BAD_RESPONSE)
                    target = resolved.toString()
                    redirects++
                    continue
                }
                val errorBody = try {
                    connection.errorStream?.let { readBounded(it, MAX_ERROR_BYTES) }
                } catch (e: IOException) {
                    null
                }
                reusable = errorBody != null
                throw ProviderException(failureFor(code, errorBody, api))
            } catch (e: ProviderException) {
                throw e
            } catch (e: IOException) {
                throw ProviderException(ProviderFailure.NETWORK, e)
            } finally {
                if (!reusable) connection.disconnect()
            }
        }
        throw IllegalStateException("unreachable")
    }
}
