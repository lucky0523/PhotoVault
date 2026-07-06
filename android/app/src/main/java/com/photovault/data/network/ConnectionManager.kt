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
 * - The heartbeat interval ([HEARTBEAT_INTERVAL_MS]) uses a short per-request
 *   timeout, so a dead connection is detected quickly without keeping the
 *   radio busy.
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

    // Debug: seconds remaining until the next heartbeat probe fires. -1 means
    // no countdown is active (disconnected or app backgrounded).
    private val _heartbeatCountdown = MutableStateFlow(-1)
    val heartbeatCountdown: StateFlow<Int> = _heartbeatCountdown.asStateFlow()

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

    // Whether the loop should keep trying to (re)connect while disconnected.
    // Set when the user connects, cleared on an explicit disconnect()/logout,
    // so a dropped connection auto-recovers but an intentional disconnect
    // stays disconnected.
    @Volatile
    private var autoReconnectEnabled: Boolean = false

    init {
        android.util.Log.d("ConnMgrDebug", "init: registering heartbeat observer")
        // Drive the heartbeat off the app's foreground/background lifecycle
        // rather than a raw always-on timer, so it stops consuming battery
        // and network the moment the app leaves the foreground.
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                android.util.Log.d("ConnMgrDebug", "repeatOnLifecycle STARTED -> runHeartbeatLoop")
                runHeartbeatLoop()
            }
        }
    }

    /**
     * Attempt to connect to the server.
     * Tries LAN first (10s timeout), then WAN (15s timeout).
     */
    suspend fun connect(serverAddress: String): ConnectionState {
        // The user wants a connection; allow the loop to auto-recover if it
        // later drops.
        autoReconnectEnabled = true
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
        // Explicit disconnect (e.g. logout): stop auto-reconnect so we don't
        // immediately reconnect on the next loop tick.
        autoReconnectEnabled = false
        activeTestUrl = null
        activeConnectionType = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Runs while the app is in the foreground. On every tick it either:
     * - probes the live connection ([ConnectionState.Connected]) to detect a
     *   server/network drop, or
     * - attempts to (re)connect ([ConnectionState.Disconnected]) so the client
     *   recovers automatically once the server comes back (e.g. after a
     *   server restart) without any manual user action.
     *
     * While [ConnectionState.Connecting] a connect attempt is already in
     * flight, so the tick does nothing and simply waits.
     */
    private suspend fun runHeartbeatLoop() {
        try {
            // Check immediately on entering the foreground, then on a fixed
            // interval for as long as the app stays foregrounded.
            while (true) {
                android.util.Log.d("ConnMgrDebug", "heartbeat tick, state=${_connectionState.value}, url=$activeTestUrl")
                tickConnection()
                // Count down second-by-second so the debug UI can show the
                // time remaining until the next heartbeat probe. The length is
                // derived from HEARTBEAT_INTERVAL_MS, never hardcoded.
                var remaining = HEARTBEAT_INTERVAL_SECONDS
                while (remaining > 0) {
                    _heartbeatCountdown.value = remaining
                    delay(COUNTDOWN_TICK_MS)
                    remaining--
                }
                _heartbeatCountdown.value = 0
            }
        } finally {
            // App left the foreground; the loop is cancelled. Reset the
            // countdown so the UI doesn't show a stale value.
            _heartbeatCountdown.value = -1
        }
    }

    /**
     * One iteration of the connection loop: heartbeat when connected,
     * reconnect when disconnected.
     */
    private suspend fun tickConnection() {
        when (_connectionState.value) {
            is ConnectionState.Connected -> sendHeartbeatIfConnected()
            is ConnectionState.Disconnected -> attemptReconnect()
            is ConnectionState.Connecting -> {
                // A connect() is already running; nothing to do this tick.
                android.util.Log.d("ConnMgrDebug", "skip tick, already connecting")
            }
        }
    }

    /**
     * While disconnected, quietly retry the configured server so the client
     * recovers on its own once the server is reachable again.
     */
    private suspend fun attemptReconnect() {
        if (!autoReconnectEnabled) {
            android.util.Log.d("ConnMgrDebug", "skip reconnect, auto-reconnect disabled (explicit disconnect)")
            return
        }
        val serverAddress = credentialManager.getServerAddress()
        if (serverAddress.isNullOrEmpty()) {
            android.util.Log.d("ConnMgrDebug", "skip reconnect, no server address configured")
            return
        }
        android.util.Log.d("ConnMgrDebug", "attempting reconnect to $serverAddress")
        connect(serverAddress)
    }

    private suspend fun sendHeartbeatIfConnected() {
        val currentState = _connectionState.value
        if (currentState !is ConnectionState.Connected) {
            android.util.Log.d("ConnMgrDebug", "skip heartbeat, not connected: $currentState")
            return
        }

        val url = activeTestUrl
        if (url == null) {
            android.util.Log.d("ConnMgrDebug", "skip heartbeat, activeTestUrl is null")
            return
        }

        val isAlive = tryConnect(url, heartbeatClient)
        android.util.Log.d("ConnMgrDebug", "heartbeat result isAlive=$isAlive for url=$url")
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
        // Interval between heartbeat probes. Heartbeats are skipped entirely
        // while disconnected or backgrounded, so this only costs one tiny
        // request per interval while the app is open and connected.
        // The debug countdown in the UI is derived from this value, so
        // changing it here is reflected everywhere automatically.
        private const val HEARTBEAT_INTERVAL_MS = 5_000L

        // Granularity of the debug countdown ticks (once per second).
        private const val COUNTDOWN_TICK_MS = 1_000L

        // Whole-second countdown length derived from the interval above.
        private val HEARTBEAT_INTERVAL_SECONDS = (HEARTBEAT_INTERVAL_MS / 1_000L).toInt()
    }
}
