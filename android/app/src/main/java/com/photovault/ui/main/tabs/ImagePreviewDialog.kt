package com.photovault.ui.main.tabs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
 * Full-screen media preview dialog.
 *
 * For images: displays the original with pinch-to-zoom and pan support.
 * For videos ([isVideo] = true): plays the media with an ExoPlayer, showing
 * the standard playback controls.
 *
 * [model] is any value Coil / ExoPlayer can load — a remote URL string, a
 * local MediaStore [android.net.Uri], a file path, etc.
 */
@Composable
fun ImagePreviewDialog(
    fileName: String,
    model: Any?,
    isVideo: Boolean = false,
    onDismiss: () -> Unit
) {
    if (isVideo) {
        VideoPreviewDialog(
            fileName = fileName,
            model = model,
            onDismiss = onDismiss
        )
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    // Tracks a running "snap back to default size" animation so a new gesture
    // can cancel it before taking over the transform.
    var snapBackJob by remember { mutableStateOf<Job?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        FullScreenDialogEffect()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

            // Zoomable image
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(model)
                    .crossfade(true)
                    .build(),
                contentDescription = fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            // Cancel any in-flight snap-back so the finger takes
                            // over immediately.
                            snapBackJob?.cancel()

                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    // Allow shrinking below 1x during the gesture
                                    // for tactile feedback; it snaps back on release.
                                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                                    offset = if (scale > 1f) offset + pan else Offset.Zero
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })

                            // Gesture ended. If the user shrank the image below its
                            // default size, animate it back to 1x (and re-center).
                            if (scale < 1f) {
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
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
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

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
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

            // File name at the bottom
            Text(
                text = fileName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
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
 * Full-screen video preview dialog backed by an ExoPlayer.
 *
 * The player is created for the given [model] (a remote URL string or a local
 * [android.net.Uri]) and released when the dialog leaves the composition. It
 * auto-plays on open and pauses/resumes with the app lifecycle.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPreviewDialog(
    fileName: String,
    model: Any?,
    onDismiss: () -> Unit
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
                playWhenReady = true
            }
        }
    }

    // Pause when the app is backgrounded; the player is released on dispose.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        exoPlayer.pause()
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        FullScreenDialogEffect()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
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

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
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
        }
    }
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
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        onDispose {}
    }
}
