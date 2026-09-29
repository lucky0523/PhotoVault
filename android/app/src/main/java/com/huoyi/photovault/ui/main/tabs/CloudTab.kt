package com.huoyi.photovault.ui.main.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.huoyi.photovault.ui.theme.LiquidDialogButton
import com.huoyi.photovault.ui.theme.LiquidDialogButtonStyle
import com.huoyi.photovault.ui.theme.LiquidGlassDialog
import com.huoyi.photovault.ui.theme.SurfaceLiquidButton
import com.huoyi.photovault.ui.theme.appBackgroundBrush
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.huoyi.photovault.data.api.model.DirectoryInfo
import com.huoyi.photovault.ui.main.components.CloudStatusColors
import com.huoyi.photovault.ui.main.components.StatusChip
import com.huoyi.photovault.ui.theme.LocalBottomBarPadding
import com.huoyi.photovault.data.api.model.FileBrowseInfo

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
    // Index into uiState.files of the item being previewed (null = no preview).
    // An index rather than the file itself, so the pager can swipe across the
    // whole folder — including pages fetched after the preview was opened.
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    val serverBaseUrl = viewModel.serverBaseUrl
    val listState = rememberLazyListState()

    // Reset the scroll position when we move to a different directory, otherwise
    // the new (possibly shorter) listing opens scrolled part-way down. Seeded from
    // the current path so simply returning to this tab keeps the restored
    // position instead of jumping back to the top.
    var lastPath by remember { mutableStateOf(uiState.currentPath) }
    LaunchedEffect(uiState.currentPath) {
        if (uiState.currentPath != lastPath) {
            lastPath = uiState.currentPath
            previewIndex = null
            listState.scrollToItem(0)
        }
    }

    // Infinite scroll: pull the next page as the tail of the list comes into view.
    LaunchedEffect(listState, uiState.currentPath, uiState.loadMoreError) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            Pair(
                layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
                layoutInfo.totalItemsCount
            )
        }.collect { (lastVisible, totalRows) ->
            if (uiState.loadMoreError == null && totalRows > 0 &&
                lastVisible >= totalRows - LOAD_MORE_THRESHOLD
            ) {
                viewModel.loadMoreFiles()
            }
        }
    }

    // In the recycle-bin view, the system back gesture returns to the browser
    // rather than leaving the Cloud Tab.
    BackHandler(enabled = uiState.viewMode == CloudViewMode.Trash) {
        viewModel.exitTrash()
    }

    // Inside a subdirectory, the system back gesture climbs one level up
    // (to the parent breadcrumb) instead of exiting the app. At the root the
    // handler stays disabled so back propagates normally.
    BackHandler(
        enabled = uiState.viewMode == CloudViewMode.Browse && uiState.breadcrumbs.size > 1
    ) {
        val parent = uiState.breadcrumbs[uiState.breadcrumbs.lastIndex - 1]
        viewModel.navigateToBreadcrumb(parent)
    }

    // In multi-select mode, back just leaves selection mode. Registered last so
    // it takes precedence over the directory-up handler above.
    BackHandler(
        enabled = uiState.viewMode == CloudViewMode.Browse && uiState.isSelectionMode
    ) {
        viewModel.clearSelection()
    }

    // Backdrop capturing the gradient + the file listing, sampled by the
    // selection button. The shell's LocalGlassBackdrop is gradient-only, so a
    // button sampling it would just paint gradient over the photos and look
    // opaque instead of see-through.
    val backgroundBrush = appBackgroundBrush()
    val contentBackdrop = rememberLayerBackdrop(
        onDraw = {
            drawRect(backgroundBrush)
            drawContent()
        }
    )

    // Confirmation dialog for moving the selected files into the recycle bin.
    var showTrashConfirm by remember { mutableStateOf(false) }

    // One-shot result feedback from the view model.
    val context = LocalContext.current
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
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
            // Breadcrumb navigation — only shown once we've navigated into a
            // subdirectory. At the root the path is just "/", so the bar would
            // waste vertical space without adding any wayfinding value.
            if (uiState.breadcrumbs.size > 1) {
                BreadcrumbNavigation(
                    breadcrumbs = uiState.breadcrumbs,
                    onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }

            // Content area
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // The listing is recorded into contentBackdrop so the floating
                // selection button (a sibling, never inside this layer) refracts
                // the thumbnails beneath it, like FolderDetailScreen's FABs.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(contentBackdrop)
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
                            state = listState,
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
                            itemsIndexed(
                                items = uiState.files,
                                key = { _, file -> "file_${file.id}" }
                            ) { index, file ->
                                FileItem(
                                    file = file,
                                    serverBaseUrl = serverBaseUrl,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = file.id in uiState.selectedFileIds,
                                    onClick = {
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleFileSelection(file.id)
                                        } else {
                                            previewIndex = index
                                        }
                                    },
                                    onLongClick = { viewModel.toggleFileSelection(file.id) }
                                )
                            }

                            // Footer spinner/error for loading the next page.
                            if (uiState.isLoadingMore) {
                                item(key = "files_loading_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            } else if (uiState.loadMoreError != null) {
                                item(key = "files_load_more_error") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = uiState.loadMoreError!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        TextButton(onClick = viewModel::loadMoreFiles) {
                                            Text("重试")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }

                // Multi-select action button (bottom-end, like LocalTab's FABs):
                // shows the selected count and a trash glyph; tapping it asks for
                // confirmation before moving the files into the recycle bin.
                if (uiState.isSelectionMode) {
                    SelectionTrashButton(
                        backdrop = contentBackdrop,
                        selectedCount = uiState.selectedFileIds.size,
                        isWorking = uiState.isMovingToTrash,
                        onClick = { showTrashConfirm = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = 16.dp,
                                bottom = 16.dp + LocalBottomBarPadding.current
                            )
                    )
                }
            }
        }
    }

    if (showTrashConfirm) {
        val count = uiState.selectedFileIds.size
        LiquidGlassDialog(
            onDismissRequest = { showTrashConfirm = false },
            title = "移入回收站",
            text = "确定将选中的 $count 张图片移入回收站吗？移入后可在回收站中还原。",
            buttons = {
                LiquidDialogButton(
                    text = "取消",
                    onClick = { showTrashConfirm = false }
                )
                LiquidDialogButton(
                    text = "移入回收站",
                    style = LiquidDialogButtonStyle.Destructive,
                    onClick = {
                        showTrashConfirm = false
                        viewModel.moveSelectedToTrash()
                    }
                )
            }
        )
    }

    // Full-screen, swipeable preview over the whole folder. The item list is
    // derived from uiState.files, so pages appended by loadMoreFiles() extend the
    // pager while it is open.
    previewIndex?.let { index ->
        val previewItems = remember(uiState.files, serverBaseUrl) {
            uiState.files.map { file ->
                PreviewMedia(
                    fileName = file.fileName,
                    model = "$serverBaseUrl/api/v1/files/download/${file.id}",
                    isVideo = file.mediaType == "video" ||
                        file.mimeType?.startsWith("video/") == true
                )
            }
        }
        if (index in previewItems.indices) {
            MediaPagerPreviewDialog(
                items = previewItems,
                initialIndex = index,
                onDismiss = { previewIndex = null },
                onPageChanged = { page ->
                    // Keep fetching ahead so swiping never dead-ends at a page
                    // boundary in folders larger than one server page.
                    if (page >= previewItems.lastIndex - LOAD_MORE_THRESHOLD) {
                        viewModel.loadMoreFiles()
                    }
                }
            )
        } else {
            // The list shrank out from under us (e.g. a refresh); close cleanly.
            LaunchedEffect(Unit) { previewIndex = null }
        }
    }
}

/**
 * How close to the end of the loaded list (in rows / pages) the user has to get
 * before the next page is requested.
 */
private const val LOAD_MORE_THRESHOLD = 5

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
    val listState = rememberLazyListState()
    // Keep the current (deepest) segment in view when navigating into deep
    // paths, so the user always sees where they are without manual scrolling.
    LaunchedEffect(breadcrumbs.size) {
        if (breadcrumbs.isNotEmpty()) {
            listState.animateScrollToItem(breadcrumbs.lastIndex)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(end = 8.dp)
    ) {
        itemsIndexed(breadcrumbs) { index, breadcrumb ->
            val isLast = index == breadcrumbs.lastIndex
            val segmentColor = if (isLast) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }

            // Root is rendered as a compact home glyph; deeper segments as
            // lightweight clickable text (no TextButton, so no 40dp min-height).
            if (index == 0) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "根目录",
                    tint = segmentColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onBreadcrumbClick(breadcrumb) }
                        .padding(4.dp)
                        .size(18.dp)
                )
            } else {
                Text(
                    text = breadcrumb.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = segmentColor,
                    fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onBreadcrumbClick(breadcrumb) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }

            // Separator (except after last item)
            if (!isLast) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // Plain list row (no card background), matching CloudDirectoryRow's
    // horizontal/vertical padding for a tighter, consistent rhythm.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
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

        // Selection indicator, only in multi-select mode.
        if (isSelectionMode) {
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = if (isSelected) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Outlined.RadioButtonUnchecked
                },
                contentDescription = if (isSelected) "已选中" else "未选中",
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * Multi-select action button, styled like LocalTab's floating buttons
 * ([SurfaceLiquidButton], 56dp tall, primary tint) but stretched into a pill
 * that shows the selected count next to a trash glyph.
 */
@Composable
private fun SelectionTrashButton(
    backdrop: Backdrop,
    selectedCount: Int,
    isWorking: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SurfaceLiquidButton(
        onClick = { if (!isWorking) onClick() },
        backdrop = backdrop,
        modifier = modifier
            .height(56.dp)
            .semantics { contentDescription = "将选中的 $selectedCount 张图片移入回收站" }
    ) {
        // Horizontal padding lives on the content (not the button modifier) so
        // the glass surface itself spans the full pill width.
        // Destructive action → red content (the app's "deleted" status color).
        val destructiveColor = CloudStatusColors.Purged
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$selectedCount",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = destructiveColor
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = destructiveColor
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = destructiveColor
                )
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
private fun formatTime(timeStr: String?): String {
    if (timeStr.isNullOrBlank()) return "未知时间"
    // Try to extract date portion (YYYY-MM-DD) from various formats
    return if (timeStr.length >= 10) {
        timeStr.substring(0, 10)
    } else {
        timeStr
    }
}

