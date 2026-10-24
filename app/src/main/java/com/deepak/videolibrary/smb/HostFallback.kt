package com.deepak.videolibrary.smb

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Several addresses for one server, for routers that move the drive between addresses (for example
 * 192.168.71.250 and 192.168.71.249). The address that answered last is tried first next time.
 */
object HostFallback {
    private const val SMB_PORT = 445
    private const val PROBE_TIMEOUT_MS = 2500

    /** Main address -> the address that answered last (for this run of the app). */
    private val answered = ConcurrentHashMap<String, String>()

    /** "192.168.71.249, 192.168.71.248" (commas, semicolons, spaces or new lines) -> clean, distinct addresses. */
    fun parse(text: String): List<String> = text
        .split(',', ';', ' ', '\n', '\t')
        .map { it.trim().removePrefix("smb://").substringBefore('/').substringBefore('\\') }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

    /** The order to try: the address that answered last, then the main one, then the others. */
    fun order(main: String, others: List<String>, last: String?): List<String> {
        val configured = (listOf(main) + others).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val remembered = configured.firstOrNull { it.equals(last, ignoreCase = true) }
        return (listOfNotNull(remembered) + configured).distinctBy { it.lowercase() }
    }

    fun lastAnswered(main: String): String? = answered[main.trim().lowercase()]

    fun remember(main: String, address: String) {
        answered[main.trim().lowercase()] = address
    }

    /** True when something accepts a connection on the SMB port of [address] within 2.5 seconds. Blocking. */
    fun answers(address: String): Boolean = try {
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "").toIntOrNull() ?: SMB_PORT
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }
}
