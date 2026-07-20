package com.photovault.ui.main.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.photovault.data.local.SettingsPreferences
import com.photovault.util.BatteryOptimizationHelper
import com.photovault.data.local.entity.BackupFolder
import com.photovault.data.network.ConnectionState
import com.photovault.data.network.ConnectionType
import com.photovault.ui.main.components.StoragePolicySheet
import com.photovault.ui.theme.LiquidDialogButton
import com.photovault.ui.theme.LiquidDialogButtonStyle
import com.photovault.ui.theme.LiquidGlassDialog
import com.photovault.ui.theme.LocalBottomBarPadding
import com.photovault.ui.theme.LocalGlassBackdrop
import com.photovault.ui.theme.PhotoVaultColors
import com.photovault.ui.theme.liquid.LiquidSlider
import com.photovault.ui.theme.liquid.LiquidToggle

/**
 * 设置 Tab - 显示应用设置选项，包括备份条件配置、存储策略管理、账户信息和退出登录。
 *
 * Implements:
 * - 备份条件 (Backup Conditions) group:
 *   - WiFi switch (always ON, cannot be turned off)
 *   - Minimum battery level slider (20%-80%, default 50%)
 *   - Scan interval selector (5/15/30/60 minutes, default 15)
 * - 存储策略管理 (Storage Strategy Management) group:
 *   - List of configured backup folders with policy summaries
 *   - Click on any folder to modify its storage strategy via BottomSheet
 *   - Empty state hint when no folders are configured
 */
@Composable
fun SettingsTab(
    onLogout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()
    val minBatteryLevel by viewModel.minBatteryLevel.collectAsState()
    val scanIntervalMinutes by viewModel.scanIntervalMinutes.collectAsState()
    val fileLoggingEnabled by viewModel.fileLoggingEnabled.collectAsState()
    val diagnosticLogHasContent by viewModel.diagnosticLogHasContent.collectAsState()
    val backupFolders by viewModel.backupFolders.collectAsState()
    val showPolicySheet by viewModel.showPolicySheet.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    val username by viewModel.username.collectAsState()
    val serverAddress by viewModel.serverAddress.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshDiagnosticLogStatus()
    }

    var showLogoutDialog by remember { mutableStateOf(false) }
    // While the user is operating an interactive control (dragging the battery
    // slider or the WiFi switch), block the page's vertical scroll so the gesture
    // doesn't also pan the whole settings list.
    var isBatterySliderDragging by remember { mutableStateOf(false) }
    var isWifiToggleInteracting by remember { mutableStateOf(false) }
    var isAutoBackupToggleInteracting by remember { mutableStateOf(false) }
    val blockPageScroll =
        isBatterySliderDragging || isWifiToggleInteracting || isAutoBackupToggleInteracting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState(),
                enabled = !blockPageScroll
            )
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + LocalBottomBarPadding.current
            )
    ) {
        // 账户信息 group
        AccountInfoGroup(
            username = username,
            serverAddress = serverAddress,
            connectionState = connectionState
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 存储策略管理 group
        StorageStrategyManagementGroup(
            folders = backupFolders,
            onFolderClick = { folder -> viewModel.showPolicyForFolder(folder) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 备份条件 group
        BackupConditionsGroup(
            autoBackupEnabled = autoBackupEnabled,
            wifiOnly = wifiOnly,
            minBatteryLevel = minBatteryLevel,
            scanIntervalMinutes = scanIntervalMinutes,
            onAutoBackupChanged = { viewModel.setAutoBackupEnabled(it) },
            onWifiOnlyChanged = { viewModel.setWifiOnly(it) },
            onBatteryLevelChanged = { viewModel.setMinBatteryLevel(it) },
            onScanIntervalChanged = { viewModel.setScanInterval(it) },
            onBatterySliderDraggingChange = { isBatterySliderDragging = it },
            onWifiToggleInteractingChange = { isWifiToggleInteracting = it },
            onAutoBackupToggleInteractingChange = { isAutoBackupToggleInteracting = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 后台运行 group（电池优化白名单）
        BackgroundRunGroup()

        Spacer(modifier = Modifier.height(16.dp))

        // 诊断 group（把日志写入文件，绕过部分 ROM 屏蔽 logcat）
        DiagnosticsGroup(
            fileLoggingEnabled = fileLoggingEnabled,
            diagnosticLogPath = viewModel.diagnosticLogPath,
            diagnosticLogHasContent = diagnosticLogHasContent,
            onFileLoggingChanged = { viewModel.setFileLoggingEnabled(it) },
            onClearLogs = viewModel::clearDiagnosticLogs
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 退出登录按钮（红色文字）
        LogoutButton(onClick = { showLogoutDialog = true })
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            onConfirm = {
                showLogoutDialog = false
                viewModel.logout()
                onLogout()
            },
            onDismiss = { showLogoutDialog = false }
        )
    }

    // Storage Policy BottomSheet
    if (showPolicySheet) {
        StoragePolicySheet(
            folder = selectedFolder,
            isNewFolder = false,
            onDismiss = { viewModel.dismissPolicySheet() },
            onSave = { useCustomPath, customPath, useYearMonthLayer ->
                selectedFolder?.let { folder ->
                    viewModel.updateFolderPolicy(
                        folderId = folder.id,
                        useCustomPath = useCustomPath,
                        customPath = customPath,
                        useYearMonthLayer = useYearMonthLayer
                    )
                }
            }
        )
    }
}

/**
 * 备份条件 (Backup Conditions) settings group.
 *
 * Contains:
 * - WiFi switch: Always ON, disabled (cannot be turned off)
 * - Minimum battery slider: Range 20%-80%, default 50%
 * - Scan interval radio group: Options 5/15/30/60 minutes
 */
@Composable
private fun BackupConditionsGroup(
    autoBackupEnabled: Boolean,
    wifiOnly: Boolean,
    minBatteryLevel: Int,
    scanIntervalMinutes: Int,
    onAutoBackupChanged: (Boolean) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onBatteryLevelChanged: (Int) -> Unit,
    onScanIntervalChanged: (Int) -> Unit,
    onBatterySliderDraggingChange: (Boolean) -> Unit,
    onWifiToggleInteractingChange: (Boolean) -> Unit,
    onAutoBackupToggleInteractingChange: (Boolean) -> Unit
) {
    SettingsGroupCard(title = "备份条件") {
        // 自动备份开关
        AutoBackupSetting(
            enabled = autoBackupEnabled,
            onEnabledChange = onAutoBackupChanged,
            onInteractingChange = onAutoBackupToggleInteractingChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        // WiFi 开关
        WifiOnlySetting(
            enabled = wifiOnly,
            onEnabledChange = onWifiOnlyChanged,
            onInteractingChange = onWifiToggleInteractingChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 最低电量滑块
        MinBatteryLevelSetting(
            currentLevel = minBatteryLevel,
            onLevelChanged = onBatteryLevelChanged,
            onDraggingChange = onBatterySliderDraggingChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 扫描间隔选择
        ScanIntervalSetting(
            currentInterval = scanIntervalMinutes,
            onIntervalChanged = onScanIntervalChanged
        )
    }
}

/**
 * 诊断 (Diagnostics) settings group.
 *
 * A debug toggle that writes backup/scan/resume diagnostics to a file, so issues
 * can be traced on ROMs that suppress third-party logcat output. Reads via:
 *   adb pull /sdcard/Android/data/com.photovault/files/diagnostics.log
 */
@Composable
private fun DiagnosticsGroup(
    fileLoggingEnabled: Boolean,
    diagnosticLogPath: String?,
    diagnosticLogHasContent: Boolean,
    onFileLoggingChanged: (Boolean) -> Unit,
    onClearLogs: () -> Unit
) {
    SettingsGroupCard(title = "诊断") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "记录日志到文件",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = if (fileLoggingEnabled) {
                        "已开启，备份/扫描/恢复诊断信息写入日志文件"
                    } else {
                        "开启后记录后台备份诊断信息，用于排查问题"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2
                )
                Text(
                    text = "日志路径：${diagnosticLogPath ?: "尚未初始化"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            val glassBackdrop = LocalGlassBackdrop.current
            if (glassBackdrop != null) {
                LiquidToggle(
                    selected = { fileLoggingEnabled },
                    onSelect = onFileLoggingChanged,
                    backdrop = glassBackdrop
                )
            } else {
                Switch(
                    checked = fileLoggingEnabled,
                    onCheckedChange = onFileLoggingChanged
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onClearLogs,
                enabled = diagnosticLogHasContent
            ) {
                Text("清空日志")
            }
        }
    }
}

/**
 * 后台运行 (Background execution) settings group.
 *
 * Surfaces the battery-optimization (Doze) whitelist status. Aggressive OEM ROMs
 * (ColorOS/OxygenOS/MIUI) defer or kill background scans, the MediaStore observer, and
 * condition-recovery unless the app is exempt from battery optimization, which stalls
 * automatic backup. This row lets the user grant the exemption in one tap and reflects the
 * current state, re-checking whenever the screen returns to the foreground.
 */
@Composable
private fun BackgroundRunGroup() {
    val context = LocalContext.current
    var ignoringOptimizations by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }
    // The user grants the exemption in a system screen, so re-check on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ignoringOptimizations = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
    }

    SettingsGroupCard(title = "后台运行") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (ignoringOptimizations) Modifier
                    else Modifier.clickable {
                        // Fall back to the generic list if the direct request dialog
                        // isn't available on this ROM.
                        if (!BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)) {
                            BatteryOptimizationHelper.openBatteryOptimizationSettings(context)
                        }
                    }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "允许后台运行",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = if (ignoringOptimizations) {
                        "已允许，后台可稳定扫描并备份新照片"
                    } else {
                        "受限，系统可能会推迟或停止后台备份，点击前往关闭电池优化"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (ignoringOptimizations) {
                Text(
                    text = "已允许",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PhotoVaultColors.SyncGreen
                )
            } else {
                Text(
                    text = "去设置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 存储策略管理 (Storage Strategy Management) settings group.
 *
 * Displays a list of configured backup folders with their current policy summary.
 * Each folder is clickable to open the strategy configuration BottomSheet.
 * If no folders are configured, shows a hint message.
 */
@Composable
private fun StorageStrategyManagementGroup(
    folders: List<BackupFolder>,
    onFolderClick: (BackupFolder) -> Unit
) {
    SettingsGroupCard(title = "存储策略管理") {
        if (folders.isEmpty()) {
            // Empty state hint
            Text(
                text = "暂无已配置的备份文件夹",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "请在本地 Tab 中添加备份文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            folders.forEachIndexed { index, folder ->
                StorageStrategyFolderItem(
                    folder = folder,
                    onClick = { onFolderClick(folder) }
                )
                if (index < folders.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

/**
 * A single folder item in the storage strategy management list.
 * Shows folder name, current policy summary, and a chevron icon.
 */
@Composable
private fun StorageStrategyFolderItem(
    folder: BackupFolder,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = folder.folderName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildPolicySummary(folder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = "修改策略",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Build a human-readable summary of the folder's current storage policy.
 */
private fun buildPolicySummary(folder: BackupFolder): String {
    val pathPart = if (folder.useCustomPath) {
        "手动指定路径"
    } else {
        "默认路径"
    }

    val layerPart = if (folder.useYearMonthLayer) {
        "按年月分层"
    } else {
        "不分层"
    }

    return "$pathPart + $layerPart"
}

/**
 * 账户信息 (Account Information) settings group.
 *
 * Displays read-only information about the current user account:
 * - 当前用户名 (current username)
 * - 服务器地址 (server address)
 * - 连接状态 (connection status: "已连接 (局域网)" / "已连接 (公网)" / "未连接")
 */
@Composable
private fun AccountInfoGroup(
    username: String,
    serverAddress: String,
    connectionState: ConnectionState
) {
    SettingsGroupCard(title = "账户信息") {
        AccountInfoItem(
            label = "当前用户名",
            value = username.ifEmpty { "未登录" }
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        AccountInfoItem(
            label = "服务器地址",
            value = serverAddress.ifEmpty { "未配置" }
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        ConnectionStatusItem(connectionState = connectionState)
    }
}

/**
 * Connection-status row with a colored status dot:
 * - 绿色: 已连接（局域网/公网）
 * - 橙色: 连接中
 * - 灰色: 未连接
 */
@Composable
private fun ConnectionStatusItem(connectionState: ConnectionState) {
    val dotColor = when (connectionState) {
        is ConnectionState.Connected -> PhotoVaultColors.SyncGreen
        is ConnectionState.Connecting -> PhotoVaultColors.ArchiveAmber
        is ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "连接状态",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = connectionState.toDisplayString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * A single read-only information item with a label and value.
 */
@Composable
private fun AccountInfoItem(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Convert a ConnectionState to a user-facing display string.
 */
private fun ConnectionState.toDisplayString(): String {
    return when (this) {
        is ConnectionState.Connected -> when (type) {
            ConnectionType.LAN -> "已连接 (局域网)"
            ConnectionType.WAN -> "已连接 (公网)"
        }
        is ConnectionState.Connecting -> "连接中..."
        is ConnectionState.Disconnected -> "未连接"
    }
}

/**
 * Automatic backup setting. When ON, backup runs automatically via the
 * background scan worker, MediaStore observer, and condition-recovery paths.
 * When OFF, backup only happens when the user taps the Local tab "立即备份" FAB;
 * all automatic triggers stop uploading on their own.
 */
@Composable
private fun AutoBackupSetting(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onInteractingChange: (Boolean) -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "自动备份",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (enabled) {
                    "满足条件时自动在后台备份新照片"
                } else {
                    "已关闭，仅在本地页点击「立即备份」时备份"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Reserve two lines so toggling the copy doesn't shift the layout.
                minLines = 2
            )
        }
        val glassBackdrop = LocalGlassBackdrop.current
        if (glassBackdrop != null) {
            LiquidToggle(
                selected = { enabled },
                onSelect = onEnabledChange,
                backdrop = glassBackdrop,
                onDragStateChange = onInteractingChange
            )
        } else {
            val interactionSource = remember { MutableInteractionSource() }
            LaunchedEffect(interactionSource) {
                var active = 0
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> active++
                        is PressInteraction.Release -> active--
                        is PressInteraction.Cancel -> active--
                        is DragInteraction.Start -> active++
                        is DragInteraction.Stop -> active--
                        is DragInteraction.Cancel -> active--
                    }
                    onInteractingChange(active > 0)
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                interactionSource = interactionSource
            )
        }
    }
}

/**
 * WiFi-only backup setting. When ON, backup only happens over WiFi.
 * When OFF, backup is also allowed on cellular networks.
 */
@Composable
private fun WifiOnlySetting(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onInteractingChange: (Boolean) -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "仅 WiFi 下备份",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (enabled) "备份仅在 WiFi 连接时进行" else "允许使用移动数据备份（可能产生流量费用）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Reserve two lines so toggling between the short/long copy doesn't
                // change the row height and shift the layout.
                minLines = 2
            )
        }
        val glassBackdrop = LocalGlassBackdrop.current
        if (glassBackdrop != null) {
            LiquidToggle(
                selected = { enabled },
                onSelect = onEnabledChange,
                backdrop = glassBackdrop,
                // Block the page's vertical scroll while pressing/dragging the toggle.
                onDragStateChange = onInteractingChange
            )
        } else {
            // Observe the Switch's press/drag interactions so we can block the page's
            // vertical scroll for the duration of the touch, matching the toggle above.
            val interactionSource = remember { MutableInteractionSource() }
            LaunchedEffect(interactionSource) {
                var active = 0
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> active++
                        is PressInteraction.Release -> active--
                        is PressInteraction.Cancel -> active--
                        is DragInteraction.Start -> active++
                        is DragInteraction.Stop -> active--
                        is DragInteraction.Cancel -> active--
                    }
                    onInteractingChange(active > 0)
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                interactionSource = interactionSource
            )
        }
    }
}

/**
 * Minimum battery level slider setting.
 * Range: 20% to 80%, step: 5%, default: 50%
 */
@Composable
private fun MinBatteryLevelSetting(
    currentLevel: Int,
    onLevelChanged: (Int) -> Unit,
    onDraggingChange: (Boolean) -> Unit = {}
) {
    // Local slider state for smooth dragging.
    var sliderPosition by remember { mutableFloatStateOf(currentLevel.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    // Mirror external changes onto the thumb, but never while the user is dragging
    // (that would fight the finger). Re-keying the state on every commit is what
    // previously desynced the slider, so we sync explicitly instead.
    LaunchedEffect(currentLevel, isDragging) {
        if (!isDragging) sliderPosition = currentLevel.toFloat()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "最低电量",
                style = MaterialTheme.typography.bodyLarge
            )
            // Show the value snapped to the nearest 5% step so the readout matches
            // where the thumb will settle.
            val displayLevel = (Math.round(sliderPosition / 5f) * 5).coerceIn(
                SettingsPreferences.MIN_BATTERY_LEVEL_LOWER,
                SettingsPreferences.MIN_BATTERY_LEVEL_UPPER
            )
            Text(
                text = "$displayLevel%",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = "电量低于此值时暂停备份",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        val glassBackdrop = LocalGlassBackdrop.current
        val valueRange = SettingsPreferences.MIN_BATTERY_LEVEL_LOWER.toFloat()..
            SettingsPreferences.MIN_BATTERY_LEVEL_UPPER.toFloat()
        // Snap a raw slider value to the nearest 5% step, clamped to the range.
        val snapToStep: (Float) -> Int = { raw ->
            (Math.round(raw / 5f) * 5).coerceIn(
                SettingsPreferences.MIN_BATTERY_LEVEL_LOWER,
                SettingsPreferences.MIN_BATTERY_LEVEL_UPPER
            )
        }
        // Snap the thumb back onto the nearest step and commit the value (only when
        // it actually changes, so we don't spam preference writes).
        val settleToStep: () -> Unit = {
            val snapped = snapToStep(sliderPosition)
            sliderPosition = snapped.toFloat()
            if (snapped != currentLevel) onLevelChanged(snapped)
        }
        if (glassBackdrop != null) {
            LiquidSlider(
                value = { sliderPosition },
                onValueChange = { raw ->
                    // During a drag keep the raw position for a smooth thumb; only
                    // snap+commit once the finger lifts (handled in onDragStateChange).
                    sliderPosition = raw
                    // A track tap doesn't go through the drag callbacks, so snap it
                    // immediately here.
                    if (!isDragging) settleToStep()
                },
                onDragStateChange = { dragging ->
                    isDragging = dragging
                    // Block the page's vertical scroll for the duration of the drag.
                    onDraggingChange(dragging)
                    if (!dragging) settleToStep()
                },
                valueRange = valueRange,
                visibilityThreshold = 0.01f,
                backdrop = glassBackdrop,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Slider(
                value = sliderPosition,
                onValueChange = {
                    sliderPosition = it
                    onDraggingChange(true)
                },
                onValueChangeFinished = {
                    onDraggingChange(false)
                    settleToStep()
                },
                valueRange = valueRange,
                steps = 11 // (80-20)/5 - 1 = 11 steps between endpoints
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "20%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "80%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Scan interval radio button group.
 * Options: 5, 15, 30, 60 minutes.
 */
@Composable
private fun ScanIntervalSetting(
    currentInterval: Int,
    onIntervalChanged: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "扫描间隔",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "后台自动扫描新照片的时间间隔",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.selectableGroup()) {
            SettingsPreferences.SCAN_INTERVAL_OPTIONS.forEach { interval ->
                val label = when (interval) {
                    SettingsPreferences.SCAN_INTERVAL_TEST_10S -> "10 秒（测试）"
                    5 -> "5 分钟"
                    15 -> "15 分钟"
                    30 -> "30 分钟"
                    60 -> "60 分钟"
                    else -> "$interval 分钟"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = currentInterval == interval,
                            onClick = { onIntervalChanged(interval) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = currentInterval == interval,
                        onClick = null // handled by row's selectable
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}

/**
 * A card wrapper for settings groups with a title header.
 *
 * Uses a rounded [background] rather than a [Card]/[Surface] on purpose: a Card
 * clips its content to the rounded shape, which would cut off the liquid slider's
 * thumb where it overflows the track ends. A background modifier paints the same
 * rounded surface but does NOT clip children, so the thumb can extend into the
 * card's inner padding while keeping its full size and a full-width track.
 */
@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = CardDefaults.shape
            )
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

/**
 * 退出登录按钮 - theme error styled destructive text button.
 */
@Composable
private fun LogoutButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "退出登录",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * Confirmation dialog shown before performing logout.
 * Asks the user "确定要退出登录吗？" with confirm/cancel options.
 */
@Composable
private fun LogoutConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    LiquidGlassDialog(
        onDismissRequest = onDismiss,
        title = "退出登录",
        text = "确定要退出登录吗？"
    ) {
        LiquidDialogButton(
            text = "取消",
            onClick = onDismiss,
            style = LiquidDialogButtonStyle.Neutral
        )
        LiquidDialogButton(
            text = "确定",
            onClick = onConfirm,
            style = LiquidDialogButtonStyle.Destructive
        )
    }
}
