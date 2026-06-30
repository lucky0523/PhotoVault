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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import com.photovault.data.local.entity.BackupFolder
import com.photovault.ui.main.components.StoragePolicySheet

/**
 * 本地 Tab - 显示手机本地的图片文件夹列表及其备份状态。
 *
 * Features:
 * - LazyColumn of folder cards with progress info
 * - FAB to add new backup folder via system folder picker
 * - Long-press context menu (configure policy / remove)
 * - Storage policy BottomSheet for configuration
 */
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

    // System folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistable permission for the URI
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)

            // Get folder name from URI
            val documentFile = DocumentFile.fromTreeUri(context, it)
            val folderName = documentFile?.name ?: "未知文件夹"

            viewModel.onFolderPicked(it, folderName)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { folderPickerLauncher.launch(null) }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "添加备份文件夹"
                )
            }
        }
    ) { paddingValues ->
        if (folders.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Prominent "立即备份" button — only shown when at least one folder exists
                Button(
                    onClick = {
                        viewModel.backupNow()?.let { errorMsg ->
                            android.widget.Toast.makeText(
                                context,
                                errorMsg,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } ?: run {
                            android.widget.Toast.makeText(
                                context,
                                "已开始备份",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("立即备份")
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
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
                    // New folder
                    viewModel.saveNewFolder(useCustomPath, customPath, useYearMonthLayer)
                } else {
                    // Existing folder policy update
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
 * Card displaying a backup folder with its name, progress, and status.
 * Supports long-press for context menu.
 */
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

    val statusIcon = when {
        folder.totalImages == 0 -> Icons.Filled.Folder
        folder.backedUpImages >= folder.totalImages -> Icons.Filled.CheckCircle
        folder.backedUpImages > 0 -> Icons.Filled.Sync
        else -> Icons.Filled.Warning
    }

    val statusColor = when {
        folder.totalImages == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        folder.backedUpImages >= folder.totalImages -> Color(0xFF4CAF50)
        folder.backedUpImages > 0 -> Color(0xFF2196F3)
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
                // Folder icon
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Folder info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.folderName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Progress bar
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = statusColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Progress text
                    Text(
                        text = "${folder.backedUpImages}/${folder.totalImages} 已备份",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Status icon
                Icon(
                    imageVector = statusIcon,
                    contentDescription = when {
                        folder.backedUpImages >= folder.totalImages -> "全部已备份"
                        folder.backedUpImages > 0 -> "备份中"
                        else -> "有待备份"
                    },
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Long-press dropdown menu
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
