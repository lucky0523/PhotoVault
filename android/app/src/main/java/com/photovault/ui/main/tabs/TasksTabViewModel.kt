package com.photovault.ui.main.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.local.dao.BackupHistoryDao
import com.photovault.data.local.entity.BackupHistoryRecord
import com.photovault.data.local.entity.BackupStatus
import com.photovault.service.BackupConditionChecker
import com.photovault.service.BackupForegroundService
import com.photovault.service.BackupQueue
import com.photovault.service.FileInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Represents the currently selected segment in the tasks tab.
 */
enum class TasksSegment {
    CURRENT_TASKS,
    HISTORY
}

/**
 * Filter options for history records.
 */
enum class HistoryFilter {
    ALL,
    SUCCESS,
    FAILED
}

/**
 * Represents a pause reason for the backup process.
 */
sealed class PauseReason(val message: String, val resumeHint: String) {
    data object LowBattery : PauseReason("电量不足", "将在电量恢复至 55% 以上后自动恢复")
    data object NoWifi : PauseReason("WiFi 未连接", "将在 WiFi 连接后自动恢复")
    data object LowBatteryAndNoWifi : PauseReason("电量不足且 WiFi 未连接", "将在条件满足后自动恢复")
}

/**
 * State for the current upload task.
 */
data class CurrentUploadState(
    val fileName: String = "",
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0,
    val remainingSeconds: Long = 0,
    val uploadedBytes: Long = 0,
    val totalBytes: Long = 0
)

/**
 * A group of history records sharing the same date.
 */
data class HistoryDateGroup(
    val date: String,
    val records: List<BackupHistoryRecord>
)

/**
 * UI state for the Tasks Tab.
 */
data class TasksTabUiState(
    val selectedSegment: TasksSegment = TasksSegment.CURRENT_TASKS,
    // Current tasks
    val isUploading: Boolean = false,
    val isPaused: Boolean = false,
    val pauseReason: PauseReason? = null,
    val currentUpload: CurrentUploadState = CurrentUploadState(),
    val queuedFiles: List<FileInfo> = emptyList(),
    val queueSize: Int = 0,
    // History
    val historyFilter: HistoryFilter = HistoryFilter.ALL,
    val historyGroups: List<HistoryDateGroup> = emptyList(),
    val isHistoryLoading: Boolean = false
)

/**
 * ViewModel for the Backup Tasks Tab.
 * Manages current backup task state (upload progress, queue, pause status)
 * and backup history (date-grouped records with filtering).
 */
@HiltViewModel
class TasksTabViewModel @Inject constructor(
    private val backupQueue: BackupQueue,
    private val backupConditionChecker: BackupConditionChecker,
    private val backupHistoryDao: BackupHistoryDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksTabUiState())
    val uiState: StateFlow<TasksTabUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

    init {
        startPollingCurrentTasks()
        loadHistory()
    }

    /**
     * Switch between "Current Tasks" and "History" segments.
     */
    fun selectSegment(segment: TasksSegment) {
        _uiState.value = _uiState.value.copy(selectedSegment = segment)
        if (segment == TasksSegment.HISTORY) {
            loadHistory()
        }
    }

    /**
     * Set the filter for history records.
     */
    fun setHistoryFilter(filter: HistoryFilter) {
        _uiState.value = _uiState.value.copy(historyFilter = filter)
        loadHistory()
    }

    /**
     * Retry a failed backup record.
     */
    fun retryFailedRecord(record: BackupHistoryRecord) {
        viewModelScope.launch {
            // Re-enqueue the file for backup
            val fileInfo = FileInfo(
                uri = record.fileUri,
                fileName = record.fileName,
                fileSize = record.fileSize,
                createdTime = record.completedAt,
                mimeType = "image/*",
                folderUri = record.folderUri
            )
            backupQueue.enqueue(listOf(fileInfo))

            // Update the record status to indicate retry is pending
            backupHistoryDao.updateStatus(
                id = record.id,
                status = BackupStatus.SKIPPED,
                errorMessage = "重试中..."
            )

            // Refresh state
            refreshCurrentTasks()
            loadHistory()
        }
    }

    /**
     * Update the current upload progress from the foreground service.
     * Called externally when progress changes.
     */
    fun updateUploadProgress(
        fileName: String,
        uploadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ) {
        val progress = if (totalBytes > 0) uploadedBytes.toFloat() / totalBytes else 0f
        val remainingSeconds = if (speedBytesPerSec > 0) {
            (totalBytes - uploadedBytes) / speedBytesPerSec
        } else {
            0L
        }

        _uiState.value = _uiState.value.copy(
            isUploading = true,
            currentUpload = CurrentUploadState(
                fileName = fileName,
                progress = progress,
                speedBytesPerSec = speedBytesPerSec,
                remainingSeconds = remainingSeconds,
                uploadedBytes = uploadedBytes,
                totalBytes = totalBytes
            )
        )
    }

    /**
     * Polls the current backup task state periodically.
     */
    private fun startPollingCurrentTasks() {
        viewModelScope.launch {
            while (isActive) {
                refreshCurrentTasks()
                delay(2000) // Poll every 2 seconds
            }
        }
    }

    /**
     * Refresh the current tasks state from the service.
     */
    private fun refreshCurrentTasks() {
        val isRunning = BackupForegroundService.isRunning
        val isPaused = BackupForegroundService.isPaused
        val queuedFiles = backupQueue.getAll()

        val pauseReason = if (isPaused) {
            determinePauseReason()
        } else {
            null
        }

        _uiState.value = _uiState.value.copy(
            isUploading = isRunning && !isPaused,
            isPaused = isPaused,
            pauseReason = pauseReason,
            queuedFiles = queuedFiles,
            queueSize = queuedFiles.size
        )
    }

    /**
     * Determine why backup is paused based on current conditions.
     * When charging, battery level is not considered a pause reason.
     */
    private fun determinePauseReason(): PauseReason {
        val isCharging = backupConditionChecker.isCharging()
        val batteryOk = isCharging || backupConditionChecker.getBatteryLevel() > BackupConditionChecker.BATTERY_PAUSE_THRESHOLD
        val networkOk = backupConditionChecker.isNetworkAvailableForBackup()

        return when {
            !batteryOk && !networkOk -> PauseReason.LowBatteryAndNoWifi
            !batteryOk -> PauseReason.LowBattery
            !networkOk -> PauseReason.NoWifi
            else -> PauseReason.NoWifi // fallback
        }
    }

    private var historyCollectionJob: kotlinx.coroutines.Job? = null

    /**
     * Load history records from the database with current filter applied.
     */
    private fun loadHistory() {
        // Cancel any existing history collection
        historyCollectionJob?.cancel()

        historyCollectionJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isHistoryLoading = true)

            val filter = _uiState.value.historyFilter
            val flow = when (filter) {
                HistoryFilter.ALL -> backupHistoryDao.getAll()
                HistoryFilter.SUCCESS -> backupHistoryDao.getByStatus(BackupStatus.SUCCESS)
                HistoryFilter.FAILED -> backupHistoryDao.getByStatus(BackupStatus.FAILED)
            }

            flow.collect { records ->
                val groups = groupByDate(records)
                _uiState.value = _uiState.value.copy(
                    historyGroups = groups,
                    isHistoryLoading = false
                )
            }
        }
    }

    /**
     * Group history records by date for display.
     */
    private fun groupByDate(records: List<BackupHistoryRecord>): List<HistoryDateGroup> {
        return records
            .groupBy { dateFormat.format(Date(it.completedAt)) }
            .map { (dateKey, items) ->
                val displayDate = try {
                    val date = dateFormat.parse(dateKey)
                    if (date != null) displayDateFormat.format(date) else dateKey
                } catch (e: Exception) {
                    dateKey
                }
                HistoryDateGroup(date = displayDate, records = items)
            }
    }
}
