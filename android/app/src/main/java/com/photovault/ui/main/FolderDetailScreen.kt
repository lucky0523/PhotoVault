package com.photovault.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.photovault.ui.theme.LocalGlassBackdrop
import com.photovault.ui.theme.SurfaceLiquidButton
import com.photovault.ui.theme.appBackgroundBrush
import java.util.concurrent.TimeUnit

/**
 * Photo status filters shown by the folder detail FAB.
 */
private enum class PhotoFilter(val label: String) {
    ALL("全部"),
    BACKED_UP("已备份"),
    TRASHED("回收站"),
    PURGED("已删除")
}

/**
 * Folder detail screen showing a grid of image thumbnails with four-state
 * backup status indicators:
 *
 * - 未备份 (not_backed_up): no badge
 * - 已备份 (active):       green checkmark
 * - 回收站中 (trashed):    orange trash icon + remaining days
 * - 已删除 (purged):       red X icon
 *
 * Long-pressing a trashed/purged photo shows a "重新备份" dialog that
 * re-uploads the file, bypassing the scan skip logic. The server reactivates
 * the existing record (no duplicate created).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    folderName: String,
    folderUri: String,
    backedUpImages: Int,
    onNavigateBack: () -> Unit,
    viewModel: FolderDetailViewModel = hiltViewModel()
) {
    val images by viewModel.images.collectAsState()
    val loading by viewModel.loading.collectAsState()

    var rebackupTarget by remember { mutableStateOf<FolderImage?>(null) }
    var selectedFilter by remember { mutableStateOf(PhotoFilter.ALL) }
    var filterExpanded by remember { mutableStateOf(false) }

    val filteredImages = remember(images, selectedFilter) {
        when (selectedFilter) {
            PhotoFilter.ALL -> images
            PhotoFilter.BACKED_UP -> images.filter { it.isBackedUp }
            PhotoFilter.TRASHED -> images.filter { it.isTrashed }
            PhotoFilter.PURGED -> images.filter { it.isPurged }
        }
    }

    LaunchedEffect(folderUri) {
        viewModel.loadImages(folderUri)
    }

    val backgroundBrush = appBackgroundBrush()
    // Content backdrop captures the gradient + the photo grid, so the FAB and
    // filter buttons genuinely refract the photos beneath them (a real liquid-
    // glass, see-through effect). The buttons live in the Scaffold's FAB slot —
    // a sibling of the captured content layer — so they never sample themselves.
    val contentBackdrop = rememberLayerBackdrop(
        onDraw = {
            drawRect(backgroundBrush)
            drawContent()
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // On-screen gradient painted behind the transparent Scaffold.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
        )

        CompositionLocalProvider(LocalGlassBackdrop provides contentBackdrop) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text(folderName) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        ),
                        navigationIcon = {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回"
                                )
                            }
                        }
                    )
                },
                floatingActionButton = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Filter pills. When expanded all four show; when
                        // collapsed only the active (non-ALL) filter stays
                        // visible so the current filter state is always shown.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PhotoFilter.entries.forEach { filter ->
                                val selected = filter == selectedFilter
                                val chipVisible = filterExpanded ||
                                    (selected && filter != PhotoFilter.ALL)
                                AnimatedVisibility(
                                    visible = chipVisible,
                                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
                                ) {
                                    Box(modifier = Modifier.padding(end = 8.dp)) {
                                        SurfaceLiquidButton(
                                            onClick = { selectedFilter = filter },
                                            surfaceColor = if (selected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                            } else {
                                                null
                                            },
                                            modifier = Modifier.height(40.dp)
                                        ) {
                                            Text(
                                                text = filter.label,
                                                modifier = Modifier.padding(horizontal = 14.dp),
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // Toggle FAB.
                        SurfaceLiquidButton(
                            onClick = { filterExpanded = !filterExpanded },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FilterList,
                                contentDescription = "筛选照片状态",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            ) { paddingValues ->
                // The photo grid is recorded into contentBackdrop so the
                // floating buttons above genuinely refract it.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(contentBackdrop)
                ) {
                if (filteredImages.isEmpty() && !loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Image,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (selectedFilter == PhotoFilter.ALL) "暂无图片" else "该状态下暂无图片",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredImages) { image ->
                            ImageThumbnailItem(
                                image = image,
                                onLongPress = { img ->
                                    if (img.isTrashed || img.isPurged) {
                                        rebackupTarget = img
                                    }
                                }
                            )
                        }
                    }
                }
                }
            }
        }
    }

    // Re-upload confirmation dialog for trashed/purged photos
    rebackupTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { rebackupTarget = null },
            title = { Text("重新备份") },
            text = {
                Text(
                    if (target.isTrashed) {
                        "该照片在回收站中，重新备份将从服务器恢复记录。"
                    } else {
                        "该照片已从服务器删除，重新备份将创建新的备份记录。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rebackup(target, folderUri)
                    rebackupTarget = null
                }) {
                    Text("重新备份")
                }
            },
            dismissButton = {
                TextButton(onClick = { rebackupTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * Single image thumbnail with four-state backup status overlay.
 *
 * Trashed and purged photos are rendered at reduced opacity to visually
 * distinguish them from active and not-backed-up photos.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageThumbnailItem(
    image: FolderImage,
    onLongPress: (FolderImage) -> Unit
) {
    val alpha = if (image.isTrashed || image.isPurged) 0.4f else 1f

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = {},
                onLongClick = { onLongPress(image) }
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(image.uri)
                .crossfade(true)
                .size(200)
                .build(),
            contentDescription = image.name,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayerAlpha(alpha),
            contentScale = ContentScale.Crop
        )

        // Play icon overlay for videos
        if (image.isVideo) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = "视频",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            )
        }

        // Status badge (bottom-right corner)
        StatusBadge(image)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.StatusBadge(image: FolderImage) {
    when {
        image.isBackedUp -> {
            BadgeIcon(
                backgroundColor = Color(0xFF4CAF50)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已备份",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        image.isTrashed -> {
            val remaining = formatRemainingTime(image.status?.expiresAt)
            BadgeLabel(
                backgroundColor = Color(0xFFFF9800),
                text = if (remaining != null) "回收站 $remaining" else "回收站"
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "回收站中",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        image.isPurged -> {
            BadgeIcon(
                backgroundColor = Color(0xFFF44336)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "已删除",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        // not_backed_up: no badge
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.BadgeIcon(
    backgroundColor: Color,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .size(20.dp)
            .background(backgroundColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.BadgeLabel(
    backgroundColor: Color,
    text: String,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(4.dp)
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            icon()
            Text(
                text = text,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Formats the remaining time until auto-purge as "Xd Yh" or "Yh" (null if expired/unknown).
 */
private fun formatRemainingTime(expiresAt: Long?): String? {
    if (expiresAt == null) return null
    val remainingMs = expiresAt - System.currentTimeMillis()
    if (remainingMs <= 0) return null
    val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMs) - days * 24
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时"
        else -> "<1小时"
    }
}

/**
 * Helper to apply alpha to a Modifier using graphicsLayer.
 */
private fun Modifier.graphicsLayerAlpha(alpha: Float): Modifier =
    this.then(
        Modifier.graphicsLayer {
            this.alpha = alpha
        }
    )
