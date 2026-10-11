package com.huoyi.photovault.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huoyi.photovault.data.local.CredentialManager
import com.huoyi.photovault.data.network.ConnectionManager
import com.huoyi.photovault.data.network.ConnectionState
import com.huoyi.photovault.data.network.endpoint.EndpointConfigSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val credentialManager: CredentialManager,
    private val endpointConfig: EndpointConfigSource
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    // Debug: seconds until the next heartbeat probe fires.
    val heartbeatCountdown: StateFlow<Int> = connectionManager.heartbeatCountdown

    init {
        // Attempt connection on initialization (public or LAN address configured)
        if (endpointConfig.current().hasAnyAddress) {
            viewModelScope.launch {
                connectionManager.connect()
            }
        }
    }

    fun logout() {
        credentialManager.clearTokens()
        connectionManager.disconnect()
    }

    fun retryConnection() {
        if (endpointConfig.current().hasAnyAddress) {
            viewModelScope.launch {
                connectionManager.connect()
            }
        }
    }
}
