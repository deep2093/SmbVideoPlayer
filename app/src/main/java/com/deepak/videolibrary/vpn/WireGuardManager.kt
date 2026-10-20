package com.deepak.videolibrary.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.VpnService
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed interface VpnState {
    data object NoConfig : VpnState
    data object Disconnected : VpnState
    data object Connecting : VpnState

    /** [serverReachable] is null when unknown, true/false after probing the SMB server through the tunnel. */
    data class Connected(val serverReachable: Boolean?) : VpnState

    /** Android's one-time "Connection request" has not been accepted (or was revoked). */
    data object NeedsPermission : VpnState
    data class Error(val message: String) : VpnState
}

enum class AutoConnectOutcome {
    DISABLED, NO_CONFIG, SUPPRESSED_BY_USER, NO_SERVER, DIRECT, OFFLINE, ALREADY_UP, CONNECTED, NEEDS_PERMISSION, FAILED,
}

enum class DisconnectResult { DONE, DEFERRED_PLAYBACK, NOT_CONNECTED, FAILED }

sealed interface ImportResult {
    data class Success(val peerCount: Int) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/**
 * Wraps the official WireGuard [GoBackend] (userspace wireguard-go) for this app.
 *
 * - One tunnel, restricted to this app's own traffic by default (see [ConfigText.restrictToApp]).
 * - The configuration never leaves [WireGuardConfigStore]'s encrypted file except in memory while connecting.
 * - Nothing here logs configuration text, keys or the SMB password.
 * - The tunnel lives in this process: if Android kills the app, the tunnel goes with it.
 */
class WireGuardManager private constructor(context: Context) {
    private val app = context.applicationContext
    val store = WireGuardConfigStore(app)
    val prefs = VpnPreferences(app)
    private val reachability = SmbReachability(app)
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // Loads the native library, so only pay for it once a VPN is actually used.
    private val backend by lazy { GoBackend(app) }

    private val _state = MutableStateFlow<VpnState>(if (store.hasConfig()) VpnState.Disconnected else VpnState.NoConfig)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _playbackActive = MutableStateFlow(false)

    /** True while a player holds the tunnel open. The UI uses it to explain a deferred disconnect. */
    val playbackActive: StateFlow<Boolean> = _playbackActive.asStateFlow()

    private val holds = AtomicInteger(0)

    @Volatile private var pendingDisconnect = false

    /** The user pressed Disconnect: no automatic reconnect until they connect again or the app process restarts. */
    @Volatile private var manualOff = false

    @Volatile private var startedByAuto = false
    @Volatile private var failures = 0
    @Volatile private var lastAttemptAt = 0L

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            // UP is reported to connect() through its own return path. DOWN can also come from Android
            // revoking the VPN (another VPN app took over), so reflect it here.
            if (newState == Tunnel.State.DOWN && _state.value is VpnState.Connected) {
                _state.value = VpnState.Disconnected
            }
        }
    }

    // ---- Public API ----

    /** Intent for Android's VPN consent dialog, or null when consent was already granted. */
    fun consentIntent(): Intent? = VpnService.prepare(app)

    /** Called after the consent dialog returns RESULT_CANCELED. */
    fun consentDenied() {
        _state.value = VpnState.NeedsPermission
    }

    /** The tunnel is up or on its way up. */
    fun isActive(): Boolean = _state.value.let { it is VpnState.Connected || it is VpnState.Connecting }

    suspend fun connect(userInitiated: Boolean = true): VpnState {
        return mutex.withLock {
            if (userInitiated) {
                manualOff = false
                failures = 0
            } else if (manualOff || backoffRemainingMs() > 0) {
                return@withLock _state.value
            }
            startedByAuto = !userInitiated
            doConnect()
        }
    }

    suspend fun disconnect(force: Boolean = false, userInitiated: Boolean = true): DisconnectResult {
        return mutex.withLock { disconnectLocked(force, userInitiated) }
    }

    /** Must be called with [mutex] held. */
    private suspend fun disconnectLocked(force: Boolean, userInitiated: Boolean): DisconnectResult {
        if (userInitiated) manualOff = true
        val s = _state.value
        // An Error state may still have a live tunnel behind it (e.g. a failed disconnect), so ask the backend.
        val maybeUp = s is VpnState.Connected || s is VpnState.Connecting ||
            (s is VpnState.Error && backendIsUp())
        if (!maybeUp) {
            pendingDisconnect = false
            return DisconnectResult.NOT_CONNECTED
        }
        if (!force && holds.get() > 0) {
            pendingDisconnect = true
            return DisconnectResult.DEFERRED_PLAYBACK
        }
        pendingDisconnect = false
        return try {
            // Not cancellable: the blocking call must finish so our state matches the real tunnel.
            withContext(NonCancellable + Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            _state.value = idleState()
            DisconnectResult.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = VpnState.Error(describe(e))
            DisconnectResult.FAILED
        }
    }

    private fun backendIsUp(): Boolean = runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    /** Cancels a disconnect that was deferred until playback finished. */
    fun cancelPendingDisconnect() {
        pendingDisconnect = false
    }

    /**
     * Declares that a player is streaming and may depend on the tunnel. While any hold is open the
     * tunnel is never taken down automatically, and a manual disconnect needs `force`.
     * Call [PlaybackHold.release] exactly when playback ends (it is idempotent).
     */
    fun beginPlayback(): PlaybackHold {
        holds.incrementAndGet()
        _playbackActive.value = holds.get() > 0
        return PlaybackHold()
    }

    inner class PlaybackHold internal constructor() {
        private val released = AtomicBoolean(false)
        fun release() {
            if (!released.compareAndSet(false, true)) return
            val remaining = holds.decrementAndGet()
            _playbackActive.value = remaining > 0
            if (remaining == 0) {
                scope.launch {
                    if (pendingDisconnect) disconnect(force = false, userInitiated = false) else scheduleEvaluation(0)
                }
            }
        }
    }

    suspend fun importConfig(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val text = try {
            val bytes = app.contentResolver.openInputStream(uri)?.use { readLimited(it, ConfigText.MAX_IMPORT_BYTES) }
                ?: return@withContext ImportResult.Failure("Could not open that file.")
            String(bytes, Charsets.UTF_8)
        } catch (e: TooLargeException) {
            return@withContext ImportResult.Failure("That file is too large to be a WireGuard configuration.")
        } catch (e: Exception) {
            return@withContext ImportResult.Failure("Could not read that file.")
        }
        val config = try {
            parse(text, appOnly = false)
        } catch (e: BadConfigException) {
            return@withContext ImportResult.Failure("Not a valid WireGuard configuration (${e.section.name}: ${e.reason.name}).")
        } catch (e: Exception) {
            return@withContext ImportResult.Failure("Not a valid WireGuard configuration.")
        }
        if (config.peers.isEmpty()) return@withContext ImportResult.Failure("The configuration has no [Peer] section.")
        mutex.withLock {
            if (isActive() && holds.get() > 0) {
                return@withContext ImportResult.Failure("Stop playback before replacing the VPN configuration.")
            }
            if (disconnectLocked(force = true, userInitiated = false) == DisconnectResult.FAILED) {
                return@withContext ImportResult.Failure("Could not turn the current VPN off. Try again.")
            }
            try {
                store.save(text)
            } catch (e: Exception) {
                return@withContext ImportResult.Failure("Could not store the configuration securely.")
            }
            failures = 0
            manualOff = false
            _state.value = VpnState.Disconnected
        }
        ImportResult.Success(config.peers.size)
    }

    /** Disconnects and deletes the stored configuration and its Keystore key. False while playback is active. */
    suspend fun removeConfig(): Boolean {
        return mutex.withLock {
            if (isActive() && holds.get() > 0) return@withLock false
            if (disconnectLocked(force = true, userInitiated = false) == DisconnectResult.FAILED) return@withLock false
            store.clear()
            failures = 0
            _state.value = VpnState.NoConfig
            true
        }
    }

    // ---- Automatic connection ----

    /**
     * Brings the tunnel up only when needed: not when the server already answers directly, not when
     * offline, and not after the user turned the VPN off. [respectBackoff] is false for app launch,
     * which is a deliberate user action; network events keep the back-off so a flapping network
     * does not cause a reconnect loop.
     */
    suspend fun autoConnect(respectBackoff: Boolean): AutoConnectOutcome {
        if (!prefs.autoConnect) return AutoConnectOutcome.DISABLED
        if (!store.hasConfig()) return AutoConnectOutcome.NO_CONFIG
        if (manualOff) return AutoConnectOutcome.SUPPRESSED_BY_USER
        when (_state.value) {
            is VpnState.Connected, VpnState.Connecting -> return AutoConnectOutcome.ALREADY_UP
            VpnState.NeedsPermission -> {
                if (respectBackoff) return AutoConnectOutcome.NEEDS_PERMISSION
            }
            else -> Unit
        }
        val host = serverHost()
        if (host.isEmpty()) return AutoConnectOutcome.NO_SERVER
        if (!reachability.hasUnderlyingNetwork()) return AutoConnectOutcome.OFFLINE
        if (reachability.isDirectlyReachable(host)) return AutoConnectOutcome.DIRECT
        if (respectBackoff && backoffRemainingMs() > 0) return AutoConnectOutcome.FAILED
        return when (connectForAuto()) {
            is VpnState.Connected -> AutoConnectOutcome.CONNECTED
            VpnState.NeedsPermission -> AutoConnectOutcome.NEEDS_PERMISSION
            else -> AutoConnectOutcome.FAILED
        }
    }

    private suspend fun connectForAuto(): VpnState {
        return mutex.withLock {
            if (manualOff) return@withLock _state.value
            startedByAuto = true
            doConnect()
        }
    }

    // ---- Network monitoring (call from the Activity) ----

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var evaluationJob: Job? = null

    @Synchronized
    fun startMonitoring() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleEvaluation()
            override fun onLost(network: Network) = scheduleEvaluation()
        }
        // A default NetworkRequest excludes VPN networks, so only the physical networks are reported.
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        try {
            connectivity.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: RuntimeException) {
            // Too many callbacks registered or missing permission: monitoring is optional.
        }
    }

    @Synchronized
    fun stopMonitoring() {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        evaluationJob?.cancel()
        evaluationJob = null
    }

    @Synchronized
    private fun scheduleEvaluation(delayMs: Long = NETWORK_SETTLE_MS) {
        evaluationJob?.cancel()
        evaluationJob = scope.launch {
            delay(delayMs) // Wi-Fi/mobile hand-over produces bursts of events; act once, after they settle.
            evaluateNetwork()
        }
    }

    private suspend fun evaluateNetwork() {
        if (!prefs.autoConnect || !store.hasConfig() || manualOff) return
        val host = serverHost()
        if (host.isEmpty() || !reachability.hasUnderlyingNetwork()) return // Offline: wait, do not retry.
        when (_state.value) {
            is VpnState.Connected -> {
                if (holds.get() == 0 && startedByAuto && reachability.isDirectlyReachable(host)) {
                    // Back on the home network: the tunnel is no longer needed.
                    disconnect(force = false, userInitiated = false)
                } else if (!reachability.isReachableViaDefaultRoute(host)) {
                    delay(HEALTH_RECHECK_MS)
                    // At most one restart a minute, so an offline server cannot cause a restart loop.
                    val recentlyTried = System.currentTimeMillis() - lastAttemptAt < RESTART_MIN_INTERVAL_MS
                    if (!reachability.isReachableViaDefaultRoute(host) && !recentlyTried) restartTunnel()
                }
            }
            VpnState.Disconnected, is VpnState.Error -> autoConnect(respectBackoff = true)
            else -> Unit // Connecting, NeedsPermission, NoConfig
        }
    }

    private suspend fun restartTunnel() {
        // The stream behind a dead tunnel is already stalled, so restarting does not add an interruption.
        disconnect(force = true, userInitiated = false)
        connectForAuto()
    }

    // ---- Internals ----

    private suspend fun doConnect(): VpnState {
        val s = _state.value
        if (s is VpnState.Connected || s is VpnState.Connecting) return s
        if (!store.hasConfig()) return VpnState.NoConfig.also { _state.value = it }
        val text = store.load()
            ?: return VpnState.Error("The saved WireGuard configuration can't be read. Import it again.").also { _state.value = it }
        if (VpnService.prepare(app) != null) return VpnState.NeedsPermission.also { _state.value = it }

        _state.value = VpnState.Connecting
        lastAttemptAt = System.currentTimeMillis()
        try {
            val config = parse(text, appOnly = prefs.appOnly)
            pendingDisconnect = false
            withContext(NonCancellable + Dispatchers.IO) {
                backend.setState(tunnel, Tunnel.State.UP, config)
                // Recorded inside the non-cancellable block: if the caller is cancelled right after the
                // tunnel came up, our state must still say so, or the UI could no longer turn it off.
                failures = 0
                _state.value = VpnState.Connected(null)
            }
            val host = serverHost()
            if (host.isNotEmpty()) {
                val reachable = waitUntilReachable(host)
                // The state may have been changed by a revoke while we waited.
                if (_state.value is VpnState.Connected) _state.value = VpnState.Connected(reachable)
            }
        } catch (e: CancellationException) {
            // Only reachable before the tunnel came up (it is Connected otherwise), so nothing is running.
            if (_state.value == VpnState.Connecting) _state.value = VpnState.Disconnected
            throw e
        } catch (e: Throwable) {
            failures++
            _state.value = if (e is BackendException && e.reason == BackendException.Reason.VPN_NOT_AUTHORIZED) {
                VpnState.NeedsPermission
            } else {
                VpnState.Error(describe(e))
            }
        }
        return _state.value
    }

    private suspend fun waitUntilReachable(host: String): Boolean {
        val deadline = System.currentTimeMillis() + REACHABLE_WAIT_MS
        while (true) {
            if (reachability.isReachableViaDefaultRoute(host, timeoutMs = 2000)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(1000)
        }
    }

    private fun idleState(): VpnState = if (store.hasConfig()) VpnState.Disconnected else VpnState.NoConfig

    private fun backoffRemainingMs(): Long {
        val n = failures
        if (n <= 0) return 0
        val wait = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl minOf(n - 1, 5))
        return (lastAttemptAt + wait - System.currentTimeMillis()).coerceAtLeast(0)
    }

    /**
     * Reads the host from the app's existing SMB settings. Only the non-secret host field is read, so
     * no Keystore decryption is needed. Key names match SettingsStore ("smb_settings" / "host").
     */
    private fun serverHost(): String =
        SmbReachability.normalizeHost(app.getSharedPreferences("smb_settings", Context.MODE_PRIVATE).getString("host", "") ?: "")

    private fun parse(text: String, appOnly: Boolean): Config {
        val effective = if (appOnly) ConfigText.restrictToApp(text, app.packageName) else text
        return Config.parse(BufferedReader(StringReader(effective)))
    }

    private fun readLimited(stream: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw TooLargeException()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private class TooLargeException : Exception()

    /** User-facing text. Never includes configuration content, keys, endpoints or exception messages. */
    private fun describe(e: Throwable): String = when (e) {
        is BackendException -> when (e.reason) {
            BackendException.Reason.UNABLE_TO_START_VPN -> "Android could not start the VPN service. Try again."
            BackendException.Reason.TUN_CREATION_ERROR -> "Android refused to create the VPN interface. Another VPN may be active."
            BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> "WireGuard could not start the tunnel."
            BackendException.Reason.DNS_RESOLUTION_FAILURE -> "Could not resolve the WireGuard server name. Check your internet connection."
            BackendException.Reason.TUNNEL_MISSING_CONFIG -> "No WireGuard configuration is available."
            else -> "WireGuard reported an error (${e.reason.name})."
        }
        is BadConfigException -> "The saved WireGuard configuration is invalid (${e.section.name}: ${e.reason.name})."
        is UnsatisfiedLinkError -> "WireGuard is not supported on this device's processor."
        else -> "VPN connection failed (${e.javaClass.simpleName})."
    }

    companion object {
        private const val TUNNEL_NAME = "videolibrary"
        private const val NETWORK_SETTLE_MS = 3_000L
        private const val HEALTH_RECHECK_MS = 5_000L
        private const val REACHABLE_WAIT_MS = 8_000L
        private const val RESTART_MIN_INTERVAL_MS = 60_000L
        private const val BASE_BACKOFF_MS = 15_000L
        private const val MAX_BACKOFF_MS = 300_000L

        @Volatile private var instance: WireGuardManager? = null

        fun get(context: Context): WireGuardManager =
            instance ?: synchronized(this) {
                instance ?: WireGuardManager(context.applicationContext).also { instance = it }
            }
    }
}
