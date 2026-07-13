package com.photovault.ui.main.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.photovault.ui.theme.LocalGlassBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A liquid-glass, auto-hiding scrollbar for a [LazyVerticalGrid][androidx.compose.foundation.lazy.grid.LazyVerticalGrid].
 *
 * - Appears while the grid is scrolling (or the thumb is being dragged) and
 *   fades out after a short idle delay.
 * - The thumb is draggable for fast scrubbing.
 * - Styled with the app's liquid-glass look: it samples [LocalGlassBackdrop] to
 *   refract the photos beneath (like the floating buttons), falling back to a
 *   translucent capsule when no backdrop is available.
 *
 * Place it as a sibling drawn **on top of** the grid (e.g. `Modifier.align(
 * Alignment.CenterEnd)` inside the same Box), NOT inside the layer captured by
 * the backdrop, so sampling never feeds back on itself.
 *
 * @param columns number of grid columns, used to convert item indices to rows.
 */
@Composable
fun GlassScrollbar(
    state: LazyGridState,
    columns: Int,
    modifier: Modifier = Modifier,
    thumbWidth: androidx.compose.ui.unit.Dp = 20.dp,
    // Gap between the visible pill and the screen's right edge, so it's not
    // jammed against the border and is easy to grab.
    edgeMargin: androidx.compose.ui.unit.Dp = 10.dp,
    // Transparent finger hit area around the visible pill, so the glass
    // capsule is easy to grab and drag.
    touchWidth: androidx.compose.ui.unit.Dp = 48.dp,
    minThumbHeight: androidx.compose.ui.unit.Dp = 56.dp,
    hideDelayMillis: Long = 1200L
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val spacingPx = with(density) { 4.dp.toPx() }
    val minThumbPx = with(density) { minThumbHeight.toPx() }

    // Scroll geometry derived from the lazy layout, recomputed only when the
    // relevant layout info changes.
    val metrics by remember(state, columns) {
        derivedStateOf { computeMetrics(state, columns, spacingPx, minThumbPx) }
    }

    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    // Show on scroll/drag; fade out after an idle delay.
    LaunchedEffectShowHide(
        active = state.isScrollInProgress || dragging,
        hideDelayMillis = hideDelayMillis,
        onChange = { visible = it }
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible && metrics.canScroll) 1f else 0f,
        label = "scrollbarAlpha"
    )

    // Track height in px, captured to map drag deltas to scroll positions.
    var trackHeightPx by remember { mutableFloatStateOf(0f) }

    val dark = isSystemInDarkTheme()
    val backdrop = LocalGlassBackdrop.current
    val surface = if (dark) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.55f)
    val shape = Capsule()

    val thumbGlass = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(1f.dp.toPx())
                lens(6f.dp.toPx(), 12f.dp.toPx())
            },
            onDrawSurface = { drawRect(surface) }
        )
    } else {
        val border = if (dark) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.7f)
        Modifier
            .clip(shape)
            .background(surface)
            .border(BorderStroke(1.dp, border), shape)
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(touchWidth)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
    ) {
        val thumbPx = (trackHeightPx * metrics.thumbFraction)
            .coerceIn(minThumbPx, trackHeightPx.coerceAtLeast(minThumbPx))
        val travel = (trackHeightPx - thumbPx).coerceAtLeast(0f)
        val thumbTopPx = travel * metrics.scrollFraction

        val draggableState = rememberDraggableState { delta ->
            if (travel <= 0f) return@rememberDraggableState
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val firstVisible = info.visibleItemsInfo.firstOrNull()
            if (total == 0 || firstVisible == null) return@rememberDraggableState

            // Pixel-accurate mapping: turn the thumb's new position into a target
            // content scroll offset, then split it into (row, in-row offset) so
            // the grid positions exactly there — smooth, 1:1 with the finger,
            // instead of jumping a whole row at a time.
            val rowHeight = firstVisible.size.height.coerceAtLeast(1)
            val rowPitch = rowHeight + spacingPx
            val totalRows = ceil(total / columns.toFloat()).toInt()
            val viewportPx = (info.viewportSize.height -
                info.beforeContentPadding - info.afterContentPadding).coerceAtLeast(1)
            val totalContentPx = totalRows * rowPitch - spacingPx
            val maxScrollPx = (totalContentPx - viewportPx).coerceAtLeast(1f)

            val newTop = (thumbTopPx + delta).coerceIn(0f, travel)
            val targetScrollPx = (newTop / travel) * maxScrollPx
            val targetRow = (targetScrollPx / rowPitch).toInt().coerceAtLeast(0)
            val rowOffset = (targetScrollPx - targetRow * rowPitch)
                .roundToInt().coerceAtLeast(0)
            val targetIndex = (targetRow * columns).coerceIn(0, total - 1)
            scope.launch { state.scrollToItem(targetIndex, rowOffset) }
        }

        // Wide, transparent hit target for comfortable finger dragging; the
        // visible thin glass pill sits at its trailing (right) edge.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                .width(touchWidth)
                .height(with(density) { thumbPx.toDp() })
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    enabled = metrics.canScroll,
                    onDragStarted = { dragging = true },
                    onDragStopped = { dragging = false }
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .padding(end = edgeMargin)
                    .width(thumbWidth)
                    .fillMaxHeight()
                    .graphicsLayer { this.alpha = alpha }
                    .then(thumbGlass),
                contentAlignment = Alignment.Center
            ) {
                // Three short horizontal grip lines for a tactile, "grippy" look.
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(thumbWidth * 0.5f)
                                .height(1.5.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(Color.White)
                        )
                    }
                }
            }
        }
    }
}

private data class ScrollbarMetrics(
    val thumbFraction: Float,
    val scrollFraction: Float,
    val canScroll: Boolean
)

/**
 * Estimates the thumb size (as a fraction of the track) and the scroll position
 * (0..1) from the grid's [layoutInfo][LazyGridState.layoutInfo], assuming a
 * uniform row height (true for a fixed-column grid of square cells).
 */
private fun computeMetrics(
    state: LazyGridState,
    columns: Int,
    spacingPx: Float,
    minThumbPx: Float
): ScrollbarMetrics {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total == 0 || visible.isEmpty()) return ScrollbarMetrics(1f, 0f, false)

    val rowHeight = visible.first().size.height.coerceAtLeast(1)
    val rowPitch = rowHeight + spacingPx
    val totalRows = ceil(total / columns.toFloat()).toInt()
    val totalContentPx = totalRows * rowPitch - spacingPx

    val viewportPx = (info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding)
        .coerceAtLeast(1)

    if (totalContentPx <= viewportPx) return ScrollbarMetrics(1f, 0f, false)

    val firstRow = state.firstVisibleItemIndex / columns
    val scrolledPx = firstRow * rowPitch + state.firstVisibleItemScrollOffset
    val maxScrollPx = (totalContentPx - viewportPx).coerceAtLeast(1f)

    val scrollFraction = (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
    val thumbFraction = (viewportPx / totalContentPx).coerceIn(0.06f, 1f)
    return ScrollbarMetrics(thumbFraction, scrollFraction, true)
}

/**
 * Small helper effect: sets visible=true while [active]; otherwise waits
 * [hideDelayMillis] then sets visible=false.
 */
@Composable
private fun LaunchedEffectShowHide(
    active: Boolean,
    hideDelayMillis: Long,
    onChange: (Boolean) -> Unit
) {
    androidx.compose.runtime.LaunchedEffect(active) {
        if (active) {
            onChange(true)
        } else {
            delay(hideDelayMillis)
            onChange(false)
        }
    }
}
