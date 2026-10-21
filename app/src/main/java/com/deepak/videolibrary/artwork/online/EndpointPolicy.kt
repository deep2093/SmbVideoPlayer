package com.deepak.videolibrary.artwork.online

import java.net.URI

/** Validation of a user-supplied API address: HTTPS, a real host, no embedded credentials. */
object EndpointPolicy {
    fun isAllowed(url: String?): Boolean {
        if (url.isNullOrBlank() || url.length > 512) return false
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return false
        }
        return "https".equals(uri.scheme, ignoreCase = true) && uri.userInfo == null && !uri.host.isNullOrBlank() && uri.fragment == null
    }

    /** The address without a trailing slash, or null when it is not acceptable. */
    fun normalize(url: String?): String? = url?.trim()?.trimEnd('/')?.takeIf { isAllowed(it) }
}
