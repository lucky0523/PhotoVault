package com.photovault.ui.main.tabs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.local.dao.BackupHistoryDao
import com.photovault.data.local.dao.UploadRecordDao
import com.photovault.data.local.entity.BackupHistoryRecord
import com.photovault.data.local.entity.BackupStatus
import com.photovault.data.local.entity.UploadRecord
import com.photovault.service.BackupConditionChecker
import com.photovault.service.BackupForegroundService
import com.photovault.service.BackupQueue
import com.photovault.service.FileInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    FAILED,
    SKIPPED
}

/**
 * Represents why the backup is currently paused, as shown on the 备份任务 Tab.
 *
 * The UI distinguishes a **user pause** (the user tapped "暂停") from a
 * **condition pause** (battery low / WiFi disconnected) per R-24.5:
 * - [UserPaused] renders a neutral/grey pause indicator and prompts the user to
 *   tap "开始" to continue — it is NOT auto-resumed by condition recovery.
 * - The condition variants render an amber warning indicator with the specific
 *   recovery hint, and resume automatically once conditions are met.
 *
 * @property message headline shown after "备份已暂停：".
 * @property resumeHint secondary line describing how/when backup resumes.
 * @property isUserPause true for the user-initiated pause; drives the neutral
 *   visual treatment and "点击开始继续" wording.
 */
sealed class PauseReason(
    val message: String,
    val resumeHint: String,
    val isUserPause: Boolean = false
) {
    data object UserPaused : PauseReason("已手动暂停", "点击开始继续", isUserPause = true)
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
 * UI model for a single AUTO_OFF paused task shown on the 备份任务 Tab.
 *
 * These represent In_Flight_File(s) whose [UploadRecord] was marked `AUTO_OFF`
 * when the user turned off "自动备份" mid-upload (R-25.2). They are surfaced as
 * "已暂停" tasks the user can manually continue (27.6) or clear (27.7) instead
 * of being resumed silently.
 *
 * @property fileUri the source file's URI, used as the stable identity/key.
 * @property fileName display name of the file.
 * @property progressPercent integer upload progress 0..100 (R-26.2/26.3).
 * @property pausedAt epoch millis when the task was paused, used for ordering
 *   (most recently paused first, R-26.1).
 */
data class PausedTaskUi(
    val fileUri: String,
    val fileName: String,
    val progressPercent: Int,
    val pausedAt: Long
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
    // Manual start/pause control (R-24)
    /** True while the foreground service reports an actively running (non-paused) backup. */
    val isBackupRunning: Boolean = false,
    /**
     * Whether the in-progress run was started by an explicit user action (manual)
     * vs an automatic trigger. Drives the "正在手动/自动备份" label on the button
     * while a backup is actively running.
     */
    val isManualRun: Boolean = false,
    // AUTO_OFF paused tasks (R-26): In_Flight_File(s) preserved when the user
    // turned off "自动备份" mid-upload. Ordered by pause time (most recent first).
    val pausedTasks: List<PausedTaskUi> = emptyList(),
    val isPausedTasksLoading: Boolean = false,
    val pausedTasksLoadError: Boolean = false,
    /**
     * A one-shot message to surface to the user (e.g. via a snackbar) after an
     * action on a paused task. Currently used when a "继续" (resume) is rejected
     * because the source file is gone (R-27.6/32.3). The UI must call
     * [TasksTabViewModel.consumeTransientMessage] once it has shown the message
     * so it is not re-displayed on the next recomposition.
     */
    val transientMessage: String? = null,
    // History
    val historyFilter: HistoryFilter = HistoryFilter.ALL,
    val historyGroups: List<HistoryDateGroup> = emptyList(),
    val isHistoryLoading: Boolean = false
) {
    /**
     * Label/semantics for the manual "开始/暂停" button (R-24.1):
     * shows "暂停" while a backup is actively in progress, "开始" when paused or idle.
     */
    val isStartPauseShowingPause: Boolean
        get() = isBackupRunning && !isPaused

    /**
     * Whether the "开始/暂停" button is enabled (R-24.4): disabled only when the
     * queue is empty AND there is no in-progress task (nothing running, nothing paused).
     */
    val isStartPauseEnabled: Boolean
        get() = queuedFiles.isNotEmpty() || isBackupRunning || isPaused
}

/**
 * ViewModel for the Backup Tasks Tab.
 * Manages current backup task state (upload progress, queue, pause status)
 * and backup history (date-grouped records with filtering).
 */
@HiltViewModel
class TasksTabViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupQueue: BackupQueue,
    private val backupConditionChecker: BackupConditionChecker,
    private val backupHistoryDao: BackupHistoryDao,
    private val uploadRecordDao: UploadRecordDao
) : ViewModel() {

    companion object {
        /**
         * Paused tasks older than this (measured from `created_at`) are treated
         * as expired and dropped from the list: a later full scan will
         * rediscover them as ordinary new files and re-upload them fresh, so
         * there is no point surfacing them as AUTO_OFF Paused_Task(s). Mirrors
         * the 7-day session expiry used by [SettingsViewModel] and the server
         * (R-32.1).
         */
        const val SESSION_EXPIRY_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Computes integer upload progress (0..100) for a paused task from its
         * persisted resume state (R-26.2/26.3).
         *
         * [uploadedChunkIndex] is the index of the last confirmed chunk (-1 when
         * nothing has been confirmed yet), so `uploadedChunkIndex + 1` is the
         * number of completed chunks. Integer division floors the percentage.
         * Returns 0 when [totalChunks] is non-positive (no meaningful progress),
         * and clamps the result into [0, 100].
         *
         * Pure function (no ViewModel/DB state) so it can be unit- and
         * property-tested (design Property 20).
         */
        fun computeProgressPercent(uploadedChunkIndex: Int, totalChunks: Int): Int {
            if (totalChunks <= 0) return 0
            val uploaded = (uploadedChunkIndex + 1).coerceIn(0, totalChunks)
            return (uploaded * 100 / totalChunks).coerceIn(0, 100)
        }
    }

    private val _uiState = MutableStateFlow(TasksTabUiState())
    val uiState: StateFlow<TasksTabUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

    init {
        startPollingCurrentTasks()
        observeUploadProgress()
        loadHistory()
        loadPausedTasks()
    }

    /**
     * Collects the live upload progress published by [BackupForegroundService]
     * and maps it into [TasksTabUiState.currentUpload], so the current upload card
     * shows the real file name, transferred/total bytes, transfer speed, computed
     * progress, and derived ETA. Emits a cleared [CurrentUploadState] when nothing
     * is uploading (snapshot == null).
     */
    private fun observeUploadProgress() {
        viewModelScope.launch {
            BackupForegroundService.uploadProgress.collect { snapshot ->
                val current = if (snapshot == null) {
                    CurrentUploadState()
                } else {
                    val progress = if (snapshot.totalBytes > 0) {
                        (snapshot.uploadedBytes.toFloat() / snapshot.totalBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val remainingSeconds = if (snapshot.speedBytesPerSec > 0) {
                        ((snapshot.totalBytes - snapshot.uploadedBytes) / snapshot.speedBytesPerSec)
                            .coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    CurrentUploadState(
                        fileName = snapshot.fileName,
                        progress = progress,
                        speedBytesPerSec = snapshot.speedBytesPerSec,
                        remainingSeconds = remainingSeconds,
                        uploadedBytes = snapshot.uploadedBytes,
                        totalBytes = snapshot.totalBytes
                    )
                }
                _uiState.value = _uiState.value.copy(currentUpload = current)
            }
        }
    }

    /**
     * Loads the AUTO_OFF paused tasks from persistence and publishes them to
     * [TasksTabUiState.pausedTasks] (R-26.1/26.2/26.3/26.7/31.1/31.3/32.1).
     *
     * Reads [UploadRecordDao.getPausedByAutoOff] (already ordered by `paused_at`
     * DESC, R-26.1), drops records outside the 7-day resume window (R-32.1), and
     * maps each surviving record to a [PausedTaskUi] with a computed integer
     * progress. On any read failure it sets [TasksTabUiState.pausedTasksLoadError]
     * and does NOT delete or modify any [UploadRecord] (R-26.4) — the persisted
     * records are the source of truth and are rebuilt on the next successful load
     * (including after an app restart, R-31.1/31.3).
     */
    fun loadPausedTasks() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPausedTasksLoading = true,
                pausedTasksLoadError = false
            )
            try {
                val now = System.currentTimeMillis()
                val records = uploadRecordDao.getPausedByAutoOff()
                    .filter { now - it.createdAt <= SESSION_EXPIRY_MS }
                val pausedTasks = records.map { record ->
                    PausedTaskUi(
                        fileUri = record.fileUri,
                        fileName = record.fileName,
                        progressPercent = computeProgressPercent(
                            record.uploadedChunkIndex,
                            record.totalChunks
                        ),
                        pausedAt = record.pausedAt ?: record.updatedAt
                    )
                }
                _uiState.value = _uiState.value.copy(
                    pausedTasks = pausedTasks,
                    isPausedTasksLoading = false
                )
            } catch (e: Exception) {
                // R-26.4: surface the error but leave persisted records untouched.
                android.util.Log.e(
                    "PhotoVaultTasks",
                    "Failed to load AUTO_OFF paused tasks: ${e.message}",
                    e
                )
                _uiState.value = _uiState.value.copy(
                    isPausedTasksLoading = false,
                    pausedTasksLoadError = true
                )
            }
        }
    }

    /**
     * Manually continue a single AUTO_OFF Paused_Task (R-27.1..27.6, 30.4/30.5,
     * 32.2/32.3).
     *
     * Flow:
     * 1. Look up the persisted [UploadRecord] by [fileUri]. If it no longer
     *    exists (e.g. already cleared/expired concurrently), just refresh the
     *    list and return — nothing to resume.
     * 2. **Source-file pre-check** (R-27.6/32.3): verify the source URI is still
     *    readable. If it is gone/unreadable, delete the record, surface a
     *    one-shot "源文件已不存在，无法续传" message, refresh the list (dropping the
     *    entry) and return WITHOUT starting an upload.
     * 3. Clear ONLY this record's AUTO_OFF marker via
     *    [UploadRecordDao.clearAutoOffPause] (R-30.5: other AUTO_OFF records are
     *    untouched). This turns it back into an ordinary resume record so the
     *    automatic-resume gate no longer excludes it and the upload loop resumes
     *    from the persisted breakpoint. The Auto_Backup_Switch is NOT changed
     *    (R-27.2).
     * 4. Rebuild a [FileInfo] from the record and enqueue it, then start the
     *    foreground service as a **manual** run (R-27.1) so turning off auto
     *    backup won't stop it. The existing ChunkUploader/SnapshotValidator loop
     *    then handles resume, condition pauses, retries and success cleanup
     *    (delete-on-complete) with no extra work here (R-27.3/27.4/27.5/27.7/27.8/32.2).
     * 5. Refresh current tasks and the paused list so the entry moves out of the
     *    "已暂停" section.
     */
    fun resumePausedTask(fileUri: String) {
        viewModelScope.launch {
            val record = uploadRecordDao.getByFileUri(fileUri) ?: run {
                // Record already gone (cleared/expired elsewhere): just re-sync.
                loadPausedTasks()
                return@launch
            }

            // R-27.6/32.3: never start an upload for a source that no longer
            // exists. Drop the orphaned record and tell the user.
            if (!isSourceReadable(record.fileUri)) {
                uploadRecordDao.deleteByFileUri(fileUri)
                _uiState.value = _uiState.value.copy(
                    transientMessage = "源文件已不存在，无法续传"
                )
                loadPausedTasks()
                return@launch
            }

            // R-30.5: clear the AUTO_OFF marker for THIS file only.
            uploadRecordDao.clearAutoOffPause(fileUri)

            // Rebuild the queue entry and kick off a manual run (R-27.1).
            val fileInfo = record.toFileInfo()
            backupQueue.enqueue(listOf(fileInfo))
            BackupForegroundService.start(context, manual = true)

            refreshCurrentTasks()
            loadPausedTasks()
        }
    }

    /**
     * Permanently clear a single AUTO_OFF Paused_Task, abandoning any further
     * resume of that file (R-28.2/28.3/28.4/28.6).
     *
     * Deletes ONLY this file's [UploadRecord] via
     * [UploadRecordDao.deleteByFileUri] (R-28.2). Because the record is the sole
     * source of truth for the paused list and the automatic-resume gate, once it
     * is gone the file will never be re-enqueued or resumed on later scans,
     * continues or process rebuilds — unless a future full scan rediscovers it
     * as a brand-new file (R-28.6). On success the list is refreshed so the
     * entry disappears well within the 1-second budget (R-28.4).
     *
     * On failure we deliberately do nothing destructive: the record is left
     * intact, the entry stays in the list, and a one-shot "清除失败，请重试"
     * message is surfaced so the user can retry (R-28.3).
     */
    fun clearPausedTask(fileUri: String) {
        viewModelScope.launch {
            try {
                // R-28.2: remove only this file's record.
                uploadRecordDao.deleteByFileUri(fileUri)
                // R-28.4: refresh so the entry leaves the "已暂停" section.
                loadPausedTasks()
            } catch (e: Exception) {
                // R-28.3: leave the record & entry untouched, tell the user.
                android.util.Log.e(
                    "PhotoVaultTasks",
                    "Failed to clear AUTO_OFF paused task $fileUri: ${e.message}",
                    e
                )
                _uiState.value = _uiState.value.copy(
                    transientMessage = "清除失败，请重试"
                )
            }
        }
    }

    /**
     * Clears the one-shot [TasksTabUiState.transientMessage] after the UI has
     * displayed it (see [resumePausedTask]).
     */
    fun consumeTransientMessage() {
        if (_uiState.value.transientMessage != null) {
            _uiState.value = _uiState.value.copy(transientMessage = null)
        }
    }

    /**
     * Best-effort check that the source file behind [uriString] still exists and
     * is readable. Opens (and immediately closes) an input stream through the
     * [android.content.ContentResolver]; any failure — missing file, revoked
     * permission, malformed URI — is treated as "not readable" (R-27.6/32.3).
     */
    private fun isSourceReadable(uriString: String): Boolean {
        return try {
            val uri = android.net.Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
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

            // Delete the original failed record. The backup service inserts a
            // fresh record with the actual outcome when the file is processed,
            // so removing the old one avoids a stale/duplicate entry.
            backupHistoryDao.deleteById(record.id)

            // Kick off the backup service so the re-enqueued file is actually
            // processed (otherwise it would just sit in the in-memory queue).
            // The task-page 重试 is an explicit user action: mark the run manual
            // so turning off "自动备份" mid-run won't stop it (R-3.15).
            BackupForegroundService.start(context, manual = true)

            // Refresh state
            refreshCurrentTasks()
            loadHistory()
        }
    }

    /**
     * Clear all backup history records.
     */
    fun clearHistory() {
        viewModelScope.launch {
            backupHistoryDao.deleteAll()
            loadHistory()
        }
    }

    /**
     * Polls the current backup task state periodically.
     */
    private fun startPollingCurrentTasks() {
        viewModelScope.launch {
            while (isActive) {
                refreshCurrentTasks()
                // Keep the AUTO_OFF paused list in sync as records are added
                // (turning off auto-backup) or removed (continue/clear), so
                // opening the page, polling, and app restart all reflect the
                // persisted state (R-26.7/31.1/31.3).
                loadPausedTasks()
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

        // Distinguish a user-initiated pause from a condition pause (R-24.5).
        // A user pause takes priority: even if a condition also happens to be
        // unmet, we show the "已手动暂停，点击开始继续" wording rather than a
        // condition-recovery hint, because condition recovery must not auto-resume
        // a user pause.
        val pauseReason = when {
            !isPaused -> null
            BackupForegroundService.isUserPaused ||
                BackupForegroundService.pauseReason == com.photovault.service.PauseReason.USER ->
                PauseReason.UserPaused
            else -> determinePauseReason()
        }

        _uiState.value = _uiState.value.copy(
            isUploading = isRunning && !isPaused,
            isPaused = isPaused,
            isBackupRunning = isRunning,
            isManualRun = BackupForegroundService.isManualRun,
            pauseReason = pauseReason,
            queuedFiles = queuedFiles,
            queueSize = queuedFiles.size
        )
    }

    /**
     * Toggle the manual start/pause control (R-24.1/24.2/24.3).
     *
     * - While a backup is actively in progress, dispatch [BackupForegroundService.pause]
     *   with [PauseReason.USER] to mark a user pause and preserve 断点续传 progress.
     * - While paused or idle (with queued work), dispatch [BackupForegroundService.resume]
     *   to continue uploading the queue from the persisted breakpoint when conditions allow.
     */
    fun toggleStartPause() {
        val state = _uiState.value
        if (state.isBackupRunning && !state.isPaused) {
            BackupForegroundService.pause(context, com.photovault.service.PauseReason.USER)
        } else {
            BackupForegroundService.resume(context)
        }
        // Reflect the change quickly rather than waiting for the next poll tick.
        refreshCurrentTasks()
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
                HistoryFilter.SKIPPED -> backupHistoryDao.getByStatus(BackupStatus.SKIPPED)
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

/**
 * Rebuilds a [FileInfo] from a persisted [UploadRecord] so an AUTO_OFF
 * Paused_Task can be re-enqueued and resumed (task 27.6, Property 23).
 *
 * Field mapping:
 * - `uri`         ← [UploadRecord.fileUri]
 * - `fileName`    ← [UploadRecord.fileName]
 * - `fileSize`    ← [UploadRecord.fileSize]
 * - `createdTime` ← [UploadRecord.fileModifiedTime] (the original
 *                   `FileInfo.createdTime` was persisted into this column; the
 *                   backup queue orders by it, oldest first)
 * - `folderUri`   ← [UploadRecord.folderUri]
 * - `mimeType`    ← [UploadRecord.mimeType] when present, otherwise guessed from
 *                   the file-name extension, defaulting to a generic image type.
 *
 * Pure function (no Android framework or DB access) so it can be unit- and
 * property-tested. Mirrors the reconstruction done by
 * `BackgroundScanWorker.requeueResumableUploads`.
 */
fun UploadRecord.toFileInfo(): FileInfo = FileInfo(
    uri = fileUri,
    fileName = fileName,
    fileSize = fileSize,
    createdTime = fileModifiedTime,
    mimeType = mimeType.ifBlank { guessMimeTypeFromFileName(fileName) },
    folderUri = folderUri
)

/**
 * Best-effort MIME type guess from a file-name extension, kept dependency-free
 * (no `android.webkit.MimeTypeMap`) so [toFileInfo] stays a pure, testable
 * function. Covers the common photo/video extensions PhotoVault backs up and
 * falls back to a generic "image" wildcard type for anything unrecognised.
 */
fun guessMimeTypeFromFileName(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "dng" -> "image/x-adobe-dng"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        else -> "image/*"
    }
}
