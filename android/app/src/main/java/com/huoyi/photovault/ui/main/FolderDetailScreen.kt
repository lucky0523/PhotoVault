package com.huoyi.photovault.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.huoyi.photovault.ui.theme.PhotoBadgeColors
import com.huoyi.photovault.ui.theme.SemanticTone
import com.huoyi.photovault.ui.theme.semanticColorSet
import com.huoyi.photovault.ui.theme.statusSoftFill
import com.huoyi.photovault.ui.theme.LocalPhotoVaultDarkTheme
import com.huoyi.photovault.ui.main.components.GlassScrollbar
import com.huoyi.photovault.ui.main.tabs.MediaPagerPreview
import com.huoyi.photovault.ui.main.tabs.PreviewMedia
import com.huoyi.photovault.ui.theme.LiquidDialogButton
import com.huoyi.photovault.ui.theme.LiquidDialogButtonStyle
import com.huoyi.photovault.ui.theme.LiquidGlassDialog
import com.huoyi.photovault.ui.theme.LocalGlassBackdrop
import com.huoyi.photovault.ui.theme.SurfaceLiquidButton
import com.huoyi.photovault.ui.theme.appBackgroundBrush
import java.util.concurrent.TimeUnit

/**
 * Photo status filters shown by the folder detail FAB.
 */
internal enum class PhotoFilterKind(val label: String, val tone: SemanticTone?) {
    ALL("全部", null),
    NOT_BACKED_UP("未备份", SemanticTone.Info),
    BACKED_UP("已备份", SemanticTone.Success),
    TRASHED("回收站", SemanticTone.Warning),
    PURGED("已删除", SemanticTone.Danger)
}

@Composable
internal fun PhotoFilterChip(
    filter: PhotoFilterKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Liquid-glass pills. Unselected: a translucent light tint of the pill's own
    // status color (instead of the shared pale-pink default glass) with the vivid
    // status color as text. Selected: filled with that status color.
    // 全部 uses the brand primary instead of a status color.
    val dark = LocalPhotoVaultDarkTheme.current
    val semantic = filter.tone?.let { semanticColorSet(it) }
    val accent = semantic?.accent ?: MaterialTheme.colorScheme.primary
    // Same soft look as StatusChip (see statusSoftFill).
    val unselectedTint = statusSoftFill(accent, dark)
    val selectedForeground = when {
        semantic == null -> MaterialTheme.colorScheme.onPrimary
        // Dark-mode accents are light pastels, so white text would vanish on them.
        dark -> semantic.container
        else -> Color.White
    }
    SurfaceLiquidButton(
        onClick = onClick,
        surfaceColor = if (selected) accent else unselectedTint,
        // No shadow: prevents shadow "jumps" when the pills expand/collapse.
        showShadow = false,
        modifier = modifier
            .height(40.dp)
            .testTag("photo_filter_${filter.name.lowercase()}_${if (selected) "selected" else "unselected"}")
    ) {
        Text(
            text = filter.label,
            modifier = Modifier.padding(horizontal = 14.dp),
            fontSize = 12.sp,
            maxLines = 1,
            color = if (selected) selectedForeground else accent,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
        )
    }
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
    // Index (into filteredImages) of the photo/video shown full-screen, or null.
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    // On-screen bounds (root coordinates) of each visible thumbnail, keyed by
    // photo URI. Used by the preview to animate the image back onto its
    // thumbnail when dismissed. A plain map: read on demand, no recomposition.
    val thumbnailBounds = remember { mutableMapOf<String, Rect>() }
    var selectedFilter by remember { mutableStateOf(PhotoFilterKind.ALL) }
    var filterExpanded by remember { mutableStateOf(false) }

    // The grid scrolls edge-to-edge beneath the transparent overlay top bar.
    val gridState = rememberLazyGridState()

    // Handle system back gesture explicitly so it always navigates back cleanly.
    BackHandler { onNavigateBack() }

    val filteredImages = remember(images, selectedFilter) {
        when (selectedFilter) {
            PhotoFilterKind.ALL -> images
            PhotoFilterKind.NOT_BACKED_UP -> images.filter { it.status == null }
            PhotoFilterKind.BACKED_UP -> images.filter { it.isBackedUp }
            PhotoFilterKind.TRASHED -> images.filter { it.isTrashed }
            PhotoFilterKind.PURGED -> images.filter { it.isPurged }
        }
    }

    LaunchedEffect(folderUri) {
        viewModel.loadImages(folderUri)
    }

    // Fallback refresh: when the screen returns to the foreground, re-align the
    // displayed statuses with the photo_status table. Covers the "switch away →
    // upload completes → switch back" scenario where the reactive Flow
    // subscription was stopped while backgrounded (requirement 2.6).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.reloadStatuses()
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

    // Insets used to lay the full-screen grid out edge-to-edge: the first row
    // starts below the status bar + top bar, and the last row clears the nav bar.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topBarHeight = 64.dp

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
                // Full-screen content: the grid draws edge-to-edge (under the
                // status bar and the overlaid top bar) for the immersive effect.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                floatingActionButton = {
                    Column(
                        // Scaffold no longer insets the FAB (contentWindowInsets
                        // is zeroed for the edge-to-edge grid), so add the nav-bar
                        // inset here to keep the buttons above it.
                        modifier = Modifier.navigationBarsPadding(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Filter pills. When expanded all four show; when
                        // collapsed only the active (non-ALL) filter stays
                        // visible so the current filter state is always shown.
                        Column(horizontalAlignment = Alignment.End) {
                            PhotoFilterKind.entries.forEach { filter ->
                                val selected = filter == selectedFilter
                                val chipVisible = filterExpanded ||
                                    (selected && filter != PhotoFilterKind.ALL)
                                AnimatedVisibility(
                                    visible = chipVisible,
                                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                                ) {
                                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                                        PhotoFilterChip(
                                            filter = filter,
                                            selected = selected,
                                            onClick = { selectedFilter = filter }
                                        )
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
                // Apply systemGestureExclusion to the grid area only (not the
                // whole screen) so the left-edge back gesture is blocked here,
                // preventing accidental navigation while browsing photos. The
                // top-bar area still allows the back gesture.
                Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemGestureExclusion()
                        .layerBackdrop(contentBackdrop)
                ) {
                if (loading && filteredImages.isEmpty()) {
                    // Show a spinner during the initial MediaStore scan instead
                    // of a blank white screen while the folder loads.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (filteredImages.isEmpty()) {
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
                                text = if (selectedFilter == PhotoFilterKind.ALL) "暂无图片" else "该状态下暂无图片",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        // Top inset keeps the first row below the status bar +
                        // top bar; content scrolls up beneath the bar as the user
                        // browses. Bottom inset clears the navigation bar.
                        contentPadding = PaddingValues(
                            start = 4.dp,
                            end = 4.dp,
                            top = statusBarTop + topBarHeight + 4.dp,
                            bottom = navBarBottom + 4.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = filteredImages,
                            // Stable per-photo key (the unique MediaStore URI) so
                            // filtering and status updates diff by photo identity,
                            // not list position — keeps thumbnails from flickering /
                            // rebinding and preserves scroll position on large grids.
                            key = { it.uri.toString() }
                        ) { image ->
                            ImageThumbnailItem(
                                image = image,
                                detectMotion = viewModel::isMotionPhoto,
                                onBoundsChanged = { rect ->
                                    thumbnailBounds[image.uri.toString()] = rect
                                },
                                onClick = { img ->
                                    previewIndex = filteredImages
                                        .indexOfFirst { it.uri == img.uri }
                                        .takeIf { it >= 0 }
                                },
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
                    // Liquid-glass, auto-hiding scrollbar on the right edge.
                    // Sibling of (and drawn on top of) the captured grid so it
                    // refracts the photos beneath without feeding back on itself.
                    if (filteredImages.isNotEmpty()) {
                        GlassScrollbar(
                            state = gridState,
                            columns = 3,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(
                                    top = statusBarTop + topBarHeight,
                                    bottom = navBarBottom
                                )
                        )
                    }
                }
            }
        }

        // Immersive overlay header. Drawn above the grid with no background of
        // its own, so photos show through. Unlike a Material TopAppBar (whose
        // Surface swallows all touches across its width), this is a plain Row:
        // only the back button consumes clicks, so taps and scrolls elsewhere in
        // the header strip pass straight through to the photos beneath.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(topBarHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回"
                )
            }
            Text(
                text = folderName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Full-screen, swipeable preview overlay. Rendered as the last child of
        // this edge-to-edge root Box (not a Dialog) so its black backdrop fills
        // the entire screen — including the status bar and navigation bar areas —
        // and draws on top of the grid, FAB and header. Loads local MediaStore
        // URIs directly (images: pinch-to-zoom + pan; videos: ExoPlayer). Swiping
        // left/right moves to the previous/next item in the filtered list.
        previewIndex?.let { index ->
            val previewItems = remember(filteredImages) {
                filteredImages.map { PreviewMedia(it.name, it.uri, it.isVideo) }
            }
            if (index in previewItems.indices) {
                MediaPagerPreview(
                    items = previewItems,
                    initialIndex = index,
                    originBoundsFor = { page ->
                        filteredImages.getOrNull(page)
                            ?.uri?.toString()
                            ?.let { thumbnailBounds[it] }
                    },
                    onDismiss = { previewIndex = null }
                )
            }
        }
    }

    // Re-upload confirmation dialog for trashed/purged photos
    rebackupTarget?.let { target ->
        LiquidGlassDialog(
            onDismissRequest = { rebackupTarget = null },
            title = "重新备份",
            text = if (target.isTrashed) {
                "该照片在回收站中，重新备份将从服务器恢复记录。"
            } else {
                "该照片已从服务器删除，重新备份将创建新的备份记录。"
            }
        ) {
            LiquidDialogButton(
                text = "取消",
                onClick = { rebackupTarget = null },
                style = LiquidDialogButtonStyle.Neutral
            )
            LiquidDialogButton(
                text = "重新备份",
                onClick = {
                    viewModel.rebackup(target, folderUri)
                    rebackupTarget = null
                },
                style = LiquidDialogButtonStyle.Accent
            )
        }
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
    detectMotion: suspend (FolderImage) -> Boolean,
    onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit,
    onClick: (FolderImage) -> Unit,
    onLongPress: (FolderImage) -> Unit
) {
    val alpha = if (image.isTrashed || image.isPurged) 0.4f else 1f

    // Motion-photo detection runs lazily only for thumbnails that become
    // visible, keyed by the stable URI so a status update (new FolderImage with
    // the same URI) doesn't re-trigger the file read. The result is cached in
    // the ViewModel, so re-scrolling reuses it instantly.
    val isMotionPhoto by produceState(initialValue = false, image.uri) {
        value = if (image.isVideo) false else detectMotion(image)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
            .combinedClickable(
                onClick = { onClick(image) },
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

        // Motion photo "LIVE" badge (top-right corner), mirroring the web style
        if (!image.isVideo && isMotionPhoto) {
            MotionPhotoBadge(
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        // Uploading overlay: a scrim + spinner shown while a manual re-backup of
        // this photo is in flight (until it converges to 已备份).
        if (image.isUploading) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = Color.White
                )
            }
        }

        // Status badge (bottom-right corner)
        StatusBadge(image)
    }
}

/**
 * "LIVE" pill shown on motion-photo thumbnails, matching the web client:
 * a semi-transparent dark rounded background with an iOS Live-Photo glyph
 * and the "LIVE" label.
 */
@Composable
private fun MotionPhotoBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        LivePhotoIcon(
            modifier = Modifier.size(14.dp),
            color = Color.White
        )
    }
}

/**
 * iOS "Live Photo"-style glyph: a solid center dot, a thin ring, and an outer
 * dashed ring. Drawn with Canvas so it matches the web `LivePhotoIcon.vue`.
 */
@Composable
private fun LivePhotoIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val unit = size.minDimension / 24f

        // Solid center dot (r = 3.1)
        drawCircle(color = color, radius = 3.1f * unit, center = c)

        // Thin inner ring (r = 6, stroke 1.4)
        drawCircle(
            color = color,
            radius = 6f * unit,
            center = c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f * unit)
        )

        // Outer dashed ring (r = 9.3, stroke 1.5)
        drawCircle(
            color = color,
            radius = 9.3f * unit,
            center = c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.5f * unit,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(0.2f * unit, 3.1f * unit),
                    0f
                )
            )
        )
    }
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.StatusBadge(image: FolderImage) {
    when {
        image.isBackedUp -> {
            BadgeIcon(
                backgroundColor = PhotoBadgeColors.container
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已备份",
                    tint = PhotoBadgeColors.foreground(SemanticTone.Success),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        image.isTrashed -> {
            val remaining = formatRemainingTime(image.status?.expiresAt)
            BadgeLabel(
                backgroundColor = PhotoBadgeColors.container,
                foregroundColor = PhotoBadgeColors.foreground(SemanticTone.Warning),
                text = remaining
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "回收站中",
                    tint = PhotoBadgeColors.foreground(SemanticTone.Warning),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        image.isPurged -> {
            BadgeIcon(
                backgroundColor = PhotoBadgeColors.container
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "已删除",
                    tint = PhotoBadgeColors.foreground(SemanticTone.Danger),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        // not_backed_up: no badge
    }
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.BadgeIcon(
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
internal fun androidx.compose.foundation.layout.BoxScope.BadgeLabel(
    backgroundColor: Color,
    foregroundColor: Color,
    text: String?,
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            if (!text.isNullOrEmpty()) {
                Text(
                    text = text,
                    color = foregroundColor,
                    fontSize = 9.sp
                )
            }
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
