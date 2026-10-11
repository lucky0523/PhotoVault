package com.huoyi.photovault.data.network

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.repeatOnLifecycle
import com.huoyi.photovault.data.network.endpoint.EndpointConfigSource
import com.huoyi.photovault.data.network.endpoint.EndpointResolver
import com.huoyi.photovault.data.network.endpoint.Route
import com.huoyi.photovault.data.network.endpoint.RouteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
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
 * Heartbeat client derived from the shared client: keeps the routing layer (so
 * heartbeats get LAN→public fallback) but drops the BODY logging interceptor,
 * and uses 5 s connect/read timeouts (design §5.5).
 */
@VisibleForTesting
internal fun buildHeartbeatClient(shared: OkHttpClient): OkHttpClient = shared.newBuilder()
    .apply { interceptors().removeAll { it is HttpLoggingInterceptor } }
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build()

/**
 * ConnectionManager reports server connectivity for the connection indicator.
 *
 * Every probe is one GET connection/test on the ambient route session through
 * the routed heartbeat client; which address (LAN or public) is used is decided
 * by [EndpointResolver], and [ConnectionState.Connected.type] mirrors
 * [EndpointResolver.displayRoute] (updated immediately when the route changes).
 *
 * While connected, a lightweight heartbeat periodically re-probes the test
 * endpoint to detect disconnects (server restart, network change, etc.) and keep
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
    private val resolver: EndpointResolver,
    sharedClient: OkHttpClient,
    private val config: EndpointConfigSource
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Debug: seconds remaining until the next heartbeat probe fires. -1 means
    // no countdown is active (disconnected or app backgrounded).
    private val _heartbeatCountdown = MutableStateFlow(-1)
    val heartbeatCountdown: StateFlow<Int> = _heartbeatCountdown.asStateFlow()

    // Short-timeout routed client used for connect, reconnect and heartbeats, so a
    // stalled/dead connection is detected in ~5s.
    private val heartbeatClient: OkHttpClient by lazy { buildHeartbeatClient(sharedClient) }

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
        // Keep the indicator's LAN/WAN label in sync with the route as soon as it
        // changes (network change, LAN marked unavailable…), not on the next heartbeat.
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            resolver.displayRoute.collect {
                val state = _connectionState.value
                if (state is ConnectionState.Connected) {
                    _connectionState.compareAndSet(state, ConnectionState.Connected(currentConnectionType()))
                }
            }
        }
    }

    /** Indicator type for the route currently shown by the resolver. */
    private fun currentConnectionType(): ConnectionType =
        if (resolver.displayRoute.value == Route.LAN) ConnectionType.LAN else ConnectionType.WAN

    /**
     * User-initiated connect (first login / manual retry).
     *
     * This is the only path that surfaces [ConnectionState.Connecting], because
     * it represents an attempt the user is actively waiting on. One routed
     * probe with the heartbeat timeouts.
     */
    suspend fun connect(): ConnectionState {
        // The user wants a connection; allow the loop to auto-recover if it
        // later drops.
        autoReconnectEnabled = true
        _connectionState.value = ConnectionState.Connecting

        val state = if (tryConnect()) {
            ConnectionState.Connected(currentConnectionType())
        } else {
            ConnectionState.Disconnected
        }
        _connectionState.value = state
        return state
    }

    /**
     * Disconnect and reset state.
     */
    fun disconnect() {
        // Explicit disconnect (e.g. logout): stop auto-reconnect so we don't
        // immediately reconnect on the next loop tick.
        autoReconnectEnabled = false
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
                android.util.Log.d("ConnMgrDebug", "heartbeat tick, state=${_connectionState.value}")
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
     *
     * Unlike [connect], this is a *silent* probe: the state stays
     * [ConnectionState.Disconnected] for the whole attempt and only flips to
     * [ConnectionState.Connected] once the server actually answers. This avoids
     * showing "连接中" for a background retry the user isn't waiting on (which,
     * given the retry cadence, would otherwise keep the pill stuck on
     * "connecting" while the server is down). It also uses the short-timeout
     * [heartbeatClient] so an unreachable server fails fast.
     */
    private suspend fun attemptReconnect() {
        if (!autoReconnectEnabled) {
            android.util.Log.d("ConnMgrDebug", "skip reconnect, auto-reconnect disabled (explicit disconnect)")
            return
        }
        if (!config.current().hasAnyAddress) {
            android.util.Log.d("ConnMgrDebug", "skip reconnect, no server address configured")
            return
        }
        android.util.Log.d("ConnMgrDebug", "silently probing reconnect")
        if (!tryConnect()) {
            android.util.Log.d("ConnMgrDebug", "reconnect probe failed, staying disconnected")
            return
        }
        // Only promote to Connected if we're still Disconnected, so we don't
        // clobber a concurrent explicit connect()/disconnect().
        if (_connectionState.value is ConnectionState.Disconnected) {
            val type = currentConnectionType()
            _connectionState.value = ConnectionState.Connected(type)
            android.util.Log.d("ConnMgrDebug", "reconnect probe succeeded via $type")
        }
    }

    private suspend fun sendHeartbeatIfConnected() {
        val currentState = _connectionState.value
        if (currentState !is ConnectionState.Connected) {
            android.util.Log.d("ConnMgrDebug", "skip heartbeat, not connected: $currentState")
            return
        }

        val isAlive = tryConnect()
        android.util.Log.d("ConnMgrDebug", "heartbeat result isAlive=$isAlive")
        if (!isAlive) {
            // Only downgrade if we're still in the state we just checked,
            // so we don't clobber a state change made concurrently by an
            // explicit connect()/disconnect() call.
            if (_connectionState.value == currentState) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    /** One GET connection/test on the ambient session through the routed heartbeat client. */
    private suspend fun tryConnect(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(TEST_URL)
                    .tag(RouteSession::class.java, resolver.ambient)
                    .get()
                    .build()

                heartbeatClient.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }
    }

    companion object {
        // Placeholder host; the routing interceptor rewrites it to the LAN/public address.
        private const val TEST_URL = "http://photovault.invalid/api/v1/connection/test"

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
