package com.photovault.ui.main.tabs

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import com.photovault.data.local.entity.BackupFolder
import com.photovault.ui.main.components.StoragePolicySheet
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

    // Pull-to-refresh state
    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }

    // System folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            val documentFile = DocumentFile.fromTreeUri(context, it)
            val folderName = documentFile?.name ?: "未知文件夹"
            viewModel.onFolderPicked(it, folderName)
        }
    }

    fun onRefresh() {
        if (isRefreshing) return
        isRefreshing = true
        viewModel.backupNow { errorMsg ->
            if (errorMsg != null) {
                android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(context, "已开始刷新备份状态", android.widget.Toast.LENGTH_SHORT).show()
            }
            // Keep indicator visible for a moment to show the refresh happened
            scope.launch {
                delay(800)
                isRefreshing = false
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            Column {
                // Refresh FAB
                FloatingActionButton(
                    onClick = { onRefresh() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新备份状态"
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Add folder FAB
                FloatingActionButton(
                    onClick = { folderPickerLauncher.launch(null) }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "添加备份文件夹"
                    )
                }
            }
        }
    ) { paddingValues ->
        if (folders.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .nestedScroll(pullToRefreshState.nestedScrollConnection)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(folders, key = { it.id }) { folder ->
                        FolderCard(
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
                    }
                }

                PullToRefreshContainer(
                    state = pullToRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: BackupFolder,
    onClick: () -> Unit,
    onConfigurePolicy: () -> Unit,
    onRemove: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val progress = if (folder.totalImages > 0) {
        folder.backedUpImages.toFloat() / folder.totalImages.toFloat()
    } else {
        0f
    }

    val notBackedUp = folder.totalImages - folder.backedUpImages - folder.trashedImages - folder.purgedImages

    val statusIcon = when {
        folder.totalImages == 0 -> Icons.Filled.Folder
        notBackedUp == 0 && folder.backedUpImages >= folder.totalImages -> Icons.Filled.CheckCircle
        folder.backedUpImages > 0 || folder.trashedImages > 0 || folder.purgedImages > 0 -> Icons.Filled.Sync
        else -> Icons.Filled.Warning
    }

    val statusColor = when {
        folder.totalImages == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        notBackedUp == 0 && folder.backedUpImages >= folder.totalImages -> Color(0xFF4CAF50)
        folder.backedUpImages > 0 || folder.trashedImages > 0 || folder.purgedImages > 0 -> Color(0xFF2196F3)
        else -> Color(0xFFFFA000)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.folderName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = statusColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = buildString {
                            if (notBackedUp > 0) append("$notBackedUp 未备份 ")
                            if (folder.backedUpImages > 0) append("${folder.backedUpImages} 已备份 ")
                            if (folder.trashedImages > 0) append("${folder.trashedImages} 回收站 ")
                            if (folder.purgedImages > 0) append("${folder.purgedImages} 已删除")
                        }.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Icon(
                    imageVector = statusIcon,
                    contentDescription = when {
                        notBackedUp == 0 && folder.backedUpImages >= folder.totalImages -> "全部已备份"
                        folder.backedUpImages > 0 || folder.trashedImages > 0 || folder.purgedImages > 0 -> "部分已处理"
                        else -> "有待备份"
                    },
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
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
}
