package com.deepak.videolibrary.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Discover reachable SMB endpoints on the active Wi-Fi/LAN, without requesting credentials.
 * SMB has no universally reliable broadcast discovery service; a TCP/445 probe is a fallback.
 */
object LanDiscovery {
    data class Result(val hosts: List<String>, val description: String)

    suspend fun find(context: Context, knownHost: String = ""): Result = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { caps ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        } ?: cm.activeNetwork
        val link = network?.let { cm.getLinkProperties(it) }
        val capabilities = network?.let { cm.getNetworkCapabilities(it) }
        val onLocalNetwork = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

        // Use Android's active network rather than the first interface (often VPN/tethering).
        val addresses = link?.linkAddresses.orEmpty().filter { it.address is Inet4Address }
        val candidates = LinkedHashSet<String>()
        val gateways = link
            ?.routes
            .orEmpty()
            .filter { it.isDefaultRoute }
            .mapNotNull { (it.gateway as? Inet4Address)?.hostAddress }
        candidates.addAll(gateways)
        val trimmedKnown = knownHost
            .trim()
            .removePrefix("smb://")
            .substringBefore('/')
            .substringBefore(':')
        if (trimmedKnown.isNotEmpty()) candidates.add(trimmedKnown)

        // /24 sweep is bounded intentionally. Larger subnets would be slow and battery intensive.
        val prefixes = LinkedHashSet<String>()
        for (address in addresses) {
            val ip = address.address.hostAddress ?: continue
            if (isPrivate(ip)) prefixes.add(ip.substringBeforeLast('.'))
        }
        // Some devices expose only a VPN as active: also take Wi-Fi DHCP address and gateway.
        @Suppress("DEPRECATION")
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        @Suppress("DEPRECATION")
        val dhcp = wifi?.dhcpInfo
        if (dhcp != null) {
            val ip = intToIpv4(dhcp.ipAddress)
            val gateway = intToIpv4(dhcp.gateway)
            if (isPrivate(ip)) prefixes.add(ip.substringBeforeLast('.'))
            if (dhcp.gateway != 0) candidates.add(gateway)
        }
        // The saved host can be a router or NAS on a different subnet; always probe it.
        for (prefix in prefixes.take(2)) for (last in 1..254) candidates.add("$prefix.$last")

        if (candidates.isEmpty()) {
            return@withContext Result(
                emptyList(),
                "No LAN IPv4 address found. Connect to Wi-Fi and make sure local network access is allowed.",
            )
        }

        val executor = Executors.newFixedThreadPool(48)
        val found = LinkedHashSet<String>()
        try {
            val tasks = candidates.map { host ->
                Callable {
                    try {
                        // Network-bound sockets can fail on some Samsung/Android builds even
                        // when the regular LAN route works. Probe both routes before rejecting.
                        var reachable = false
                        if (network != null) {
                            reachable = runCatching {
                                network.socketFactory.createSocket().use { sock ->
                                    sock.connect(InetSocketAddress(host, 445), 650)
                                }
                            }.isSuccess
                        }
                        if (!reachable) {
                            reachable = runCatching {
                                Socket().use { sock -> sock.connect(InetSocketAddress(host, 445), 650) }
                            }.isSuccess
                        }
                        if (reachable) host else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val futures = executor.invokeAll(tasks, 12, TimeUnit.SECONDS)
            futures.forEach { future ->
                if (!future.isCancelled) runCatching { future.get() }.getOrNull()?.let(found::add)
            }
        } finally {
            executor.shutdownNow()
        }

        val explanation = if (found.isEmpty()) {
            "No SMB server answered on TCP port 445. Probed ${candidates.size} addresses across ${prefixes.size} local subnet(s). " +
                "If manual SMB access works, check the saved host, Wi-Fi client isolation, VPN and router SMB service. " +
                if (!onLocalNetwork) "; Android does not report an active Wi-Fi/Ethernet network. Disable VPN or switch to Wi-Fi and retry." else ". Check router SMB sharing and guest-network isolation."
        } else {
            "Found ${found.size} SMB server(s). Select one, then enter the share name and credentials."
        }
        Result(found.sorted(), explanation)
    }

    private fun isPrivate(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }

    private fun intToIpv4(value: Int) = (0..3).joinToString(".") { ((value ushr (it * 8)) and 255).toString() }
}
