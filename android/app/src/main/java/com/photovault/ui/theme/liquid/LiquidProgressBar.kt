package com.photovault.ui.theme.liquid

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

/**
 * A determinate progress bar that reuses [LiquidSlider]'s track look — a capsule
 * track with a capsule accent fill — but drops the draggable liquid-glass thumb
 * (the "control block"). Purely a display indicator: no gestures, no thumb.
 *
 * The fill width animates so incremental progress (e.g. chunked uploads) reads as
 * a smooth liquid sweep rather than discrete jumps.
 *
 * @param progress current progress in `0f..1f` (values outside are clamped).
 * @param trackHeight thickness of the bar; matches [LiquidSlider]'s 6dp track.
 * @param trackColor the unfilled track color (defaults to the slider's track).
 * @param color the filled accent color (defaults to the theme primary).
 */
@Composable
fun LiquidProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 6.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
    color: Color = MaterialTheme.colorScheme.primary
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label = "liquidProgress"
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(Capsule())
            .background(trackColor)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = animatedProgress)
                .clip(Capsule())
                .background(color)
        )
    }
}
