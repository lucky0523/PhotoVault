package com.photovault.ui.main.tabs

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.photovault.data.local.entity.BackupHistoryRecord
import com.photovault.data.local.entity.BackupStatus
import com.photovault.ui.theme.LiquidDialogButton
import com.photovault.ui.theme.LiquidDialogButtonStyle
import com.photovault.ui.theme.LiquidGlassDialog
import com.photovault.ui.theme.liquid.LiquidProgressBar
import com.photovault.ui.theme.LocalBottomBarPadding
import com.photovault.ui.theme.PhotoVaultColors
import com.photovault.service.FileInfo
import kotlin.math.roundToInt

/**
 * 备份任务 Tab - 显示当前正在进行的备份任务、排队中的任务以及历史备份记录。
 *
 * 包含：
 * - 分段控制器（当前任务 / 历史记录）
 * - 当前任务视图：进度条 + 速度 + 剩余时间 + 排队列表 + 暂停原因提示
 * - 历史记录视图：按日期分组，状态筛选（全部/成功/失败），失败记录可点击重试
 */
@Composable
fun TasksTab(
    viewModel: TasksTabViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Surface one-shot messages (e.g. resume rejected / clear failed) as a
    // toast, then consume so it is not shown again on recomposition (R-28.3).
    LaunchedEffect(uiState.transientMessage) {
        val message = uiState.transientMessage
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeTransientMessage()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Segmented control (TabRow)
        SegmentedControl(
            selectedSegment = uiState.selectedSegment,
            onSegmentSelected = { viewModel.selectSegment(it) }
        )

        // Content based on selected segment
        when (uiState.selectedSegment) {
            TasksSegment.CURRENT_TASKS -> CurrentTasksView(
                uiState = uiState,
                onToggleStartPause = { viewModel.toggleStartPause() },
                onResumePausedTask = { viewModel.resumePausedTask(it) },
                onClearPausedTask = { viewModel.clearPausedTask(it) },
                onRetryLoadPausedTasks = { viewModel.loadPausedTasks() }
            )
            TasksSegment.HISTORY -> HistoryView(
                uiState = uiState,
                onFilterChanged = { viewModel.setHistoryFilter(it) },
                onRetry = { viewModel.retryFailedRecord(it) },
                onClearHistory = { viewModel.clearHistory() }
            )
        }
    }
}

/**
 * Segmented control using TabRow for switching between current tasks and history.
 */
@Composable
private fun SegmentedControl(
    selectedSegment: TasksSegment,
    onSegmentSelected: (TasksSegment) -> Unit
) {
    PrimaryTabRow(
        selectedTabIndex = selectedSegment.ordinal,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Tab(
            selected = selectedSegment == TasksSegment.CURRENT_TASKS,
            onClick = { onSegmentSelected(TasksSegment.CURRENT_TASKS) },
            text = { Text("当前任务") }
        )
        Tab(
            selected = selectedSegment == TasksSegment.HISTORY,
            onClick = { onSegmentSelected(TasksSegment.HISTORY) },
            text = { Text("历史记录") }
        )
    }
}

// ============================================================
// Current Tasks View
// ============================================================

/**
 * Displays current upload progress, queued files, and pause status.
 */
@Composable
private fun CurrentTasksView(
    uiState: TasksTabUiState,
    onToggleStartPause: () -> Unit,
    onResumePausedTask: (String) -> Unit,
    onClearPausedTask: (String) -> Unit,
    onRetryLoadPausedTasks: () -> Unit
) {
    // Tracks which paused task (by fileUri) currently has its long-press options
    // dialog open. null = no dialog shown (R-28.1).
    var longPressedFileUri by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 16.dp + LocalBottomBarPadding.current
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Manual start/pause control (R-24.1/24.4). A user pause is surfaced as a
        // status line on this button (no separate banner) — see statusText.
        item {
            StartPauseButton(
                showingPause = uiState.isStartPauseShowingPause,
                enabled = uiState.isStartPauseEnabled,
                isManualRun = uiState.isManualRun,
                statusText = if (uiState.isPaused && uiState.pauseReason?.isUserPause == true) {
                    uiState.pauseReason.message
                } else {
                    null
                },
                onClick = onToggleStartPause
            )
        }

        // AUTO_OFF paused tasks section (需求 26/27/28): tasks preserved when the
        // user turned off "自动备份" mid-upload. Each entry can be continued
        // (tap "继续") or cleared (long-press → "清除").
        if (uiState.pausedTasksLoadError) {
            // R-26.4: reading the paused list failed — offer a retry entry
            // without touching any persisted record.
            item {
                PausedTasksLoadErrorCard(onRetry = onRetryLoadPausedTasks)
            }
        } else if (uiState.pausedTasks.isNotEmpty()) {
            item {
                Text(
                    text = "已暂停（自动备份已关闭）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(
                items = uiState.pausedTasks,
                key = { it.fileUri }
            ) { task ->
                PausedTaskItem(
                    task = task,
                    onResume = { onResumePausedTask(task.fileUri) },
                    onLongPress = { longPressedFileUri = task.fileUri }
                )
            }
        }

        // Pause reason banner — only for CONDITION pauses (电量/WiFi). A user
        // pause no longer gets its own banner; it is shown on the 开始/暂停 button.
        if (uiState.isPaused && uiState.pauseReason != null && !uiState.pauseReason.isUserPause) {
            item {
                PauseReasonBanner(pauseReason = uiState.pauseReason)
            }
        }

        // Current upload progress
        if (uiState.isUploading) {
            item {
                CurrentUploadCard(upload = uiState.currentUpload)
            }
        }

        // Empty state when nothing is happening
        if (!uiState.isUploading && !uiState.isPaused && uiState.queuedFiles.isEmpty() &&
            uiState.pausedTasks.isEmpty() && !uiState.pausedTasksLoadError
        ) {
            item {
                EmptyCurrentTasksState()
            }
        }

        // Queue header
        if (uiState.queuedFiles.isNotEmpty()) {
            item {
                Text(
                    text = "排队中 (${uiState.queueSize})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Queued file list
            items(uiState.queuedFiles) { fileInfo ->
                QueuedFileItem(fileInfo = fileInfo)
            }
        }
    }

    // Long-press options dialog for a paused task (R-28.1/28.2/28.5).
    val targetFileUri = longPressedFileUri
    if (targetFileUri != null) {
        LiquidGlassDialog(
            onDismissRequest = { longPressedFileUri = null },
            title = "清除已暂停任务",
            text = "确定要清除这个已暂停的任务吗？将放弃该文件的续传，此操作不可恢复。"
        ) {
            LiquidDialogButton(
                text = "取消",
                onClick = { longPressedFileUri = null },
                style = LiquidDialogButtonStyle.Neutral
            )
            LiquidDialogButton(
                text = "清除",
                onClick = {
                    longPressedFileUri = null
                    onClearPausedTask(targetFileUri)
                },
                style = LiquidDialogButtonStyle.Destructive
            )
        }
    }
}

/**
 * Manual start/pause control for the current backup task (R-24).
 *
 * Renders "暂停" while a backup is actively in progress and "开始" when paused or
 * idle (R-24.1). Disabled when the queue is empty and no task is in progress
 * (R-24.4). Tapping dispatches ACTION_PAUSE / ACTION_RESUME via the ViewModel.
 */
@Composable
private fun StartPauseButton(
    showingPause: Boolean,
    enabled: Boolean,
    isManualRun: Boolean,
    statusText: String?,
    onClick: () -> Unit
) {
    // While actively backing up the button reads "正在手动/自动备份，点击暂停";
    // otherwise it's the plain "开始" action.
    val label = if (showingPause) {
        if (isManualRun) "正在手动备份，点击暂停" else "正在自动备份，点击暂停"
    } else {
        "开始"
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        // Reserve enough height for the two-line (label + status) layout so the
        // button doesn't grow/shrink when the status subtitle appears or clears
        // (e.g. switching between the uploading and paused states).
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            imageVector = if (showingPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (statusText != null) {
            // Show the action label plus the current pause status (e.g. 已手动暂停)
            // so no separate banner is needed.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f)
                )
            }
        } else {
            Text(label)
        }
    }
}

/**
 * Banner showing why backup is paused and when it will resume.
 *
 * Distinguishes a **user pause** from a **condition pause** (R-24.5 / 状态指示设计):
 * - 已暂停(用户): neutral grey pause icon + "点击开始继续" — backup only resumes
 *   when the user taps "开始".
 * - 已暂停(条件): amber warning icon + the specific recovery hint (e.g.
 *   "将在 WiFi 连接后自动恢复") — backup auto-resumes once conditions are met.
 */
@Composable
private fun PauseReasonBanner(pauseReason: PauseReason) {
    val isUserPause = pauseReason.isUserPause
    // User pause = neutral/grey; condition pause = amber warning.
    val accentColor = if (isUserPause) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        PhotoVaultColors.ArchiveAmber
    }
    val containerColor = if (isUserPause) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    } else {
        PhotoVaultColors.ArchiveAmber.copy(alpha = 0.15f)
    }
    val textColor = MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (pauseReason) {
                    is PauseReason.UserPaused -> Icons.Filled.Pause
                    is PauseReason.LowBattery -> Icons.Filled.BatteryAlert
                    is PauseReason.NoWifi -> Icons.Filled.WifiOff
                    is PauseReason.LowBatteryAndNoWifi -> Icons.Filled.Pause
                },
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "备份已暂停：${pauseReason.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pauseReason.resumeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Card showing the current file upload progress with speed and remaining time.
 */
@Composable
private fun CurrentUploadCard(upload: CurrentUploadState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        // The default Card container falls back to an auto-generated (purplish)
        // surfaceContainer tone because this theme doesn't define those tokens.
        // Pin it to the clean `surface` color, which is what this card's text and
        // progress-track colors are designed against, and let the elevation shadow
        // lift it off the near-white page as the focal "current upload" card.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // File name + live percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = upload.fileName.ifEmpty { "准备上传…" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${(upload.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Progress bar — LiquidSlider track look, without the draggable thumb.
            LiquidProgressBar(
                progress = upload.progress,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Speed · transferred / total · remaining time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatSpeed(upload.speedBytesPerSec),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "${formatSize(upload.uploadedBytes)} / ${formatSize(upload.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = formatRemainingTime(upload.remainingSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A single queued file item in the waiting list.
 */
@Composable
private fun QueuedFileItem(fileInfo: FileInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.HourglassEmpty,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileInfo.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSize(fileInfo.fileSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "等待中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A single AUTO_OFF paused-task card (需求 26/27/28).
 *
 * Shows the file name, upload progress percentage and the "已暂停 · 自动备份已关闭"
 * status line, with a "继续" button that manually resumes the upload (R-27.1).
 * Long-pressing the card (≥500ms via [combinedClickable]) opens the clear
 * options dialog (R-28.1). A plain tap does nothing so the card is not
 * accidentally activated.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PausedTaskItem(
    task: PausedTaskUi,
    onResume: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.PauseCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "已暂停 · 自动备份已关闭 · ${task.progressPercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Second line distinguishes this AUTO_OFF pause from USER
                // ("已手动暂停，点击开始继续") and CONDITION ("条件恢复后自动续传"):
                // it will NOT auto-resume and must be continued manually (R-30.1).
                Text(
                    text = "点击“继续”手动续传（不会自动续传）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            TextButton(onClick = onResume) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("继续")
            }
        }
    }
}

/**
 * Error card shown when the paused-task list fails to load (R-26.4). Offers a
 * retry that re-reads the persisted records; no records are modified.
 */
@Composable
private fun PausedTasksLoadErrorCard(onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = PhotoVaultColors.ArchiveAmber.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                tint = PhotoVaultColors.ArchiveAmber,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "已暂停任务读取失败",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("重试")
            }
        }
    }
}

/**
 * Empty state when there are no current tasks.
 */
@Composable
private fun EmptyCurrentTasksState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "没有正在进行的备份任务",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "新的图片将自动加入备份队列",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// ============================================================
// History View
// ============================================================

/**
 * Displays backup history grouped by date with status filtering.
 */
@Composable
private fun HistoryView(
    uiState: TasksTabUiState,
    onFilterChanged: (HistoryFilter) -> Unit,
    onRetry: (BackupHistoryRecord) -> Unit,
    onClearHistory: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips
        HistoryFilterBar(
            selectedFilter = uiState.historyFilter,
            onFilterChanged = onFilterChanged
        )

        // Clear-history action (only when there are records)
        if (!uiState.isHistoryLoading && uiState.historyGroups.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showClearDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空历史记录")
                }
            }
        }

        if (uiState.isHistoryLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.historyGroups.isEmpty()) {
            EmptyHistoryState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 8.dp + LocalBottomBarPadding.current
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                uiState.historyGroups.forEach { group ->
                    // Date header
                    item(key = "header_${group.date}") {
                        HistoryDateHeader(date = group.date)
                    }

                    // Records under this date
                    items(
                        items = group.records,
                        key = { it.id }
                    ) { record ->
                        HistoryRecordItem(
                            record = record,
                            onRetry = onRetry
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        LiquidGlassDialog(
            onDismissRequest = { showClearDialog = false },
            title = "清空历史记录",
            text = "确定要清空所有备份历史记录吗？此操作不可恢复，但不会影响已备份的文件。"
        ) {
            LiquidDialogButton(
                text = "取消",
                onClick = { showClearDialog = false },
                style = LiquidDialogButtonStyle.Neutral
            )
            LiquidDialogButton(
                text = "清空",
                onClick = {
                    showClearDialog = false
                    onClearHistory()
                },
                style = LiquidDialogButtonStyle.Destructive
            )
        }
    }
}

/**
 * Filter bar with chips for filtering history by status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterBar(
    selectedFilter: HistoryFilter,
    onFilterChanged: (HistoryFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedFilter == HistoryFilter.ALL,
            onClick = { onFilterChanged(HistoryFilter.ALL) },
            label = { Text("全部") }
        )
        FilterChip(
            selected = selectedFilter == HistoryFilter.SUCCESS,
            onClick = { onFilterChanged(HistoryFilter.SUCCESS) },
            label = { Text("成功") }
        )
        FilterChip(
            selected = selectedFilter == HistoryFilter.FAILED,
            onClick = { onFilterChanged(HistoryFilter.FAILED) },
            label = { Text("失败") }
        )
        FilterChip(
            selected = selectedFilter == HistoryFilter.SKIPPED,
            onClick = { onFilterChanged(HistoryFilter.SKIPPED) },
            label = { Text("跳过") }
        )
    }
}

/**
 * Date group header in the history list.
 */
@Composable
private fun HistoryDateHeader(date: String) {
    Text(
        text = date,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/**
 * A single history record item showing file info, status, and retry option for failures.
 */
@Composable
private fun HistoryRecordItem(
    record: BackupHistoryRecord,
    onRetry: (BackupHistoryRecord) -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = record.status == BackupStatus.FAILED ||
                    (record.status == BackupStatus.SKIPPED && record.errorMessage != null)
            ) {
                showDetails = !showDetails
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status icon
                StatusIcon(status = record.status)

                Spacer(modifier = Modifier.width(12.dp))

                // File info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatSize(record.fileSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatTime(record.completedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Status label
                StatusLabel(status = record.status)
            }

            // Expandable details for failed and skipped records
            AnimatedVisibility(
                visible = showDetails && (record.status == BackupStatus.FAILED || record.status == BackupStatus.SKIPPED),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    if (record.errorMessage != null) {
                        Text(
                            text = if (record.status == BackupStatus.SKIPPED) "跳过原因：${record.errorMessage}" else "失败原因：${record.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (record.status == BackupStatus.SKIPPED)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (record.status == BackupStatus.FAILED) {
                        Button(
                            onClick = { onRetry(record) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Status icon for a history record.
 */
@Composable
private fun StatusIcon(status: BackupStatus) {
    val (icon, tint) = when (status) {
        BackupStatus.SUCCESS -> Icons.Filled.CheckCircle to PhotoVaultColors.SyncGreen
        BackupStatus.FAILED -> Icons.Filled.Error to MaterialTheme.colorScheme.error
        BackupStatus.SKIPPED -> Icons.Filled.SkipNext to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(24.dp)
    )
}

/**
 * Status label text for a history record.
 */
@Composable
private fun StatusLabel(status: BackupStatus) {
    val (text, color) = when (status) {
        BackupStatus.SUCCESS -> "成功" to PhotoVaultColors.SyncGreen
        BackupStatus.FAILED -> "失败" to MaterialTheme.colorScheme.error
        BackupStatus.SKIPPED -> "跳过" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.Medium
    )
}

/**
 * Empty state for history when no records exist.
 */
@Composable
private fun EmptyHistoryState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.HourglassEmpty,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "暂无备份记录",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================
// Utility functions
// ============================================================

/**
 * Format bytes per second to a human-readable speed string.
 */
private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec <= 0 -> "计算中..."
        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}

/**
 * Format file size to a human-readable string.
 */
private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * Format remaining seconds to a human-readable time string.
 */
private fun formatRemainingTime(seconds: Long): String {
    return when {
        seconds <= 0 -> "即将完成"
        seconds < 60 -> "剩余 ${seconds}秒"
        seconds < 3600 -> "剩余 ${seconds / 60}分${seconds % 60}秒"
        else -> "剩余 ${seconds / 3600}小时${(seconds % 3600) / 60}分"
    }
}

/**
 * Format timestamp to a time string (HH:mm).
 */
private fun formatTime(timestamp: Long): String {
    val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return format.format(java.util.Date(timestamp))
}
