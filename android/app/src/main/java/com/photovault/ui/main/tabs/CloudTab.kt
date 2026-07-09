package com.photovault.ui.main.tabs

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.photovault.data.api.model.DirectoryInfo
import com.photovault.ui.main.components.CloudStatusColors
import com.photovault.ui.main.components.StatusChip
import com.photovault.ui.theme.LocalBottomBarPadding
import com.photovault.data.api.model.FileBrowseInfo

/**
 * 云端 Tab - 显示已备份到服务器上的文件和目录结构。
 *
 * Features:
 * - Path breadcrumb navigation at the top
 * - Folder list (icon + name + file count)
 * - File list (thumbnail + name + size + time)
 * - Click folder to navigate into subdirectory
 * - Click file to open full-screen image preview
 * - Pull-to-refresh to reload current directory
 * - Empty state guidance
 */
@Composable
fun CloudTab(
    viewModel: CloudTabViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var previewFile by remember { mutableStateOf<FileBrowseInfo?>(null) }
    val serverBaseUrl = viewModel.serverBaseUrl

    // In the recycle-bin view, the system back gesture returns to the browser
    // rather than leaving the Cloud Tab.
    BackHandler(enabled = uiState.viewMode == CloudViewMode.Trash) {
        viewModel.exitTrash()
    }

    when (uiState.viewMode) {
        CloudViewMode.Trash -> {
            TrashView(
                items = uiState.trashItems,
                isLoading = uiState.isTrashLoading,
                error = uiState.trashError,
                serverBaseUrl = serverBaseUrl,
                onBack = { viewModel.exitTrash() },
                onRestore = { viewModel.restoreFile(it.id) },
                onPurge = { viewModel.purgeFile(it.id) }
            )
        }

        CloudViewMode.Browse -> Column(modifier = Modifier.fillMaxSize()) {
            // Breadcrumb navigation
            BreadcrumbNavigation(
                breadcrumbs = uiState.breadcrumbs,
                onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) }
            )

            HorizontalDivider()

            // Content area
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    uiState.isLoading && !uiState.isRefreshing -> {
                        // Initial loading state
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    uiState.error != null -> {
                        // Error state
                        ErrorState(
                            message = uiState.error!!,
                            onRetry = { viewModel.loadDirectory(uiState.currentPath) }
                        )
                    }

                    uiState.isEmpty && !uiState.showTrashEntry -> {
                        // Empty state (only when there is nothing at all — at root
                        // the pinned trash entry keeps the list non-empty).
                        EmptyState()
                    }

                    else -> {
                        // Directory content list
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 8.dp,
                                bottom = 8.dp + LocalBottomBarPadding.current
                            )
                        ) {
                            // Pinned recycle-bin entry — always first, root only.
                            if (uiState.showTrashEntry) {
                                item(key = "trash_entry") {
                                    TrashEntryRow(
                                        count = uiState.trashTotal,
                                        onClick = { viewModel.enterTrash() }
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                }
                            }

                            // Directories
                            items(
                                items = uiState.directories,
                                key = { "dir_${it.path}" }
                            ) { directory ->
                                CloudDirectoryRow(
                                    directory = directory,
                                    onClick = { viewModel.navigateToDirectory(directory.path) }
                                )
                            }

                            // Files
                            items(
                                items = uiState.files,
                                key = { "file_${it.id}" }
                            ) { file ->
                                FileItem(
                                    file = file,
                                    serverBaseUrl = serverBaseUrl,
                                    onClick = { previewFile = file }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Full-screen image preview dialog
    previewFile?.let { file ->
        val downloadUrl = "$serverBaseUrl/api/v1/files/download/${file.id}"
        ImagePreviewDialog(
            file = file,
            downloadUrl = downloadUrl,
            onDismiss = { previewFile = null }
        )
    }
}

/**
 * Pinned "回收站" entry row, styled to match [CloudDirectoryRow] so it sits
 * naturally among the folders while its warning-tinted glyph and count badge
 * make it obviously an entry point rather than a regular folder.
 */
@Composable
private fun TrashEntryRow(
    count: Int,
    onClick: () -> Unit
) {
    val accent = CloudStatusColors.Trashed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = accent
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "回收站",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (count > 0) "$count 项待还原或清理" else "空",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = "进入回收站",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Breadcrumb navigation bar showing the current path.
 * Each segment is clickable to navigate to that directory.
 */
@Composable
private fun BreadcrumbNavigation(
    breadcrumbs: List<BreadcrumbItem>,
    onBreadcrumbClick: (BreadcrumbItem) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(end = 8.dp)
    ) {
        itemsIndexed(breadcrumbs) { index, breadcrumb ->
            // Breadcrumb segment
            TextButton(
                onClick = { onBreadcrumbClick(breadcrumb) },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(
                    text = breadcrumb.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index == breadcrumbs.lastIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Separator (except after last item)
            if (index < breadcrumbs.lastIndex) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A single cloud directory row, aligned with LocalTab's [FolderRow] compact
 * list-row style (no card): a rounded tinted box with a folder glyph, the
 * directory name, a compact subtitle, and a row of three per-status
 * [StatusChip]s — backed up (green), trashed (orange), purged (red).
 *
 * The status counts come straight from the browse response fields
 * ([DirectoryInfo.backedUpCount] / [DirectoryInfo.trashedCount] /
 * [DirectoryInfo.purgedCount]); when a legacy server omits them they default
 * to 0 and the chips simply render 0 without blocking the rest of the row.
 */
@Composable
private fun CloudDirectoryRow(
    directory: DirectoryInfo,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Folder glyph in a rounded tinted box (mirrors FolderRow).
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = directory.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = buildString {
                    append("${directory.fileCount} 项")
                    directory.latestFileTime?.let {
                        append(" · ")
                        append(formatTime(it))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            // Per-directory status chips: backed up (green) / trashed / purged.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    label = "已备份",
                    count = directory.backedUpCount,
                    color = CloudStatusColors.BackedUp
                )
                StatusChip(
                    label = "回收站",
                    count = directory.trashedCount,
                    color = CloudStatusColors.Trashed
                )
                StatusChip(
                    label = "已删除",
                    count = directory.purgedCount,
                    color = CloudStatusColors.Purged
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Arrow indicator
        Icon(
            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = "进入目录",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A single file item in the list.
 * Shows thumbnail, file name, size, and backup time.
 */
@Composable
private fun FileItem(
    file: FileBrowseInfo,
    serverBaseUrl: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            val thumbnailUrl = if (file.thumbnailUrl != null) {
                if (file.thumbnailUrl.startsWith("http")) file.thumbnailUrl
                else "$serverBaseUrl${file.thumbnailUrl}"
            } else {
                "$serverBaseUrl/api/v1/files/thumbnail/${file.id}"
            }
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = file.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // File info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatFileSize(file.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTime(file.exifTime ?: file.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Empty state shown when the directory has no files or folders.
 */
@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "还没有备份文件",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "去本地 Tab 添加备份文件夹吧",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Error state with retry option.
 */
@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

/**
 * Format file size to human-readable string.
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/**
 * Format a timestamp string to a shorter display format.
 * Input may be ISO 8601 or similar; we extract the date part.
 */
private fun formatTime(timeStr: String): String {
    // Try to extract date portion (YYYY-MM-DD) from various formats
    return if (timeStr.length >= 10) {
        timeStr.substring(0, 10)
    } else {
        timeStr
    }
}

