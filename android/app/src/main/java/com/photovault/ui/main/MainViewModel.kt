package com.photovault.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.local.CredentialManager
import com.photovault.data.network.ConnectionManager
import com.photovault.data.network.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val credentialManager: CredentialManager
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    init {
        // Attempt connection on initialization
        val serverAddress = credentialManager.getServerAddress()
        if (!serverAddress.isNullOrEmpty()) {
            viewModelScope.launch {
                connectionManager.connect(serverAddress)
            }
        }
    }

    fun logout() {
        credentialManager.clearTokens()
        connectionManager.disconnect()
    }

    fun retryConnection() {
        val serverAddress = credentialManager.getServerAddress()
        if (!serverAddress.isNullOrEmpty()) {
            viewModelScope.launch {
                connectionManager.connect(serverAddress)
            }
        }
    }
}
