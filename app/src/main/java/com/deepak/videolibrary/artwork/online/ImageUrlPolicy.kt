package com.deepak.videolibrary.artwork.online

import java.net.URI

/** Which image addresses the app is willing to download. HTTPS only, and never local or private addresses. */
object ImageUrlPolicy {
    const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    const val MAX_DIMENSION = 8000

    /** Only checks the form: an https:// address. Used to choose among the addresses a provider returns. */
    fun looksUsable(url: String?): Boolean =
        !url.isNullOrBlank() && url != "null" && url.length <= 2048 && url.trim().startsWith("https://", ignoreCase = true)

    /**
     * Whether the app may download [url]. [trustedHosts] are the hosts of the user's own custom API addresses: images
     * served by a self-hosted service on the local network are allowed from those hosts only.
     */
    fun isAllowed(url: String?, trustedHosts: Set<String> = emptySet()): Boolean {
        if (url.isNullOrBlank() || url.length > 2048) return false
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return false
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        if (host in trustedHosts) return true
        if (uri.port != -1 && uri.port != 443) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan")) return false
        // Literal IP addresses (v4 or v6) are refused; providers serve images from named hosts.
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) return false
        return host.contains('.')
    }
}
