package com.photovault.ui.theme.liquid

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Ported from the Backdrop catalog. Drives the liquid controls' value + press +
 * squish animations from a drag gesture. The only platform tweaks vs. the catalog
 * source: the monotonic clock uses [SystemClock.uptimeMillis] and the frame await
 * uses Compose's [withFrameNanos] (instead of the catalog's expect/actual).
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    // Guards the press/release "squish" transition. scaleX and scaleY use
    // different spring specs (for a subtle jelly bounce), so they must be driven
    // together by a single, cancelable operation. Without this, rapid taps let a
    // press() and a release() race per-axis and the thumb can get stuck stretched
    // into a horizontal oval (scaleX chasing pressedScale while scaleY chases
    // initialScale, or vice-versa). The mutex makes the latest transition the sole
    // winner for BOTH axes at once, so they always share the same target.
    private val scaleMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            }
        ) { change, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            // Drive pressProgress + both scale axes as ONE cancelable transition so
            // a later release() (or press()) replaces them together, never leaving
            // the axes mismatched.
            scaleMutex.mutate {
                val p = launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                val x = launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
                val y = launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
                p.join(); x.join(); y.join()
            }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos {}
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            // Acquire the mutex only after the value has settled so the pressed
            // "squish" holds during the slide, then restore both axes together.
            scaleMutex.mutate {
                val p = launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                val x = launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
                val y = launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
                p.join(); x.join(); y.join()
            }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                // Always settle velocity back to 0. The previous `velocity != 0f`
                // guard could skip the reset while velocity was mid-oscillation
                // (momentarily 0 but still animating toward a non-zero target),
                // leaving the thumb stuck in the horizontal velocity squish.
                velocityTracker.resetTracking()
                if (velocity != 0f || velocityAnimation.targetValue != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            SystemClock.uptimeMillis(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
