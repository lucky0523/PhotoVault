package com.photovault.data.network

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.photovault.data.local.CredentialManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection state representing the current connection to the server.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val type: ConnectionType) : ConnectionState()
}

enum class ConnectionType {
    LAN,
    WAN
}

/**
 * ConnectionManager handles server connectivity with LAN-first priority.
 *
 * Strategy:
 * 1. Try LAN connection first (10s timeout)
 * 2. If LAN fails, try WAN connection (15s timeout)
 * 3. If both fail, report disconnected
 *
 * While connected, a lightweight heartbeat periodically re-probes the same
 * test endpoint (on the client/transport that last succeeded) to detect
 * disconnects (server restart, network change, etc.) and keep
 * [connectionState] accurate without the UI having to poll anything.
 *
 * Power considerations:
 * - The heartbeat only runs while the app process is in the foreground
 *   ([Lifecycle.State.STARTED]), driven by [ProcessLifecycleOwner]. It is
 *   automatically cancelled when the app is backgrounded/screen off and
 *   resumes (with an immediate check) when the app returns to foreground.
 * - The heartbeat interval is intentionally long (30s) and uses a short
 *   per-request timeout (5s), so a dead connection is detected quickly
 *   without keeping the radio busy.
 * - Heartbeats only happen while [ConnectionState.Connected]; there is
 *   nothing to probe while disconnected/connecting, so no extra traffic is
 *   generated in that state.
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val credentialManager: CredentialManager
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val lanClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val wanClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // Short-timeout client dedicated to heartbeats, so a stalled/dead
    // connection is detected in ~5s rather than tying up the (10-15s) client
    // timeouts used for the initial connect handshake.
    private val heartbeatClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    // Test URL and transport used by the last successful connect(), reused
    // by the heartbeat so it doesn't re-run the LAN-then-WAN probe sequence.
    @Volatile
    private var activeTestUrl: String? = null

    @Volatile
    private var activeConnectionType: ConnectionType? = null

    init {
        // Drive the heartbeat off the app's foreground/background lifecycle
        // rather than a raw always-on timer, so it stops consuming battery
        // and network the moment the app leaves the foreground.
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                runHeartbeatLoop()
            }
        }
    }

    /**
     * Attempt to connect to the server.
     * Tries LAN first (10s timeout), then WAN (15s timeout).
     */
    suspend fun connect(serverAddress: String): ConnectionState {
        _connectionState.value = ConnectionState.Connecting

        val baseUrl = normalizeServerUrl(serverAddress)
        val testUrl = "${baseUrl}api/v1/connection/test"

        // Try LAN first (10s timeout)
        val lanResult = tryConnect(testUrl, lanClient)
        if (lanResult) {
            activeTestUrl = testUrl
            activeConnectionType = ConnectionType.LAN
            val state = ConnectionState.Connected(ConnectionType.LAN)
            _connectionState.value = state
            return state
        }

        // LAN failed, try WAN (15s timeout)
        val wanResult = tryConnect(testUrl, wanClient)
        if (wanResult) {
            activeTestUrl = testUrl
            activeConnectionType = ConnectionType.WAN
            val state = ConnectionState.Connected(ConnectionType.WAN)
            _connectionState.value = state
            return state
        }

        // Both failed
        activeTestUrl = null
        activeConnectionType = null
        _connectionState.value = ConnectionState.Disconnected
        return ConnectionState.Disconnected
    }

    /**
     * Disconnect and reset state.
     */
    fun disconnect() {
        activeTestUrl = null
        activeConnectionType = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Runs while the app is in the foreground. Sleeps most of the time and
     * only issues a network request while [ConnectionState.Connected], so it
     * is effectively idle (no wakeups) whenever there's nothing to check.
     */
    private suspend fun runHeartbeatLoop() {
        // Check immediately on entering the foreground, then on a fixed
        // interval for as long as the app stays foregrounded.
        while (true) {
            sendHeartbeatIfConnected()
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private suspend fun sendHeartbeatIfConnected() {
        val currentState = _connectionState.value
        if (currentState !is ConnectionState.Connected) return

        val url = activeTestUrl ?: return

        val isAlive = tryConnect(url, heartbeatClient)
        if (!isAlive) {
            // Only downgrade if we're still in the state we just checked,
            // so we don't clobber a state change made concurrently by an
            // explicit connect()/disconnect() call.
            if (_connectionState.value == currentState) {
                activeTestUrl = null
                activeConnectionType = null
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    private suspend fun tryConnect(url: String, client: OkHttpClient): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun normalizeServerUrl(address: String): String {
        var url = address.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) {
            url = "$url/"
        }
        return url
    }

    companion object {
        // 30s balances timely disconnect detection against battery/network
        // usage; heartbeats are skipped entirely while disconnected or
        // backgrounded, so this only costs one tiny request every 30s while
        // the app is open and connected.
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
