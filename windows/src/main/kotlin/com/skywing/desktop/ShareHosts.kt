package com.skywing.desktop

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Several addresses for one network drive, for routers that move the drive between addresses (for example
 * 192.168.71.250 and 192.168.71.249), the same as Other addresses on Android.
 *
 * The library always keeps paths under the main address, so covers, folders and history stay attached to it.
 * When the main address does not answer on the SMB port, the other addresses are tried and the one that answered
 * is used to reach the files ([reachable]); paths read from it are turned back into main-address paths ([canonical]).
 */
internal object ShareHosts {
    private const val SMB_PORT = 445
    private const val PROBE_TIMEOUT_MS = 2500

    /** Main host (lower case) -> its other addresses. */
    private val others = ConcurrentHashMap<String, List<String>>()

    /** Main host (lower case) -> the address that answered last, when it is not the main one. */
    private val answered = ConcurrentHashMap<String, String>()

    /** Main host (lower case) -> the main host as it was typed, used when turning paths back. */
    private val spelling = ConcurrentHashMap<String, String>()

    /** "192.168.71.249, \\192.168.71.248\share" (commas, semicolons, spaces or new lines) -> clean, distinct hosts. */
    fun parse(text: String): List<String> = text
        .split(',', ';', ' ', '\n', '\t')
        .map { hostOf(it) ?: it.trim().removePrefix("smb://").substringBefore('/').substringBefore('\\') }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

    /** The host of "\\host\share\..." or null when [path] is not a network path. */
    fun hostOf(path: String): String? {
        val clean = path.trim()
        if (!clean.startsWith("\\\\")) return null
        return clean.removePrefix("\\\\").substringBefore('\\').takeIf { it.isNotBlank() }
    }

    /** [path] with its host replaced by [host]. */
    fun withHost(path: String, host: String): String {
        val current = hostOf(path) ?: return path
        return "\\\\" + host + path.trim().removePrefix("\\\\").substring(current.length)
    }

    /** The order to try: the address that answered last, then the main one, then the others. */
    fun order(main: String, others: List<String>, last: String?): List<String> {
        val configured = (listOf(main) + others).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val remembered = configured.firstOrNull { it.equals(last, ignoreCase = true) }
        return (listOfNotNull(remembered) + configured).distinctBy { it.lowercase() }
    }

    /** Records the other addresses of the drive at [mainPath] (a host or "\\host\share"). */
    fun register(mainPath: String, otherAddresses: String) {
        val typed = hostOf(mainPath) ?: mainPath.trim()
        val main = typed.lowercase()
        if (main.isEmpty()) return
        spelling[main] = typed
        val list = parse(otherAddresses).filterNot { it.equals(main, ignoreCase = true) }
        if (list.isEmpty()) others.remove(main) else others[main] = list
    }

    /** The address that answered last for [mainHost]. */
    fun lastAnswered(mainHost: String): String? = answered[mainHost.lowercase()]

    fun remember(mainHost: String, address: String) {
        if (address.equals(mainHost, ignoreCase = true)) answered.remove(mainHost.lowercase())
        else answered[mainHost.lowercase()] = address
    }

    /** Where to read [path] now: under the address that answered when the main one did not. Never blocks. */
    fun reachable(path: String): String {
        val host = hostOf(path) ?: return path
        val active = answered[host.lowercase()] ?: return path
        return withHost(path, active)
    }

    /** [path] as the library keeps it: under the main address, even when it was read through another one. */
    fun canonical(path: String): String {
        val host = hostOf(path) ?: return path
        val main = answered.entries.firstOrNull { it.value.equals(host, ignoreCase = true) }?.key
            ?: others.entries.firstOrNull { (_, list) -> list.any { it.equals(host, ignoreCase = true) } }?.key
            ?: return path
        return withHost(path, spelling[main] ?: main)
    }

    /**
     * Finds the address of [mainHost] that answers now (the remembered one first, then the main one, then the others)
     * and remembers it. Returns null when none answers. Blocking: call it off the UI thread.
     */
    fun resolve(mainHost: String): String? {
        val candidates = order(mainHost, others[mainHost.lowercase()].orEmpty(), lastAnswered(mainHost))
        val found = candidates.firstOrNull(::answers) ?: return null
        remember(mainHost, found)
        return found
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
