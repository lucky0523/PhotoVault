package com.huoyi.photovault.data.network.endpoint

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default-network observer used by [EndpointResolver] (design §5.1).
 * [current] returns [NetworkClass.NONE] until [start] has been called.
 */
interface NetworkMonitor {
    fun current(): NetworkClass

    /** Seeds the initial state (without calling [onChange]) and starts observing. */
    fun start(onChange: () -> Unit)
}

/**
 * Classification rules, in order: no network → NONE; VPN → OTHER (LAN routing is
 * unpredictable); Wi-Fi / Ethernet → LOCAL_CAPABLE; anything else → OTHER.
 * [transports] holds `NetworkCapabilities.TRANSPORT_*` values.
 */
internal fun classify(hasNetwork: Boolean, transports: Set<Int>): NetworkClass = when {
    !hasNetwork -> NetworkClass.NONE
    NetworkCapabilities.TRANSPORT_VPN in transports -> NetworkClass.OTHER
    NetworkCapabilities.TRANSPORT_WIFI in transports ||
        NetworkCapabilities.TRANSPORT_ETHERNET in transports -> NetworkClass.LOCAL_CAPABLE
    else -> NetworkClass.OTHER
}

/**
 * Holds the last seen [NetworkKey]. [offer] returns true only when the key actually
 * changed, so the first `onAvailable` after registration and signal-strength-only
 * capability updates are no-ops.
 */
class NetworkKeyTracker {
    private val lock = Any()
    private var last: NetworkKey = NetworkKey.NONE

    val current: NetworkKey
        get() = synchronized(lock) { last }

    /** Sets the initial key without reporting a change. */
    fun seed(key: NetworkKey) {
        synchronized(lock) { last = key }
    }

    /** Records [key]; returns true if it differs from the previous one. */
    fun offer(key: NetworkKey): Boolean = synchronized(lock) {
        if (key == last) {
            false
        } else {
            last = key
            true
        }
    }
}

/** [NetworkMonitor] backed by `ConnectivityManager.registerDefaultNetworkCallback`. */
@Singleton
class ConnectivityNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkMonitor {

    private val tracker = NetworkKeyTracker()

    @Volatile
    private var onChange: (() -> Unit)? = null

    @Volatile
    private var started = false

    /** Set when callback registration failed: [current] then polls the active network. */
    @Volatile
    private var degraded = false

    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(ConnectivityManager::class.java)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = try {
                connectivityManager?.getNetworkCapabilities(network)
            } catch (e: Exception) {
                null
            }
            report(keyFor(network, caps))
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            report(keyFor(network, networkCapabilities))
        }

        override fun onLost(network: Network) {
            // Default-network callback: a replacement default network arrives via onAvailable.
            report(NetworkKey.NONE)
        }
    }

    override fun current(): NetworkClass {
        if (degraded) {
            report(queryActiveKey())
        }
        return tracker.current.networkClass
    }

    override fun start(onChange: () -> Unit) {
        synchronized(this) {
            if (started) return
            started = true
        }
        this.onChange = onChange
        val initial = try {
            queryActiveKey()
        } catch (e: Exception) {
            Log.w(TAG, "initial network query failed: ${e.javaClass.simpleName}")
            NetworkKey.NONE
        }
        tracker.seed(initial)
        try {
            val cm = connectivityManager ?: throw IllegalStateException("ConnectivityManager unavailable")
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.e(TAG, "registerDefaultNetworkCallback failed, polling instead", e)
            degraded = true
        }
    }

    private fun report(key: NetworkKey) {
        if (tracker.offer(key)) {
            onChange?.invoke()
        }
    }

    private fun queryActiveKey(): NetworkKey {
        val cm = connectivityManager ?: return NetworkKey.NONE
        val network = cm.activeNetwork ?: return NetworkKey.NONE
        return keyFor(network, cm.getNetworkCapabilities(network))
    }

    private fun keyFor(network: Network, caps: NetworkCapabilities?): NetworkKey {
        val transports = if (caps == null) {
            emptySet()
        } else {
            KNOWN_TRANSPORTS.filterTo(HashSet()) { caps.hasTransport(it) }
        }
        // A network without readable capabilities is still a network: classified OTHER.
        return NetworkKey(network.networkHandle, classify(hasNetwork = true, transports = transports))
    }

    companion object {
        private const val TAG = "PhotoVaultEndpoint"

        private val KNOWN_TRANSPORTS = listOf(
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN
        )
    }
}
