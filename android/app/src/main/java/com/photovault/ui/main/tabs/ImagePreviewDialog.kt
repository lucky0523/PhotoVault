package com.photovault.ui.main.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.photovault.data.api.model.FileBrowseInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A single item shown in the full-screen media pager.
 *
 * [model] is any value Coil / ExoPlayer can load — a remote URL string, a
 * local MediaStore [android.net.Uri], a file path, etc.
 */
data class PreviewMedia(
    val fileName: String,
    val model: Any?,
    val isVideo: Boolean
)

/**
 * Full-screen image preview dialog for a remote (server) file. Adapts the
 * [FileBrowseInfo] + [downloadUrl] into the generic overload below.
 */
@Composable
fun ImagePreviewDialog(
    file: FileBrowseInfo,
    downloadUrl: String,
    onDismiss: () -> Unit
) = ImagePreviewDialog(
    fileName = file.fileName,
    model = downloadUrl,
    isVideo = file.mimeType?.startsWith("video/") == true,
    onDismiss = onDismiss
)

/**
 * Full-screen preview of a single media item. Delegates to
 * [MediaPagerPreviewDialog] with a one-item list.
 */
@Composable
fun ImagePreviewDialog(
    fileName: String,
    model: Any?,
    isVideo: Boolean = false,
    onDismiss: () -> Unit
) = MediaPagerPreviewDialog(
    items = listOf(PreviewMedia(fileName, model, isVideo)),
    initialIndex = 0,
    onDismiss = onDismiss
)

/**
 * Full-screen, swipeable media preview rendered as an **in-tree overlay** (not
 * a [Dialog]). Because it lives in the host screen's own composition — which
 * fills the edge-to-edge Activity window — its black backdrop truly covers the
 * whole screen, including the status bar and navigation bar areas. (A Compose
 * Dialog uses a separate floating window that the system insets by the bars, so
 * its backdrop can't reliably fill those regions.)
 *
 * Place it as the last child of a full-screen container so it draws on top of
 * everything else. It intercepts the back gesture to dismiss itself.
 */
@Composable
fun MediaPagerPreview(
    items: List<PreviewMedia>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    originBoundsFor: (Int) -> androidx.compose.ui.geometry.Rect? = { null }
) {
    if (items.isEmpty()) return
    BackHandler(onBack = onDismiss)
    LightSystemBarsEffect()
    MediaPagerContent(
        items = items,
        initialIndex = initialIndex,
        onDismiss = onDismiss,
        originBoundsFor = originBoundsFor
    )
}

/**
 * While in composition, forces the status- and navigation-bar icons to their
 * light (white) variant so they stay legible over the black preview backdrop,
 * restoring the previous appearance when the preview closes.
 */
@Composable
private fun LightSystemBarsEffect() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivityWindow()
        if (window == null) {
            onDispose {}
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val prevStatus = controller.isAppearanceLightStatusBars
            val prevNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.isAppearanceLightStatusBars = prevStatus
                controller.isAppearanceLightNavigationBars = prevNav
            }
        }
    }
}

/** Unwraps a [android.content.ContextWrapper] chain to find the Activity's window. */
private fun android.content.Context.findActivityWindow(): android.view.Window? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Full-screen, swipeable media preview shown inside a [Dialog]. Kept for callers
 * that render outside a full-screen container (e.g. the recycle bin). For the
 * folder grid prefer [MediaPagerPreview], which fills the system-bar areas.
 */
@Composable
fun MediaPagerPreviewDialog(
    items: List<PreviewMedia>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    if (items.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        FullScreenDialogEffect()
        MediaPagerContent(
            items = items,
            initialIndex = initialIndex,
            onDismiss = onDismiss,
            originBoundsFor = { null }
        )
    }
}

/**
 * The shared preview body: a black backdrop with a swipeable [HorizontalPager],
 * a close button and the current item's name. Images support pinch-to-zoom and
 * pan (paging is locked while zoomed in); videos play only on the visible page.
 */
@Composable
private fun MediaPagerContent(
    items: List<PreviewMedia>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    originBoundsFor: (Int) -> androidx.compose.ui.geometry.Rect?
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex)
    ) { items.size }

    // The active page's current zoom scale. While it's > 1 the pager stops
    // intercepting horizontal drags, so panning a magnified image doesn't flip
    // to the neighbouring page.
    var currentScale by remember { mutableFloatStateOf(1f) }
    // 0f = fully covering; grows toward 1f as the active image is dragged down to
    // dismiss. Fades the black backdrop (revealing the grid beneath) and the
    // chrome, iOS-Photos style.
    var dismissProgress by remember { mutableFloatStateOf(0f) }
    // Settling on a new page always starts unzoomed and un-dragged.
    LaunchedEffect(pagerState.currentPage) {
        currentScale = 1f
        dismissProgress = 0f
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidthPx = constraints.maxWidth
        val containerHeightPx = constraints.maxHeight

        // Backdrop drawn separately so it can fade during a dismiss drag while
        // the image itself stays fully opaque.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = 1f - dismissProgress }
                .background(Color.Black)
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = currentScale <= 1f
        ) { page ->
            val item = items[page]
            val active = page == pagerState.currentPage
            if (item.isVideo) {
                VideoPageContent(model = item.model, active = active)
            } else {
                ZoomableImageContent(
                    model = item.model,
                    contentDescription = item.fileName,
                    active = active,
                    containerWidthPx = containerWidthPx,
                    containerHeightPx = containerHeightPx,
                    originBounds = originBoundsFor(page),
                    onScaleChanged = { s -> if (active) currentScale = s },
                    onDismissProgress = { p -> if (active) dismissProgress = p },
                    onDismiss = onDismiss
                )
            }
        }

        // Chrome fades out as the image is dragged away.
        val chromeAlpha = (1f - dismissProgress).coerceIn(0f, 1f)

        // Close button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .graphicsLayer { alpha = chromeAlpha },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭预览",
                modifier = Modifier.size(28.dp)
            )
        }

        // File name of the current page at the bottom
        val currentName = items.getOrNull(pagerState.currentPage)?.fileName
        if (!currentName.isNullOrEmpty()) {
            Text(
                text = currentName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .graphicsLayer { alpha = chromeAlpha }
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * One image page: the original image with pinch-to-zoom and pan.
 *
 * Reports its current [scale][onScaleChanged] to the parent so the pager can
 * lock horizontal swiping while zoomed. Resets its zoom when it stops being the
 * [active] page so returning to it shows the default framing.
 */
@Composable
private fun ZoomableImageContent(
    model: Any?,
    contentDescription: String,
    active: Boolean,
    containerWidthPx: Int,
    containerHeightPx: Int,
    originBounds: androidx.compose.ui.geometry.Rect?,
    onScaleChanged: (Float) -> Unit,
    onDismissProgress: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    // Pan offset while zoomed in.
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Live drag translation / progress while dragging the un-zoomed image down.
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    // While true, the transform below is driven by the exit animation instead of
    // the live gesture (image flies back onto its thumbnail, or off-screen).
    var closing by remember { mutableStateOf(false) }
    var exitScale by remember { mutableFloatStateOf(1f) }
    var exitOffset by remember { mutableStateOf(Offset.Zero) }
    var exitAlpha by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    var snapBackJob by remember { mutableStateOf<Job?>(null) }
    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

    // Reset everything when this page leaves the foreground.
    LaunchedEffect(active) {
        if (!active) {
            snapBackJob?.cancel()
            scale = 1f
            offset = Offset.Zero
            dragOffset = Offset.Zero
            dragProgress = 0f
            closing = false
            exitAlpha = 1f
        }
    }
    // Surface the scale so the pager can enable/disable horizontal paging.
    LaunchedEffect(scale, active) {
        if (active) onScaleChanged(scale)
    }
    // Report backdrop-fade progress (live drag, or the exit animation).
    LaunchedEffect(dragProgress, active) {
        if (active) onDismissProgress(dragProgress)
    }

    // Animates the image onto its origin thumbnail (or off-screen if unknown),
    // fades the backdrop out, then removes the preview — no abrupt "flash".
    fun animateOutAndDismiss(startOffset: Offset, startScale: Float, startProgress: Float) {
        closing = true
        exitOffset = startOffset
        exitScale = startScale
        exitAlpha = 1f
        val target = originBounds
        snapBackJob = scope.launch {
            if (target != null && containerWidthPx > 0 && containerHeightPx > 0) {
                // Shrink/translate so the full-screen image node lands on the
                // thumbnail's rect (scaled about its center).
                val endScale = (target.width / containerWidthPx).coerceIn(0.05f, 1f)
                val endOffset = Offset(
                    target.center.x - containerWidthPx / 2f,
                    target.center.y - containerHeightPx / 2f
                )
                val anim = Animatable(0f)
                anim.animateTo(1f, animationSpec = tween(240)) {
                    exitScale = androidx.compose.ui.util.lerp(startScale, endScale, value)
                    exitOffset = Offset(
                        androidx.compose.ui.util.lerp(startOffset.x, endOffset.x, value),
                        androidx.compose.ui.util.lerp(startOffset.y, endOffset.y, value)
                    )
                    dragProgress = androidx.compose.ui.util.lerp(startProgress, 1f, value)
                    // Fade the image only at the very end so it melts into the grid.
                    exitAlpha = (1f - value * 1.4f).coerceIn(0f, 1f)
                }
            } else {
                // Unknown thumbnail: continue the downward motion and fade out.
                val endY = containerHeightPx.toFloat().coerceAtLeast(DISMISS_DISTANCE_PX)
                val anim = Animatable(0f)
                anim.animateTo(1f, animationSpec = tween(220)) {
                    exitOffset = Offset(startOffset.x, androidx.compose.ui.util.lerp(startOffset.y, endY, value))
                    dragProgress = androidx.compose.ui.util.lerp(startProgress, 1f, value)
                    exitAlpha = (1f - value).coerceIn(0f, 1f)
                }
            }
            onDismiss()
        }
    }

    val dismissScale = 1f - 0.15f * dragProgress

    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(model)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val dismissTriggerPx = 140.dp.toPx()
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (closing) return@awaitEachGesture
                        snapBackJob?.cancel()

                        var dismissing = false
                        var deferToPager = false
                        var accumulated = Offset.Zero
                        val tracker = VelocityTracker()

                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            event.changes.firstOrNull()?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                            }

                            if (zoom != 1f || scale > 1f) {
                                // Pinch-zoom, or panning an already-zoomed image.
                                if (dragOffset != Offset.Zero) {
                                    dragOffset = Offset.Zero
                                    dragProgress = 0f
                                }
                                dismissing = false
                                scale = (scale * zoom).coerceIn(0.5f, 5f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                                event.changes.forEach { it.consume() }
                            } else if (!deferToPager) {
                                accumulated += pan
                                if (!dismissing) {
                                    val slop = viewConfiguration.touchSlop
                                    if (kotlin.math.abs(accumulated.y) > slop &&
                                        kotlin.math.abs(accumulated.y) >= kotlin.math.abs(accumulated.x)
                                    ) {
                                        dismissing = true
                                        // Start from the movement accrued so far so
                                        // the image tracks the finger without a jump.
                                        dragOffset = Offset(
                                            accumulated.x,
                                            accumulated.y.coerceAtLeast(0f)
                                        )
                                    } else if (kotlin.math.abs(accumulated.x) > slop) {
                                        deferToPager = true
                                    }
                                }
                                if (dismissing) {
                                    val newY = (dragOffset.y + pan.y).coerceAtLeast(0f)
                                    dragOffset = Offset(dragOffset.x + pan.x, newY)
                                    dragProgress = (dragOffset.y / DISMISS_DISTANCE_PX).coerceIn(0f, 1f)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        val velocityY = tracker.calculateVelocity().y
                        when {
                            // Released past the distance threshold or flung down fast.
                            dismissing && (dragOffset.y > dismissTriggerPx || velocityY > 1200f) ->
                                // Start the exit from the exact scale currently
                                // shown (1 - 0.15*progress) so there's no jump
                                // back to 1x before shrinking to the thumbnail.
                                animateOutAndDismiss(
                                    startOffset = dragOffset,
                                    startScale = 1f - 0.15f * dragProgress,
                                    startProgress = dragProgress
                                )
                            // Short drag → spring back into place.
                            dismissing -> {
                                val start = dragOffset
                                val startP = dragProgress
                                snapBackJob = scope.launch {
                                    val anim = Animatable(0f)
                                    anim.animateTo(
                                        1f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    ) {
                                        dragOffset = Offset(start.x * (1f - value), start.y * (1f - value))
                                        dragProgress = startP * (1f - value)
                                    }
                                    dragOffset = Offset.Zero
                                    dragProgress = 0f
                                }
                            }
                            // Pinched below 1x → snap back to 1x.
                            scale < 1f -> {
                                val startScale = scale
                                val startOffset = offset
                                snapBackJob = scope.launch {
                                    val anim = Animatable(0f)
                                    anim.animateTo(1f, animationSpec = tween(200)) {
                                        val t = value
                                        scale = startScale + (1f - startScale) * t
                                        offset = Offset(
                                            startOffset.x * (1f - t),
                                            startOffset.y * (1f - t)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    if (closing) {
                        scaleX = exitScale
                        scaleY = exitScale
                        translationX = exitOffset.x
                        translationY = exitOffset.y
                        alpha = exitAlpha
                    } else {
                        scaleX = scale * dismissScale
                        scaleY = scale * dismissScale
                        translationX = offset.x + dragOffset.x
                        translationY = offset.y + dragOffset.y
                    }
                },
            onState = { imageState = it }
        )

        // Loading indicator
        if (imageState is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // Error state
        if (imageState is AsyncImagePainter.State.Error) {
            Text(
                text = "图片加载失败",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** Drag distance (px) that maps to full dismiss progress / backdrop fade. */
private const val DISMISS_DISTANCE_PX = 600f

/**
 * One video page backed by an ExoPlayer. Plays only while it is the [active]
 * page (and pauses when the app is backgrounded); the player is released when
 * the page leaves the composition.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPageContent(
    model: Any?,
    active: Boolean
) {
    val context = LocalContext.current

    val exoPlayer = remember(model) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = when (model) {
                is android.net.Uri -> MediaItem.fromUri(model)
                is String -> MediaItem.fromUri(model)
                null -> null
                else -> MediaItem.fromUri(model.toString())
            }
            if (mediaItem != null) {
                setMediaItem(mediaItem)
                prepare()
            }
        }
    }

    // Only the visible page plays.
    LaunchedEffect(active) {
        if (active) exoPlayer.play() else exoPlayer.pause()
    }
    // Pause when the app is backgrounded; the player is released on dispose.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        exoPlayer.pause()
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Makes the enclosing [Dialog]'s window draw edge-to-edge so a full-screen
 * background (e.g. the black preview backdrop) extends behind the status and
 * navigation bars instead of stopping at their insets. Also makes the system
 * bars transparent for a clean immersive look.
 *
 * Must be called from inside a `Dialog { ... }` content block.
 */
@Composable
private fun FullScreenDialogEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        // The DialogWindowProvider may be the view itself or one of its
        // ancestors depending on the Compose version, so walk up the tree
        // instead of assuming a fixed depth (a wrong cast would silently no-op).
        var node: android.view.ViewParent? = view.parent
        var provider: DialogWindowProvider? = view as? DialogWindowProvider
        while (provider == null && node != null) {
            provider = node as? DialogWindowProvider
            node = (node as? android.view.View)?.parent
        }
        val window = provider?.window
        if (window != null) {
            window.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
            // A Compose Dialog uses a floating window, which the system insets by
            // the status/navigation bars and which ignores decorFitsSystemWindows.
            // FLAG_LAYOUT_NO_LIMITS lets the window (and its black background)
            // extend behind those bars so the backdrop truly fills the screen.
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            // Let the backdrop draw into the display cutout (notch) area too.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        onDispose {}
    }
}
