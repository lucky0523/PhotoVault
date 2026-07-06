package com.photovault.ui.theme.liquid

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Ported from the Backdrop catalog `InteractiveHighlight`: a touch-following
 * radial highlight used by the liquid bottom tabs.
 *
 * The catalog version relies on backdrop 2.x helpers (`RuntimeShader`,
 * `asComposeShader`, `isRuntimeShaderSupported`) that don't exist in 1.0.6, so
 * this uses the platform [android.graphics.RuntimeShader] directly (Android 13+)
 * and falls back to a flat highlight on older devices.
 */
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {

    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) createShader() else null

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                val shader = shader
                if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    drawShaderHighlight(shader, progress)
                } else {
                    drawRect(
                        Color.White.copy(alpha = 0.25f * progress),
                        blendMode = BlendMode.Plus
                    )
                }
            }
            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                }
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun ContentDrawScope.drawShaderHighlight(shader: RuntimeShader, progress: Float) {
        drawRect(Color.White.copy(alpha = 0.08f * progress), blendMode = BlendMode.Plus)
        val pos = position(size, positionAnimation.value)
        shader.setFloatUniform("size", size.width, size.height)
        shader.setColorUniform("color", Color.White.copy(alpha = 0.15f * progress).toArgb())
        shader.setFloatUniform("radius", size.minDimension * 1.5f)
        shader.setFloatUniform(
            "position",
            pos.x.fastCoerceIn(0f, size.width),
            pos.y.fastCoerceIn(0f, size.height)
        )
        drawRect(ShaderBrush(shader), blendMode = BlendMode.Plus)
    }

    private companion object {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun createShader(): RuntimeShader = RuntimeShader(SHADER_SRC)

        const val SHADER_SRC = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
    }
}
