package com.photovault.ui.main.tabs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.local.CredentialManager
import com.photovault.data.local.SettingsPreferences
import com.photovault.data.local.entity.BackupFolder
import com.photovault.data.network.ConnectionManager
import com.photovault.data.network.ConnectionState
import com.photovault.data.repository.BackupFolderRepository
import com.photovault.service.BackgroundScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings Tab.
 * Manages backup condition settings, storage strategy management,
 * account information display, and triggers WorkManager rescheduling
 * when the scan interval changes.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsPreferences: SettingsPreferences,
    private val backupFolderRepository: BackupFolderRepository,
    private val credentialManager: CredentialManager,
    private val connectionManager: ConnectionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val autoBackupEnabled: StateFlow<Boolean> = settingsPreferences.autoBackupEnabled
    val wifiOnly: StateFlow<Boolean> = settingsPreferences.wifiOnly
    val minBatteryLevel: StateFlow<Int> = settingsPreferences.minBatteryLevel
    val scanIntervalMinutes: StateFlow<Int> = settingsPreferences.scanIntervalMinutes

    // Storage strategy management
    val backupFolders: StateFlow<List<BackupFolder>> = backupFolderRepository.getAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Account information
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _serverAddress = MutableStateFlow("")
    val serverAddress: StateFlow<String> = _serverAddress.asStateFlow()

    init {
        loadAccountInfo()
    }

    private fun loadAccountInfo() {
        val credentials = credentialManager.loadCredentials()
        _username.value = credentials.username
        _serverAddress.value = credentials.serverAddress
    }

    private val _showPolicySheet = MutableStateFlow(false)
    val showPolicySheet: StateFlow<Boolean> = _showPolicySheet.asStateFlow()

    private val _selectedFolder = MutableStateFlow<BackupFolder?>(null)
    val selectedFolder: StateFlow<BackupFolder?> = _selectedFolder.asStateFlow()

    /**
     * Update the minimum battery level setting.
     */
    fun setMinBatteryLevel(level: Int) {
        settingsPreferences.setMinBatteryLevel(level)
    }

    /**
     * Toggle automatic (background) backup. When turned off, backup only runs
     * when the user taps the Local tab "立即备份" FAB; all automatic triggers
     * (periodic scan, MediaStore observer, condition recovery, resume-after-kill)
     * stop uploading on their own.
     */
    fun setAutoBackupEnabled(enabled: Boolean) {
        settingsPreferences.setAutoBackupEnabled(enabled)
    }

    /**
     * Toggle whether backup is restricted to WiFi only.
     */
    fun setWifiOnly(enabled: Boolean) {
        settingsPreferences.setWifiOnly(enabled)
    }

    /**
     * Update the scan interval and reschedule the WorkManager periodic task.
     */
    fun setScanInterval(minutes: Int) {
        val previousInterval = settingsPreferences.getScanIntervalMinutes()
        settingsPreferences.setScanIntervalMinutes(minutes)

        // Reschedule WorkManager if interval changed
        if (previousInterval != minutes) {
            BackgroundScanWorker.reschedule(context, minutes.toLong())
        }
    }

    /**
     * Show the policy configuration sheet for a folder.
     */
    fun showPolicyForFolder(folder: BackupFolder) {
        _selectedFolder.value = folder
        _showPolicySheet.value = true
    }

    /**
     * Dismiss the policy configuration sheet.
     */
    fun dismissPolicySheet() {
        _showPolicySheet.value = false
        _selectedFolder.value = null
    }

    /**
     * Update storage policy for a folder from the settings tab.
     */
    fun updateFolderPolicy(
        folderId: Long,
        useCustomPath: Boolean,
        customPath: String?,
        useYearMonthLayer: Boolean
    ) {
        viewModelScope.launch {
            backupFolderRepository.updateStoragePolicy(
                folderId = folderId,
                useCustomPath = useCustomPath,
                customPath = customPath,
                useYearMonthLayer = useYearMonthLayer
            )
            dismissPolicySheet()
        }
    }

    /**
     * Perform logout: clear access token and refresh token from CredentialManager.
     */
    fun logout() {
        credentialManager.clearTokens()
    }
}
