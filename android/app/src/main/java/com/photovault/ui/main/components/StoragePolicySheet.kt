package com.photovault.ui.main.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.photovault.data.local.entity.BackupFolder
import com.photovault.ui.theme.LocalGlassBackdrop
import com.photovault.ui.theme.liquid.LiquidToggle

/**
 * Bottom sheet for configuring storage policy of a backup folder.
 *
 * Contains:
 * - Switch 1: 手动指定存储目录 (manual custom path)
 * - Switch 2: 按年月分层 (year/month layering)
 * - Path preview area showing the resolved example path
 * - Save button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoragePolicySheet(
    folder: BackupFolder?,
    isNewFolder: Boolean,
    onDismiss: () -> Unit,
    onSave: (useCustomPath: Boolean, customPath: String?, useYearMonthLayer: Boolean) -> Unit
) {
    if (folder == null) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var useCustomPath by remember(folder.id) { mutableStateOf(folder.useCustomPath) }
    var customPath by remember(folder.id) { mutableStateOf(folder.customPath ?: "") }
    var useYearMonthLayer by remember(folder.id) { mutableStateOf(folder.useYearMonthLayer) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // Title
            Text(
                text = if (isNewFolder) "配置存储策略 — ${folder.folderName}" else "修改存储策略 — ${folder.folderName}",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Switch 1: Manual custom path
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "手动指定存储目录",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "开启后可自定义 NAS 端存储路径",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PolicyToggle(
                    checked = useCustomPath,
                    onCheckedChange = { useCustomPath = it }
                )
            }

            // Custom path input (shown when switch 1 is on)
            AnimatedVisibility(visible = useCustomPath) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = { customPath = it },
                        label = { Text("目标路径") },
                        placeholder = { Text("/data/alice/travel") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = useCustomPath && customPath.isNotEmpty() && !isValidPath(customPath)
                    )
                    if (useCustomPath && customPath.isNotEmpty() && !isValidPath(customPath)) {
                        Text(
                            text = "路径仅允许字母、数字、斜杠、下划线、连字符和点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Switch 2: Year/month layering
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "按年月分层",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "按拍摄时间的年/月归档图片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PolicyToggle(
                    checked = useYearMonthLayer,
                    onCheckedChange = { useYearMonthLayer = it }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Path preview area
            Text(
                text = "路径预览",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buildPreviewPath(
                    folderName = folder.folderName,
                    useCustomPath = useCustomPath,
                    customPath = customPath,
                    useYearMonthLayer = useYearMonthLayer
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
                    .heightIn(min = 56.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Save button
            Button(
                onClick = {
                    onSave(
                        useCustomPath,
                        if (useCustomPath) customPath.ifEmpty { null } else null,
                        useYearMonthLayer
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !useCustomPath || (customPath.isNotEmpty() && isValidPath(customPath))
            ) {
                Text("保存")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Storage-policy toggle. Uses the liquid-glass [LiquidToggle] when a glass
 * backdrop is available, falling back to the Material [Switch] otherwise
 * (mirrors the pattern used in SettingsTab's backup-condition toggles).
 *
 * The toggle is wrapped in a [Box] with [detectDragGestures] that consumes
 * vertical drag events the toggle itself doesn't handle, preventing the
 * parent [ModalBottomSheet] from intercepting them and dismissing the sheet.
 */
@Composable
private fun PolicyToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val glassBackdrop = LocalGlassBackdrop.current
    Box(
        modifier = Modifier.pointerInput(Unit) {
            detectDragGestures { _, _ -> }
        }
    ) {
        if (glassBackdrop != null) {
            LiquidToggle(
                selected = { checked },
                onSelect = onCheckedChange,
                backdrop = glassBackdrop
            )
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/**
 * Build a preview path based on current policy settings.
 */
private fun buildPreviewPath(
    folderName: String,
    useCustomPath: Boolean,
    customPath: String,
    useYearMonthLayer: Boolean
): String {
    val basePath = if (useCustomPath && customPath.isNotEmpty()) {
        customPath.trimEnd('/')
    } else {
        "/data/{用户名}/{设备名}"
    }

    val folderPart = "/$folderName"

    val yearMonthPart = if (useYearMonthLayer) "/2026/03" else ""

    return "$basePath$folderPart$yearMonthPart/example.jpg"
}

/**
 * Validate path: only allows letters, digits, slash, underscore, hyphen, and dot.
 */
private fun isValidPath(path: String): Boolean {
    if (path.length > 255) return false
    return path.matches(Regex("^[a-zA-Z0-9/_\\-.]+$"))
}
