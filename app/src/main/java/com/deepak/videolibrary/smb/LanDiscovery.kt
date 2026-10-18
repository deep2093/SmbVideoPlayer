package com.deepak.videolibrary.smb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Best-effort LAN SMB host discovery. Does not authenticate or enumerate shares. */
object LanDiscovery {
    suspend fun hosts(): List<String> = withContext(Dispatchers.IO) {
        val prefixes = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { a ->
                val ip = a.hostAddress ?: return@mapNotNull null
                if (ip.startsWith("192.168.") || ip.startsWith("10.") || (ip.startsWith("172.") && (ip.split(".").getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true))) {
                    ip.substringBeforeLast('.', "")
                } else null
            }.distinct().take(2)
        val pool = Executors.newFixedThreadPool(24)
        try {
            val tasks = prefixes.flatMap { prefix -> (1..254).map { i ->
                Callable {
                    val ip = "$prefix.$i"
                    try { Socket().use { socket -> socket.connect(java.net.InetSocketAddress(ip, 445), 180); ip } }
                    catch (_: Exception) { null }
                }
            } }
            pool.invokeAll(tasks).mapNotNull { runCatching { it.get(1, TimeUnit.SECONDS) }.getOrNull() }
                .distinct().sorted()
        } finally { pool.shutdownNow() }
    }
}
