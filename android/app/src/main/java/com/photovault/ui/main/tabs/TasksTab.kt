package com.photovault.ui.main.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.photovault.data.local.entity.BackupHistoryRecord
import com.photovault.data.local.entity.BackupStatus
import com.photovault.ui.theme.LocalBottomBarPadding
import com.photovault.ui.theme.PhotoVaultColors
import com.photovault.service.FileInfo

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

    Column(modifier = Modifier.fillMaxSize()) {
        // Segmented control (TabRow)
        SegmentedControl(
            selectedSegment = uiState.selectedSegment,
            onSegmentSelected = { viewModel.selectSegment(it) }
        )

        // Content based on selected segment
        when (uiState.selectedSegment) {
            TasksSegment.CURRENT_TASKS -> CurrentTasksView(uiState = uiState)
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
private fun CurrentTasksView(uiState: TasksTabUiState) {
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
        // Pause reason banner
        if (uiState.isPaused && uiState.pauseReason != null) {
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
        if (!uiState.isUploading && !uiState.isPaused && uiState.queuedFiles.isEmpty()) {
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
}

/**
 * Banner showing why backup is paused and when it will resume.
 */
@Composable
private fun PauseReasonBanner(pauseReason: PauseReason) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
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
                    is PauseReason.LowBattery -> Icons.Filled.BatteryAlert
                    is PauseReason.NoWifi -> Icons.Filled.WifiOff
                    is PauseReason.LowBatteryAndNoWifi -> Icons.Filled.Pause
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "备份已暂停：${pauseReason.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pauseReason.resumeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
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
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // File name
            Text(
                text = upload.fileName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { upload.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Speed and remaining time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatSpeed(upload.speedBytesPerSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空历史记录") },
            text = { Text("确定要清空所有备份历史记录吗？此操作不可恢复，但不会影响已备份的文件。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClearHistory()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
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
