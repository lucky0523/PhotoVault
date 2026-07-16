package com.photovault.ui.main.tabs

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.photovault.data.local.entity.BackupFolder
import com.photovault.ui.main.components.CloudStatusColors
import com.photovault.ui.theme.PhotoVaultColors
import com.photovault.ui.main.components.StatusChip
import com.photovault.ui.main.components.StoragePolicySheet
import com.photovault.ui.theme.LocalBottomBarPadding
import com.photovault.ui.theme.SurfaceLiquidButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 本地 Tab - 显示手机本地的图片文件夹列表及其备份状态。
 *
 * Features:
 * - LazyColumn of folder cards with progress info
 * - FABs: refresh backup status + add new backup folder
 * - Pull-to-refresh to refresh backup status
 * - Long-press context menu (configure policy / remove)
 * - Storage policy BottomSheet for configuration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalTab(
    onNavigateToFolderDetail: (folderId: Long, folderName: String, folderUri: String, backedUpImages: Int) -> Unit = { _, _, _, _ -> },
    viewModel: LocalTabViewModel = hiltViewModel()
) {
    val folders by viewModel.folders.collectAsState()
    val showPolicySheet by viewModel.showPolicySheet.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    val pendingFolderUri by viewModel.pendingFolderUri.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sync server-side status (recycle-bin deletions / restores) when this tab
    // resumes — on tab entry and when the app returns to foreground. Throttled in
    // the manager so rapid switches don't repeat the full sync.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.syncStatusOnResume()
    }

    // Pull-to-refresh state (Material3 1.10 API: caller owns the isRefreshing flag).
    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }

    // Pull-to-refresh gesture. When 自动备份 is on this continues the automatic
    // backup; when it's off it only syncs server data + refreshes counts (no
    // upload). The ViewModel decides based on the toggle. Clearing the flag after
    // a short delay (so the pull feels responsive) is what retracts the indicator.
    val onPullRefresh: () -> Unit = {
        isRefreshing = true
        scope.launch {
            viewModel.refreshOnPull { errorMsg ->
                val msg = errorMsg ?: "已刷新"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            delay(800)
            isRefreshing = false
        }
    }

    // Backup FAB ("立即备份"). Always backs up: automatic backup when 自动备份 is
    // on, manual backup when it's off. Reuses the refresh indicator for feedback.
    val onBackupClick: () -> Unit = {
        isRefreshing = true
        scope.launch {
            viewModel.backupNow { errorMsg ->
                val msg = errorMsg ?: "已开始备份"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            delay(800)
            isRefreshing = false
        }
    }

    // System folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            val documentFile = DocumentFile.fromTreeUri(context, it)
            val folderName = documentFile?.name ?: "未知文件夹"
            val added = viewModel.onFolderPicked(it, folderName)
            if (!added) {
                android.widget.Toast.makeText(
                    context,
                    "该文件夹已添加，请勿重复添加",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        // The outer shell already handles the bottom (nav bar + floating tab bar)
        // inset via LocalBottomBarPadding, so don't add it again here.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            // Lift the buttons above the floating tab bar so they don't overlap it.
            Column(modifier = Modifier.padding(bottom = LocalBottomBarPadding.current)) {
                // Backup button — reuses the pull-to-refresh indicator for feedback.
                SurfaceLiquidButton(
                    onClick = onBackupClick,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Backup,
                        contentDescription = "立即备份",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Add folder button.
                SurfaceLiquidButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "添加备份文件夹",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onPullRefresh,
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            indicator = {
                // Fully custom indicator (cloud glyph) driven by the pull
                // distance fraction and the refreshing flag.
                PullRefreshCloudIndicator(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing
                )
            }
        ) {
            if (folders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "还没有备份文件夹",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "点击右下角按钮添加",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 8.dp,
                        bottom = 8.dp + LocalBottomBarPadding.current
                    )
                ) {
                    itemsIndexed(folders, key = { _, folder -> folder.id }) { index, folder ->
                        FolderRow(
                            folder = folder,
                            onClick = {
                                onNavigateToFolderDetail(
                                    folder.id,
                                    folder.folderName,
                                    folder.folderUri,
                                    folder.backedUpImages
                                )
                            },
                            onConfigurePolicy = { viewModel.showPolicyForFolder(folder) },
                            onRemove = { viewModel.removeFolder(folder.id) }
                        )
                        if (index < folders.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 64.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }
    }

    // Storage Policy BottomSheet
    if (showPolicySheet) {
        StoragePolicySheet(
            folder = selectedFolder,
            isNewFolder = pendingFolderUri != null,
            onDismiss = { viewModel.dismissPolicySheet() },
            onSave = { useCustomPath, customPath, useYearMonthLayer ->
                if (pendingFolderUri != null) {
                    viewModel.saveNewFolder(useCustomPath, customPath, useYearMonthLayer)
                } else {
                    selectedFolder?.let { folder ->
                        viewModel.updateFolderPolicy(
                            folderId = folder.id,
                            useCustomPath = useCustomPath,
                            customPath = customPath,
                            useYearMonthLayer = useYearMonthLayer
                        )
                    }
                }
            }
        )
    }
}

/**
 * Custom pull-to-refresh indicator: a frosted circle with a cloud glyph.
 *
 * Position is derived from the Material3 pull [PullToRefreshState.distanceFraction]
 * while pulling; when a refresh is running it animates up to a fixed resting
 * position, and glides back to 0 when it ends. It is not drawn at all when idle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.PullRefreshCloudIndicator(
    state: androidx.compose.material3.pulltorefresh.PullToRefreshState,
    isRefreshing: Boolean
) {
    val density = LocalDensity.current
    val restingPx = with(density) { 88.dp.toPx() }
    val indicatorSize = 46.dp
    val indicatorSizePx = with(density) { indicatorSize.toPx() }

    // Material3 1.10 exposes only distanceFraction (0f at rest, 1f at the trigger
    // threshold, and may exceed 1f on over-pull). Map it onto our travel.
    val pullPx = (state.distanceFraction * restingPx).coerceAtLeast(0f)
    val targetPos = if (isRefreshing) restingPx else pullPx

    // While the finger is actively dragging (not yet refreshing and there is a
    // pull distance) track the position 1:1 with `snap()` so the indicator feels
    // glued to the finger. Only the settle-into-refresh and retract-when-done
    // transitions use a tween. animateFloatAsState keeps its internal value
    // across the spec switch, so the handoff stays continuous with no jump.
    val isDragging = !isRefreshing && pullPx > 0f
    val pos by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = if (isDragging) snap() else tween(durationMillis = if (isRefreshing) 320 else 240),
        label = "pullPos"
    )

    if (pos <= 0.5f && !isRefreshing) return

    val appear = (pos / restingPx).coerceIn(0f, 1f)

    // Padding around the shadowed circle. `alpha` in the graphicsLayer below
    // forces this node into an offscreen buffer clipped to its own bounds; the
    // padding gives the elevation shadow room to render inside those bounds
    // instead of being cut off at the circle edge (which looked like a hard
    // ring/abnormal shadow while pulling).
    val shadowPadding = 10.dp
    val shadowPaddingPx = with(density) { shadowPadding.toPx() }

    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .graphicsLayer {
                translationY = pos - indicatorSizePx - shadowPaddingPx
                alpha = appear
                val s = 0.7f + 0.3f * appear
                scaleX = s
                scaleY = s
            }
            .padding(shadowPadding)
    ) {
        Surface(
            modifier = Modifier.size(indicatorSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.CloudUpload,
                        contentDescription = "下拉备份",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

/**
 * A dense, single-line-ish list row for a backup folder (no card): folder glyph,
 * name, a compact stats subtitle, a thin progress bar, and a status label.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderRow(
    folder: BackupFolder,
    onClick: () -> Unit,
    onConfigurePolicy: () -> Unit,
    onRemove: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    // Local-only counts derived from the folder's existing columns (pure function).
    val localCounts = deriveLocalCounts(folder)

    val notBackedUp = (folder.totalImages - folder.backedUpImages - folder.trashedImages - folder.purgedImages)
        .coerceAtLeast(0)
    // A file counts as "handled" once it has reached the server in any form.
    val handled = folder.totalImages - notBackedUp
    val progress = if (folder.totalImages > 0) handled.toFloat() / folder.totalImages.toFloat() else 0f
    val percent = (progress * 100).toInt()
    val done = folder.totalImages > 0 && notBackedUp == 0
    val statusColor = when {
        folder.totalImages == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        done -> PhotoVaultColors.SyncGreen
        handled > 0 -> MaterialTheme.colorScheme.primary
        else -> PhotoVaultColors.ArchiveAmber
    }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    // Folder name and the compact stats subtitle share one line to
                    // save vertical space: the name shrinks/ellipsizes as needed while
                    // the subtitle stays fully visible on the trailing edge.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = folder.folderName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = buildString {
                                append("${folder.totalImages} 项")
                                if (notBackedUp > 0) {
                                    append(" · 待备份 ")
                                    append(notBackedUp)
                                }
                                append(" · ")
                                append(relativeTime(folder.lastScanTime))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    // Local files' cloud status chips: 已备份 (green) / 未备份 (blue) /
                    // 回收站 (orange) / 已删除 (red). Reflects where each local file
                    // currently stands on the server. All four share one row; each chip
                    // takes an equal weight so they shrink uniformly instead of the
                    // last one getting squeezed / wrapped on narrow widths.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(
                            label = "已备份",
                            count = localCounts.backedUp,
                            color = CloudStatusColors.BackedUp,
                            modifier = Modifier.weight(1f)
                        )
                        StatusChip(
                            label = "未备份",
                            count = localCounts.pending,
                            color = CloudStatusColors.Pending,
                            modifier = Modifier.weight(1f)
                        )
                        StatusChip(
                            label = "回收站",
                            count = localCounts.trashed,
                            color = CloudStatusColors.Trashed,
                            modifier = Modifier.weight(1f)
                        )
                        StatusChip(
                            label = "已删除",
                            count = localCounts.purged,
                            color = CloudStatusColors.Purged,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Fixed-width status label so the chips column above keeps a
                // constant width and stays aligned across rows regardless of the
                // label text ("空" / "完成" / "100%"). Kept compact so the chips
                // row gets as much width as possible.
                Text(
                    text = if (folder.totalImages == 0) "空" else if (done) "完成" else "$percent%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            // Progress bar spans the full row width (below both the chips and the
            // fixed-width status label).
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("配置策略") },
                onClick = {
                    showMenu = false
                    onConfigurePolicy()
                }
            )
            DropdownMenuItem(
                text = { Text("移除", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    onRemove()
                }
            )
        }
    }
}

/** Human-friendly relative time for the last scan. */
private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "尚未扫描"
    val diff = System.currentTimeMillis() - epochMillis
    return when {
        diff < 60_000L -> "刚刚扫描"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 2_592_000_000L -> "${diff / 86_400_000L} 天前"
        else -> "很久以前"
    }
}
