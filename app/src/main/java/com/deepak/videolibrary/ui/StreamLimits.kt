package com.deepak.videolibrary.ui

/** The whole stream when it is at most [limit] bytes; null (without reading further) when it is larger. */
internal fun readAtMost(input: java.io.InputStream, limit: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size().toLong() + n > limit) return null
        out.write(buffer, 0, n)
    }
}
