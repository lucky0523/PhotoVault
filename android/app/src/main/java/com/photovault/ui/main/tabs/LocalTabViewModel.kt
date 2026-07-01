package com.photovault.ui.main.tabs

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.local.CredentialManager
import com.photovault.data.local.entity.BackupFolder
import com.photovault.data.repository.AuthRepository
import com.photovault.data.repository.BackupFolderRepository
import com.photovault.service.BackupConditionChecker
import com.photovault.service.BackgroundScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Local Tab.
 * Manages backup folder list, folder addition, policy configuration, and removal.
 */
@HiltViewModel
class LocalTabViewModel @Inject constructor(
    private val backupFolderRepository: BackupFolderRepository,
    private val backupConditionChecker: BackupConditionChecker,
    private val authRepository: AuthRepository,
    private val credentialManager: CredentialManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val folders: StateFlow<List<BackupFolder>> = backupFolderRepository.getAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedFolder = MutableStateFlow<BackupFolder?>(null)
    val selectedFolder: StateFlow<BackupFolder?> = _selectedFolder.asStateFlow()

    private val _showPolicySheet = MutableStateFlow(false)
    val showPolicySheet: StateFlow<Boolean> = _showPolicySheet.asStateFlow()

    private val _pendingFolderUri = MutableStateFlow<Uri?>(null)
    val pendingFolderUri: StateFlow<Uri?> = _pendingFolderUri.asStateFlow()

    /**
     * Called when user picks a folder from the system folder picker.
     * Stores the URI and shows the policy configuration sheet.
     */
    fun onFolderPicked(uri: Uri, folderName: String) {
        _pendingFolderUri.value = uri
        _selectedFolder.value = BackupFolder(
            folderUri = uri.toString(),
            folderName = folderName
        )
        _showPolicySheet.value = true
    }

    /**
     * Save a new folder with the configured storage policy.
     */
    fun saveNewFolder(
        useCustomPath: Boolean,
        customPath: String?,
        useYearMonthLayer: Boolean
    ) {
        val uri = _pendingFolderUri.value ?: return
        val name = _selectedFolder.value?.folderName ?: return

        viewModelScope.launch {
            backupFolderRepository.addFolder(
                folderUri = uri.toString(),
                folderName = name,
                useCustomPath = useCustomPath,
                customPath = customPath,
                useYearMonthLayer = useYearMonthLayer
            )
            dismissPolicySheet()
            // Trigger an immediate scan so backup starts without waiting for the periodic worker
            BackgroundScanWorker.runOnce(context)
        }
    }

    /**
     * Update storage policy for an existing folder.
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
     * Show policy sheet for an existing folder (long-press → configure).
     */
    fun showPolicyForFolder(folder: BackupFolder) {
        _selectedFolder.value = folder
        _pendingFolderUri.value = null
        _showPolicySheet.value = true
    }

    /**
     * Remove a backup folder from the list.
     */
    fun removeFolder(folderId: Long) {
        viewModelScope.launch {
            backupFolderRepository.removeFolder(folderId)
        }
    }

    fun dismissPolicySheet() {
        _showPolicySheet.value = false
        _pendingFolderUri.value = null
    }

    /**
     * Manually triggers an immediate full backup scan ("立即备份").
     * Checks conditions before starting: battery level (unless charging), network, and server connectivity.
     * @param onResult Callback with error message or null if backup started successfully.
     */
    fun backupNow(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = doBackupNow()
            onResult(error)
        }
    }

    private suspend fun doBackupNow(): String? {
        val networkOk = backupConditionChecker.isNetworkAvailableForBackup()
        if (!networkOk) {
            return "网络不可用，请检查网络连接"
        }

        val isCharging = backupConditionChecker.isCharging()
        val batteryLevel = backupConditionChecker.getBatteryLevel()
        val minBattery = backupConditionChecker.getMinBatteryLevel()

        if (!isCharging && batteryLevel <= minBattery) {
            return "电量不足 ${minBattery}%，请充电后再试"
        }

        // Check server connectivity
        val serverAddress = credentialManager.getServerAddress()
        if (!serverAddress.isNullOrEmpty()) {
            val connectionResult = authRepository.testConnection(serverAddress)
            if (connectionResult.isFailure) {
                return "服务器未连接: ${connectionResult.exceptionOrNull()?.localizedMessage ?: "连接失败"}"
            }
        }

        BackgroundScanWorker.runNow(context)
        return null
    }

    /**
     * Gets the configured minimum battery level from settings.
     */
    fun getMinBatteryLevel(): Int {
        return backupConditionChecker.getMinBatteryLevel()
    }
}
