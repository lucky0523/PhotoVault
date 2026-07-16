package com.photovault.ui.main.tabs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.local.CredentialManager
import com.photovault.data.local.SettingsPreferences
import com.photovault.data.local.dao.UploadRecordDao
import com.photovault.data.local.entity.BackupFolder
import com.photovault.data.local.entity.UploadRecord
import com.photovault.data.network.ConnectionManager
import com.photovault.data.network.ConnectionState
import com.photovault.data.repository.BackupFolderRepository
import com.photovault.service.BackgroundScanWorker
import com.photovault.service.BackupForegroundService
import com.photovault.service.BackupQueue
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
    private val backupQueue: BackupQueue,
    private val uploadRecordDao: UploadRecordDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        /**
         * Resume sessions older than this (measured from `created_at`) are
         * considered expired: they'll be rediscovered as ordinary new files by a
         * later full scan and re-uploaded fresh, so there's no point keeping them
         * as an AUTO_OFF Paused_Task. Mirrors the 7-day session expiry used by
         * [BackgroundScanWorker.requeueResumableUploads] and the server. (R-32.1)
         */
        const val SESSION_EXPIRY_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * From a snapshot of persisted [UploadRecord]s, selects exactly the ones
         * that should be marked `AUTO_OFF` when the user turns OFF "自动备份" while
         * an automatic run is active: the In_Flight_File(s) (`uploaded_chunk_index
         * >= 0`, via [BackupForegroundService.selectInFlightForAutoOff]) that are
         * still within the 7-day resume window (`nowMs - created_at <=
         * sessionExpiryMs`). Queued_Not_Started_File(s) have no [UploadRecord] and
         * so never appear here; expired records are dropped (R-25.2/25.5/32.1).
         *
         * Pure function (no service/DB) so the partition can be unit- and
         * property-tested (design Property 18/22), mirroring
         * [BackupForegroundService.shouldStopAutoOnDisable].
         *
         * @return the subset of [records] to mark AUTO_OFF, in input order.
         */
        fun recordsToMarkAutoOff(
            records: List<UploadRecord>,
            nowMs: Long,
            sessionExpiryMs: Long
        ): List<UploadRecord> =
            BackupForegroundService.selectInFlightForAutoOff(records)
                .filter { nowMs - it.createdAt <= sessionExpiryMs }
    }

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
     *
     * Turning it off ALSO acts on a task that is already running (R-3.13/3.14/3.15):
     * - If the service is running and the run is **manual** ([BackupForegroundService.isManualRun]),
     *   leave it alone so the manual task finishes (R-3.15).
     * - Otherwise (an **automatic** run is in progress, or the service isn't
     *   running but the queue still holds automatically-enqueued files), stop the
     *   service via [BackupForegroundService.stopAuto] and clear the [BackupQueue]
     *   so no more automatic files upload (R-3.14). The in-flight file's persisted
     *   [UploadRecord] is preserved (the queue clear doesn't touch it), so
     *   re-enabling auto-backup resumes it from its 断点续传 breakpoint.
     */
    fun setAutoBackupEnabled(enabled: Boolean) {
        settingsPreferences.setAutoBackupEnabled(enabled)

        // Trigger ①: re-enabling the auto-backup switch immediately resumes a
        // backup the user had manually paused. ACTION_RESUME clears the persisted
        // user-pause flag and continues from the 断点续传 breakpoint when conditions
        // allow. (Nothing to do if there is no outstanding user pause.)
        if (enabled && settingsPreferences.getUserPausedBackup()) {
            BackupForegroundService.resume(context)
        }

        if (!enabled &&
            BackupForegroundService.shouldStopAutoOnDisable(
                isRunning = BackupForegroundService.isRunning,
                isManualRun = BackupForegroundService.isManualRun
            )
        ) {
            // Automatic run in progress, or residual automatically-queued files:
            // stop the service first, then (asynchronously) mark the In_Flight_File
            // as AUTO_OFF and clear the queue (R-3.14/25.1/25.2/25.3). A manual run
            // is left untouched (R-3.15) — shouldStopAutoOnDisable returns false.
            BackupForegroundService.stopAuto(context)
            viewModelScope.launch {
                // R-25.2: keep the In_Flight_File's Upload_Record and mark it
                // AUTO_OFF so it surfaces as a paused task.
                val markedCount = markInFlightAsAutoOffPaused()
                // R-25.1/25.3: drop the not-yet-started files (they have no
                // Upload_Record, so they produce no Paused_Task).
                backupQueue.clear()
                // R-30.2: when >=1 In_Flight_File was preserved as an AUTO_OFF
                // Paused_Task, post a standalone reminder (the foreground service
                // notification was removed by stopAuto) whose wording is distinct
                // from the USER/CONDITION pause notifications and names
                // "自动备份已关闭" as the cause. No-op when nothing was marked (R-25.5).
                BackupForegroundService.postAutoOffPausedNotification(context, markedCount)
            }
        }
    }

    /**
     * Marks every currently-valid In_Flight_File's persisted [UploadRecord] as
     * `AUTO_OFF` (R-25.2). Loads the full record snapshot, keeps only the ones
     * [recordsToMarkAutoOff] selects (a confirmed chunk + within the 7-day resume
     * window), and flips each to AUTO_OFF via [UploadRecordDao.markAutoOffPaused].
     * When nothing is in flight this marks nothing (R-25.5).
     *
     * @return the number of records marked AUTO_OFF, used as the N in the
     *   "自动备份已关闭，有 N 个未完成任务" reminder notification (R-30.2). Returns 0
     *   when nothing was in flight or the record load failed.
     */
    private suspend fun markInFlightAsAutoOffPaused(): Int {
        val records = try {
            uploadRecordDao.getAll()
        } catch (e: Exception) {
            android.util.Log.e(
                "PhotoVaultSettings",
                "Failed to load upload records to mark AUTO_OFF: ${e.message}",
                e
            )
            return 0
        }
        val toMark = recordsToMarkAutoOff(
            records = records,
            nowMs = System.currentTimeMillis(),
            sessionExpiryMs = SESSION_EXPIRY_MS
        )
        for (record in toMark) {
            uploadRecordDao.markAutoOffPaused(record.fileUri)
        }
        return toMark.size
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

        // Reschedule WorkManager if interval changed. applyScanInterval switches
        // between the normal periodic worker and the debug ~10s test chain.
        if (previousInterval != minutes) {
            BackgroundScanWorker.applyScanInterval(context, minutes)
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
