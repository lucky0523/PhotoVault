package com.photovault.ui.theme.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** How long the finger must rest on a tab before the thumb "grabs" to it. */
private const val LongPressGrabMs = 100L

/**
 * Ported from the Backdrop catalog `LiquidBottomTabs`: a liquid-glass tab bar with
 * a draggable, refracting thumb (with damped drag animation and an interactive
 * touch highlight). The selected tab can be tapped or dragged between.
 */
@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f)
        else Color(0xFF121212).copy(0.4f)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        // Drag bookkeeping: [0] = the tab index the gesture started on (the finger's
        // tab, snapped on press), [1] = total horizontal drag accumulated since then.
        // Seeding the origin from the press position lets a drag begin on ANY tab, so
        // the highlight thumb jumps under the finger and then tracks it.
        val dragOrigin = remember { floatArrayOf(0f, 0f) }
        var didDrag by remember { mutableStateOf(false) }
        val dampedDragAnimation = remember(animationScope) {
            // Timer that fires when the finger has been held long enough to count as a
            // long-press "grab" (see onDragStarted). Cancelled on release or as soon
            // as a real drag begins.
            var longPressJob: Job? = null
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = { position ->
                    val slot = (position.x / tabWidth).toInt()
                    val index = (if (isLtr) slot else tabsCount - 1 - slot)
                        .coerceIn(0, tabsCount - 1)
                    // Just record the pressed tab; don't teleport the value yet.
                    // Feeding an instant jump into updateValue() spikes the
                    // velocity tracker and distorts the shadow's scale.
                    dragOrigin[0] = index.toFloat()
                    dragOrigin[1] = 0f
                    didDrag = false
                    // Long-press to grab: if the finger keeps resting on a tab past
                    // the threshold (without lifting or dragging), glide the pressed
                    // thumb under the finger so a drag can begin right from there.
                    longPressJob?.cancel()
                    longPressJob = animationScope.launch {
                        delay(LongPressGrabMs)
                        if (!didDrag) {
                            // Treat the grab as the drag origin so the subsequent
                            // drag continues smoothly from the finger's tab.
                            didDrag = true
                            slideValueTo(dragOrigin[0])
                        }
                    }
                },
                onDragStopped = {
                    longPressJob?.cancel()
                    val targetIndex = if (didDrag) {
                        targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    } else {
                        // Plain tap: resolve directly to the pressed tab without
                        // ever animating the value through updateValue().
                        dragOrigin[0].fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    }
                    didDrag = false
                    if (targetIndex != currentIndex) {
                        // Index changed: updating currentIndex drives the snapshotFlow
                        // effect below, which runs animateToValue() and notifies
                        // onTabSelected(). Calling animateToValue() here as well would
                        // fire a duplicate press/release squish cycle, so we don't.
                        currentIndex = targetIndex
                    } else {
                        // Same tab (e.g. dragged a little and released back onto it):
                        // the effect won't fire, so settle the thumb onto the integer
                        // slot directly.
                        animateToValue(targetIndex.toFloat())
                    }
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    // inspectDragGestures fires one zero-delta onDrag on every press.
                    // Only react once there's REAL horizontal movement: feeding
                    // updateValue() here (as the old code did for the zero-delta call)
                    // spins up the velocity tracker even for a plain tap, and the
                    // leftover velocity squishes the selected thumb into a horizontal
                    // oval via the layerBlock's velocity term. Pure taps are resolved
                    // in onDragStopped via animateToValue(), which carries no velocity.
                    if (dragAmount.x != 0f || didDrag) {
                        // A real drag started: the long-press grab is no longer needed.
                        longPressJob?.cancel()
                        if (!didDrag) {
                            didDrag = true
                            // First real movement: seed the drag from the pressed tab.
                            updateValue(dragOrigin[0])
                        }
                        dragOrigin[1] += dragAmount.x
                        val delta = dragOrigin[1] / tabWidth * if (isLtr) 1f else -1f
                        updateValue(
                            (dragOrigin[0] + delta).fastCoerceIn(0f, (tabsCount - 1).toFloat())
                        )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                        }
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(
                                24f.dp.toPx() * progress,
                                24f.dp.toPx() * progress
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )

        // Transparent, full-width gesture layer sitting on top of the whole bar.
        // Because it spans every tab (not just the selected one), a press or drag
        // can start anywhere: `onDragStarted` snaps the thumb to the tab under the
        // finger and the interactive highlight follows the drag from there. It also
        // handles plain taps (press + release with no movement selects that tab),
        // which is why the tab items themselves no longer need a click handler.
        // Padding matches the tabs' 4dp inset so pointer x maps onto `tabWidth`.
        Box(
            Modifier
                .fillMaxWidth()
                .height(64f.dp)
                .padding(horizontal = 4f.dp)
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
        )
    }
}
