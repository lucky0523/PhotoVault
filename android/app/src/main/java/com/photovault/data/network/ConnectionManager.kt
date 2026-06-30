package com.photovault.data.network

import com.photovault.data.local.CredentialManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            val state = ConnectionState.Connected(ConnectionType.LAN)
            _connectionState.value = state
            return state
        }

        // LAN failed, try WAN (15s timeout)
        val wanResult = tryConnect(testUrl, wanClient)
        if (wanResult) {
            val state = ConnectionState.Connected(ConnectionType.WAN)
            _connectionState.value = state
            return state
        }

        // Both failed
        _connectionState.value = ConnectionState.Disconnected
        return ConnectionState.Disconnected
    }

    /**
     * Disconnect and reset state.
     */
    fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
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
}
