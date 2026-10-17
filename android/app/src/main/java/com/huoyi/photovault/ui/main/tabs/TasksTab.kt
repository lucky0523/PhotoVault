package com.huoyi.photovault.ui.main.tabs

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterListOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.huoyi.photovault.R
import com.huoyi.photovault.data.local.entity.BackupHistoryRecord
import com.huoyi.photovault.data.local.entity.BackupStatus
import com.huoyi.photovault.ui.theme.LiquidDialogButton
import com.huoyi.photovault.ui.theme.LiquidDialogButtonStyle
import com.huoyi.photovault.ui.theme.LiquidGlassDialog
import com.huoyi.photovault.ui.theme.liquid.LiquidProgressBar
import com.huoyi.photovault.ui.theme.LocalBottomBarPadding
import com.huoyi.photovault.ui.theme.SemanticTone
import com.huoyi.photovault.ui.theme.photoVaultFocusRing
import com.huoyi.photovault.ui.theme.semanticColorSet
import com.huoyi.photovault.ui.theme.statusSoftFill
import com.huoyi.photovault.service.FileInfo
import kotlin.math.roundToInt

/**
 * 备份任务 Tab - 显示当前正在进行的备份任务、排队中的任务以及历史备份记录。
 *
 * 包含：
 * - 胶囊分段控制器（当前任务 / 历史记录）
 * - 当前任务视图：进度卡片 + 速度 + 剩余时间 + 排队列表 + 暂停原因提示
 * - 历史记录视图：按日期分组的卡片，状态筛选（带数量），失败记录可展开重试
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
 * Capsule segmented control: a faint brand-tinted track with a primary-filled
 * thumb that slides under the selected segment. Matches the pill language of
 * the floating bottom bar and the filter pills (no grey TabRow container).
 */
@Composable
private fun SegmentedControl(
    selectedSegment: TasksSegment,
    onSegmentSelected: (TasksSegment) -> Unit
) {
    val segments = listOf(
        TasksSegment.CURRENT_TASKS to "当前任务",
        TasksSegment.HISTORY to "历史记录"
    )
    val primary = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(44.dp)
            // background(shape) instead of clip so segment focus halos aren't cut off.
            .background(statusSoftFill(primary), CircleShape)
            .padding(4.dp)
    ) {
        val thumbWidth = maxWidth / segments.size
        val thumbOffset by animateDpAsState(
            targetValue = thumbWidth * selectedSegment.ordinal,
            label = "tasksSegmentThumb"
        )
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(thumbWidth)
                .fillMaxHeight()
                .background(primary, CircleShape)
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup()
        ) {
            segments.forEach { (segment, label) ->
                val selected = segment == selectedSegment
                val textColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "tasksSegmentText"
                )
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .photoVaultFocusRing(CircleShape, interactionSource)
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            role = Role.Tab,
                            onClick = { onSegmentSelected(segment) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ============================================================
// Shared building blocks
// ============================================================

/**
 * Position of a row inside a grouped (one rounded card per section) list.
 * Decides which corners of the row are rounded.
 */
internal enum class GroupPosition { Single, First, Middle, Last }

/** Pure mapping from a row's index within a group of [count] rows to its [GroupPosition]. */
internal fun groupPosition(index: Int, count: Int): GroupPosition = when {
    count <= 1 -> GroupPosition.Single
    index == 0 -> GroupPosition.First
    index == count - 1 -> GroupPosition.Last
    else -> GroupPosition.Middle
}

/** Title of the history empty state for a non-ALL [filter]; null for ALL (keeps "暂无备份记录"). */
@StringRes
internal fun historyFilteredEmptyTitleRes(filter: HistoryFilter): Int? = when (filter) {
    HistoryFilter.ALL -> null
    HistoryFilter.SUCCESS -> R.string.tasks_history_empty_success
    HistoryFilter.FAILED -> R.string.tasks_history_empty_failed
    HistoryFilter.SKIPPED -> R.string.tasks_history_empty_skipped
}

private fun GroupPosition.shape(radius: Dp = 16.dp): RoundedCornerShape = when (this) {
    GroupPosition.Single -> RoundedCornerShape(radius)
    GroupPosition.First -> RoundedCornerShape(topStart = radius, topEnd = radius)
    GroupPosition.Middle -> RoundedCornerShape(0.dp)
    GroupPosition.Last -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
}

/** Start inset of row dividers: aligns with the text column (14dp + 40dp icon + 14dp). */
private val RowTextInset = 68.dp

/**
 * One row of a grouped section card. Paints the shared group background clipped
 * to the row's [position] shape and an inset divider above non-first rows.
 *
 * The focus ring is applied before the clip so its outer halo stays visible;
 * [interactionModifier] (clickable/focusable) is applied after the clip so the
 * ripple follows the rounded shape.
 */
@Composable
private fun GroupedRow(
    position: GroupPosition,
    modifier: Modifier = Modifier,
    focusInteractionSource: InteractionSource? = null,
    interactionModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = position.shape()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (focusInteractionSource != null) {
                    Modifier.photoVaultFocusRing(shape, focusInteractionSource)
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .then(interactionModifier)
    ) {
        if (position == GroupPosition.Middle || position == GroupPosition.Last) {
            HorizontalDivider(
                modifier = Modifier.padding(start = RowTextInset),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
        content()
    }
}

/** Section title on the left with an optional muted count/caption on the right. */
@Composable
private fun SectionHeader(
    title: String,
    trailing: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 40dp rounded leading container holding a tinted icon. */
@Composable
private fun LeadingIconBox(
    icon: ImageVector,
    tint: Color,
    container: Color,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = iconModifier.size(22.dp)
        )
    }
}

/** Small capsule badge (status label / 等待中). */
@Composable
private fun Badge(
    text: String,
    container: Color,
    foreground: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier
            .background(container, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = foreground,
        maxLines = 1
    )
}

/** Shared empty state: brand-tinted circle with an icon, a title and a hint. */
@Composable
private fun TasksEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(statusSoftFill(primary), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Spinner centered in the area above the floating bottom bar. */
@Composable
private fun CenteredLoading() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = LocalBottomBarPadding.current),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
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

    // Show a spinner until the first load completes (queue restored + refreshed),
    // so the queue/empty/pause content doesn't flash in from the empty default.
    if (uiState.isCurrentTasksLoading) {
        CenteredLoading()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 16.dp + LocalBottomBarPadding.current
        )
    ) {
        // Manual start/pause control (R-24.1/24.4). A user pause is surfaced as a
        // status line on this button (no separate banner) — see statusText.
        item {
            StartPauseButton(
                showingPause = uiState.isStartPauseShowingPause,
                enabled = uiState.isStartPauseEnabled,
                isManualRun = uiState.isManualRun,
                statusText = if (uiState.isPaused && uiState.pauseReason?.isUserPause == true) {
                    stringResource(uiState.pauseReason.messageRes)
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
                PausedTasksLoadErrorCard(
                    onRetry = onRetryLoadPausedTasks,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        } else if (uiState.pausedTasks.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "已暂停（自动备份已关闭）",
                    trailing = uiState.pausedTasks.size.toString(),
                    modifier = Modifier.padding(top = 20.dp)
                )
            }

            itemsIndexed(
                items = uiState.pausedTasks,
                key = { _, task -> task.fileUri }
            ) { index, task ->
                PausedTaskItem(
                    task = task,
                    onResume = { onResumePausedTask(task.fileUri) },
                    onLongPress = { longPressedFileUri = task.fileUri },
                    position = groupPosition(index, uiState.pausedTasks.size)
                )
            }
        }

        // Pause reason banner — only for CONDITION pauses (电量/WiFi). A user
        // pause no longer gets its own banner; it is shown on the 开始/暂停 button.
        if (uiState.isPaused && uiState.pauseReason != null && !uiState.pauseReason.isUserPause) {
            item {
                PauseReasonBanner(
                    pauseReason = uiState.pauseReason,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }

        // Current upload progress
        if (uiState.isUploading) {
            item {
                CurrentUploadCard(
                    upload = uiState.currentUpload,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }

        // Empty state when nothing is happening
        if (!uiState.isUploading && !uiState.isPaused && uiState.queuedFiles.isEmpty() &&
            uiState.pausedTasks.isEmpty() && !uiState.pausedTasksLoadError
        ) {
            item {
                TasksEmptyState(
                    icon = Icons.Rounded.CloudDone,
                    title = "没有正在进行的备份任务",
                    subtitle = "新的图片将自动加入备份队列",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp)
                )
            }
        }

        // Queue section
        if (uiState.queuedFiles.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "排队中",
                    trailing = uiState.queueSize.toString(),
                    modifier = Modifier.padding(top = 20.dp)
                )
            }

            itemsIndexed(
                items = uiState.queuedFiles,
                key = { _, fileInfo -> fileInfo.uri }
            ) { index, fileInfo ->
                QueuedFileItem(
                    fileInfo = fileInfo,
                    position = groupPosition(index, uiState.queuedFiles.size)
                )
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
        shape = CircleShape
    ) {
        Icon(
            imageVector = if (showingPause) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
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
            Text(label, fontWeight = FontWeight.Medium)
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
internal fun PauseReasonBanner(
    pauseReason: PauseReason,
    modifier: Modifier = Modifier
) {
    val isUserPause = pauseReason.isUserPause
    // User pause = neutral/grey; condition pause = amber warning.
    val accentColor = if (isUserPause) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        semanticColorSet(SemanticTone.Warning).accent
    }
    val containerColor = if (isUserPause) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    } else {
        semanticColorSet(SemanticTone.Warning).container
    }
    val textColor = if (isUserPause) {
        MaterialTheme.colorScheme.onSurface
    } else {
        semanticColorSet(SemanticTone.Warning).foreground
    }

    Card(
        modifier = modifier.fillMaxWidth().testTag("tasks_pause_reason"),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (pauseReason) {
                    is PauseReason.UserPaused -> Icons.Rounded.Pause
                    is PauseReason.LowBattery -> Icons.Rounded.BatteryAlert
                    is PauseReason.NoWifi -> Icons.Rounded.WifiOff
                    is PauseReason.Waiting -> Icons.Rounded.HourglassEmpty
                    is PauseReason.LowBatteryAndNoWifi -> Icons.Rounded.Pause
                },
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(28.dp).testTag("tasks_pause_reason_icon")
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.backup_paused_reason_format,
                        stringResource(pauseReason.messageRes)
                    ),
                    modifier = Modifier.testTag("tasks_pause_reason_title"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(pauseReason.resumeHintRes),
                    modifier = Modifier.testTag("tasks_pause_reason_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUserPause) textColor.copy(alpha = 0.7f) else textColor
                )
            }
        }
    }
}

/**
 * Card showing the current file upload progress with speed and remaining time.
 */
@Composable
private fun CurrentUploadCard(
    upload: CurrentUploadState,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
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
                .padding(16.dp)
        ) {
            // Icon · file name + caption · live percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(statusSoftFill(primary), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudUpload,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = upload.fileName.ifEmpty { "准备上传…" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.tasks_uploading_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${(upload.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = primary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Progress bar — LiquidSlider track look, without the draggable thumb.
            LiquidProgressBar(
                progress = upload.progress,
                modifier = Modifier.fillMaxWidth(),
                trackHeight = 8.dp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Speed · transferred / total · remaining time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                UploadMetaItem(
                    icon = Icons.Rounded.Speed,
                    text = formatSpeed(upload.speedBytesPerSec),
                    color = primary
                )
                UploadMetaItem(
                    icon = Icons.Rounded.DataUsage,
                    text = "${formatSize(upload.uploadedBytes)} / ${formatSize(upload.totalBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                UploadMetaItem(
                    icon = Icons.Rounded.Schedule,
                    text = formatRemainingTime(upload.remainingSeconds),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Small icon + text pair used in the upload card's meta row. */
@Composable
private fun UploadMetaItem(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1
        )
    }
}

/**
 * A single queued file row in the waiting list.
 */
@Composable
private fun QueuedFileItem(
    fileInfo: FileInfo,
    position: GroupPosition
) {
    GroupedRow(position = position) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LeadingIconBox(
                icon = Icons.Rounded.HourglassEmpty,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileInfo.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSize(fileInfo.fileSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Badge(
                text = "等待中",
                container = MaterialTheme.colorScheme.surfaceVariant,
                foreground = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A single AUTO_OFF paused-task row (需求 26/27/28).
 *
 * Shows the file name, upload progress percentage and the "已暂停 · 自动备份已关闭"
 * status line, with a "继续" button that manually resumes the upload (R-27.1).
 * Long-pressing the row (≥500ms via [combinedClickable]) opens the clear
 * options dialog (R-28.1). A plain tap does nothing so the row is not
 * accidentally activated.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PausedTaskItem(
    task: PausedTaskUi,
    onResume: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    position: GroupPosition = GroupPosition.Single
) {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    GroupedRow(
        position = position,
        modifier = modifier.testTag("tasks_paused_item"),
        focusInteractionSource = interactionSource,
        interactionModifier = Modifier
            .combinedClickable(
                interactionSource = interactionSource,
                indication = indication,
                onClick = {},
                onLongClick = onLongPress
            )
            .focusable(interactionSource = interactionSource)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LeadingIconBox(
                icon = Icons.Rounded.Pause,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
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
                Spacer(modifier = Modifier.height(6.dp))
                LiquidProgressBar(
                    progress = task.progressPercent / 100f,
                    trackHeight = 4.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            TextButton(onClick = onResume) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
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
internal fun PausedTasksLoadErrorCard(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("tasks_load_error"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = semanticColorSet(SemanticTone.Warning).container
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = semanticColorSet(SemanticTone.Warning).accent,
                modifier = Modifier.size(24.dp).testTag("tasks_load_error_icon")
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.error_paused_tasks_load),
                style = MaterialTheme.typography.bodyMedium,
                color = semanticColorSet(SemanticTone.Warning).foreground,
                modifier = Modifier.weight(1f).testTag("tasks_load_error_message")
            )
            TextButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_retry))
            }
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
        // Filter pills (with counts) + inline clear-history action
        HistoryFilterBar(
            selectedFilter = uiState.historyFilter,
            counts = uiState.historyCounts,
            onFilterChanged = onFilterChanged,
            onClearHistory = { showClearDialog = true }
        )

        if (uiState.isHistoryLoading) {
            CenteredLoading()
        } else if (uiState.historyGroups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = LocalBottomBarPadding.current),
                contentAlignment = Alignment.Center
            ) {
                val filteredTitleRes = historyFilteredEmptyTitleRes(uiState.historyFilter)
                if (filteredTitleRes == null) {
                    TasksEmptyState(
                        icon = Icons.Rounded.History,
                        title = "暂无备份记录",
                        subtitle = stringResource(R.string.tasks_history_empty_hint)
                    )
                } else {
                    TasksEmptyState(
                        icon = Icons.Rounded.FilterListOff,
                        title = stringResource(filteredTitleRes),
                        subtitle = stringResource(R.string.tasks_history_empty_filtered)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 16.dp + LocalBottomBarPadding.current
                )
            ) {
                uiState.historyGroups.forEachIndexed { groupIndex, group ->
                    // Date header with per-day count
                    item(key = "header_${group.date}") {
                        SectionHeader(
                            title = group.date,
                            trailing = pluralStringResource(
                                R.plurals.tasks_history_day_count,
                                group.records.size,
                                group.records.size
                            ),
                            modifier = Modifier.padding(top = if (groupIndex == 0) 4.dp else 20.dp)
                        )
                    }

                    // Records under this date, drawn as one grouped card
                    itemsIndexed(
                        items = group.records,
                        key = { _, record -> record.id }
                    ) { index, record ->
                        HistoryRecordItem(
                            record = record,
                            position = groupPosition(index, group.records.size),
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

/** Shape shared by the history filter tiles and the clear-history tile. */
private val FilterTileShape = RoundedCornerShape(16.dp)

/**
 * Header row of the history view: four equal-width status tiles (label over
 * count) and a trailing clear-history tile. Everything fits in one row
 * without horizontal scrolling, so no tile is ever clipped off-screen.
 */
@Composable
private fun HistoryFilterBar(
    selectedFilter: HistoryFilter,
    counts: HistoryCounts,
    onFilterChanged: (HistoryFilter) -> Unit,
    onClearHistory: () -> Unit
) {
    val success = semanticColorSet(SemanticTone.Success).accent
    val danger = semanticColorSet(SemanticTone.Danger).accent
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HistoryFilterPill(
                label = "全部",
                count = counts.all,
                dotColor = null,
                selected = selectedFilter == HistoryFilter.ALL,
                onClick = { onFilterChanged(HistoryFilter.ALL) },
                modifier = Modifier.weight(1f)
            )
            HistoryFilterPill(
                label = "成功",
                count = counts.success,
                dotColor = success,
                selected = selectedFilter == HistoryFilter.SUCCESS,
                onClick = { onFilterChanged(HistoryFilter.SUCCESS) },
                modifier = Modifier.weight(1f)
            )
            HistoryFilterPill(
                label = stringResource(R.string.backup_status_failed),
                count = counts.failed,
                dotColor = danger,
                selected = selectedFilter == HistoryFilter.FAILED,
                onClick = { onFilterChanged(HistoryFilter.FAILED) },
                modifier = Modifier.weight(1f)
            )
            HistoryFilterPill(
                label = "跳过",
                count = counts.skipped,
                dotColor = neutral,
                selected = selectedFilter == HistoryFilter.SKIPPED,
                onClick = { onFilterChanged(HistoryFilter.SKIPPED) },
                modifier = Modifier.weight(1f)
            )
        }

        // Clears ALL records (not just the filtered ones), so it is enabled
        // whenever any history exists. Dimmed instead of hidden so the row
        // doesn't jump.
        val clearEnabled = counts.all > 0
        IconButton(
            onClick = onClearHistory,
            enabled = clearEnabled,
            shape = FilterTileShape,
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight()
                .alpha(if (clearEnabled) 1f else 0.38f)
                .testTag("tasks_history_clear"),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = statusSoftFill(danger),
                contentColor = danger,
                disabledContainerColor = statusSoftFill(danger),
                disabledContentColor = danger
            )
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteSweep,
                contentDescription = "清空历史记录",
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Brand-accent filter tile: status label (with a small status dot) over its
 * record count. Selected = primary fill; unselected = faint primary tint.
 * The four tiles stay equal-width in one non-scrolling row. At large font
 * scales the status dot is dropped to give the label its width back, and the
 * label/count ellipsize ("…") rather than being clipped mid-glyph.
 */
@Composable
private fun HistoryFilterPill(
    label: String,
    count: Int,
    dotColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val container = if (selected) primary else statusSoftFill(primary)
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = 52.dp)
            .photoVaultFocusRing(FilterTileShape, interactionSource)
            .clip(FilterTileShape)
            .background(container)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // The dot is decorative (selection is conveyed by the fill); drop it at
        // large font scales so the label keeps the tile's full width.
        val showDot = LocalDensity.current.fontScale < 1.3f
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dotColor != null && showDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (selected) contentColor else dotColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = 0.75f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A single history row: status icon, file info, status badge, and (for
 * failed/skipped records) the reason. Failed rows expand to offer a retry.
 */
@Composable
private fun HistoryRecordItem(
    record: BackupHistoryRecord,
    position: GroupPosition,
    onRetry: (BackupHistoryRecord) -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }

    val enabled = record.status == BackupStatus.FAILED ||
        (record.status == BackupStatus.SKIPPED && record.errorMessage != null)
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current

    val reason = if (record.status != BackupStatus.SUCCESS && record.errorMessage != null) {
        stringResource(
            if (record.status == BackupStatus.SKIPPED) {
                R.string.backup_skip_reason_format
            } else {
                R.string.backup_failure_reason_format
            },
            record.errorMessage
        )
    } else {
        null
    }

    GroupedRow(
        position = position,
        focusInteractionSource = interactionSource,
        interactionModifier = Modifier
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = indication
            ) {
                showDetails = !showDetails
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIcon(status = record.status)

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .animateContentSize()
            ) {
                Text(
                    text = record.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatSize(record.fileSize)} · ${formatTime(record.completedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (reason != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        // Danger foreground (not the vivid accent) keeps small
                        // text legible on the light group background.
                        color = if (record.status == BackupStatus.SKIPPED) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            semanticColorSet(SemanticTone.Danger).foreground
                        },
                        maxLines = if (showDetails) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            StatusLabel(status = record.status)
        }

        // Expanded: retry action for failed records
        AnimatedVisibility(
            visible = showDetails && record.status == BackupStatus.FAILED,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            FilledTonalButton(
                onClick = { onRetry(record) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = RowTextInset, end = 14.dp, bottom = 12.dp),
                shape = CircleShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = statusSoftFill(MaterialTheme.colorScheme.primary),
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/**
 * Status icon for a history record: a tinted rounded container holding the
 * status glyph (success = green check, failed = red error, skipped = neutral).
 */
@Composable
internal fun StatusIcon(
    status: BackupStatus,
    modifier: Modifier = Modifier
) {
    when (status) {
        BackupStatus.SUCCESS -> {
            val colors = semanticColorSet(SemanticTone.Success)
            LeadingIconBox(
                icon = Icons.Rounded.Check,
                tint = colors.accent,
                container = colors.container,
                modifier = modifier,
                iconModifier = Modifier.testTag("tasks_history_status_icon")
            )
        }
        BackupStatus.FAILED -> {
            val colors = semanticColorSet(SemanticTone.Danger)
            LeadingIconBox(
                icon = Icons.Rounded.ErrorOutline,
                tint = colors.accent,
                container = colors.container,
                modifier = modifier,
                iconModifier = Modifier.testTag("tasks_history_status_icon")
            )
        }
        BackupStatus.SKIPPED -> LeadingIconBox(
            icon = Icons.Rounded.SkipNext,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = modifier,
            iconModifier = Modifier.testTag("tasks_history_status_icon")
        )
    }
}

/**
 * Status badge for a history record: a small capsule in the status container
 * color with the status foreground text.
 */
@Composable
internal fun StatusLabel(
    status: BackupStatus,
    modifier: Modifier = Modifier
) {
    val text: String
    val container: Color
    val foreground: Color
    when (status) {
        BackupStatus.SUCCESS -> {
            val colors = semanticColorSet(SemanticTone.Success)
            text = stringResource(R.string.backup_status_success)
            container = colors.container
            foreground = colors.foreground
        }
        BackupStatus.FAILED -> {
            val colors = semanticColorSet(SemanticTone.Danger)
            text = stringResource(R.string.backup_status_failed)
            container = colors.container
            foreground = colors.foreground
        }
        BackupStatus.SKIPPED -> {
            text = stringResource(R.string.backup_status_skipped)
            container = MaterialTheme.colorScheme.surfaceVariant
            foreground = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Badge(
        text = text,
        container = container,
        foreground = foreground,
        modifier = modifier.testTag("tasks_history_status_label")
    )
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
