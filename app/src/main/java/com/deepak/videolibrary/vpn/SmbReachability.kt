package com.deepak.videolibrary.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP/445 reachability checks. These deliberately avoid SSID matching: what matters is whether
 * the server answers, not which Wi-Fi name we are on.
 */
class SmbReachability(context: Context) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Underlying (non-VPN) networks that currently exist. */
    fun underlyingNetworks(): List<Network> = cm.allNetworks.filter { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@filter false
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun hasUnderlyingNetwork(): Boolean = underlyingNetworks().isNotEmpty()

    /**
     * True when the server answers on a physical network. Each socket is bound to that network
     * itself, so the answer is independent of whether this app's VPN is currently up.
     */
    suspend fun isDirectlyReachable(host: String, timeoutMs: Int = 1500): Boolean = withContext(Dispatchers.IO) {
        val target = normalizeHost(host)
        if (target.isEmpty()) return@withContext false
        val networks = underlyingNetworks()
        if (networks.isEmpty()) return@withContext false
        coroutineScope {
            networks
                .map { network ->
                    async {
                        runCatching {
                            val address = network.getByName(target)
                            network.socketFactory.createSocket().use { socket ->
                                socket.connect(InetSocketAddress(address, SMB_PORT), timeoutMs)
                            }
                            true
                        }.getOrDefault(false)
                    }
                }.awaitAll()
                .any { it }
        }
    }

    suspend fun isDirectlyReachable(hosts: List<String>, timeoutMs: Int = 1500): Boolean =
        hosts.any { isDirectlyReachable(it, timeoutMs) }

    /**
     * True when the server answers on this app's default route. With the app-only tunnel up
     * this is the route through WireGuard; with it down it is the normal network.
     */
    suspend fun isReachableViaDefaultRoute(host: String, timeoutMs: Int = 2500): Boolean = withContext(Dispatchers.IO) {
        val target = normalizeHost(host)
        if (target.isEmpty()) return@withContext false
        runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(target, SMB_PORT), timeoutMs) }
            true
        }.getOrDefault(false)
    }

    suspend fun isReachableViaDefaultRoute(hosts: List<String>, timeoutMs: Int = 2500): Boolean =
        hosts.any { isReachableViaDefaultRoute(it, timeoutMs) }

    companion object {
        private const val SMB_PORT = 445

        /** Accepts `host`, `smb://host/share` or `host:445` as the Settings screen allows. */
        fun normalizeHost(raw: String): String = raw
            .trim()
            .removePrefix("smb://")
            .substringBefore('/')
            .substringBefore('\\')
            .substringBefore(':')
            .trim()
    }
}
