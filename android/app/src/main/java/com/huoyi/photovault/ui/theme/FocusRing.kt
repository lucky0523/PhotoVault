package com.huoyi.photovault.ui.theme

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp

enum class FocusRingSeparation { Transparent, Painted }

fun Modifier.photoVaultFocusRing(
    shape: Shape,
    interactionSource: InteractionSource,
    separation: FocusRingSeparation = FocusRingSeparation.Painted,
): Modifier = composed {
    val focused by interactionSource.collectIsFocusedAsState()
    val dark = LocalPhotoVaultDarkTheme.current
    if (!focused) this else drawWithContent {
        val two = 2.dp.toPx()
        val brand = if (dark) YingjiTone80 else YingjiTone40
        val outline = shape.createOutline(size, layoutDirection, this)

        fun drawOutlineStroke(color: Color, width: Float) {
            when (outline) {
                is Outline.Rectangle -> drawRect(
                    color = color,
                    topLeft = outline.rect.topLeft,
                    size = outline.rect.size,
                    style = Stroke(width = width)
                )
                is Outline.Rounded -> drawPath(
                    Path().apply { addRoundRect(outline.roundRect) },
                    color = color,
                    style = Stroke(width = width)
                )
                is Outline.Generic -> drawPath(outline.path, color = color, style = Stroke(width = width))
            }
        }

        if (separation == FocusRingSeparation.Painted) {
            drawContent()
            // Clip both halo strokes to the outside of the original outline.
            // Their shared centerline guarantees concentric 2dp bands even for
            // transparent controls and arbitrary rounded/generic Shapes.
            when (outline) {
                is Outline.Rectangle -> clipRect(
                    outline.rect.left,
                    outline.rect.top,
                    outline.rect.right,
                    outline.rect.bottom,
                    clipOp = ClipOp.Difference
                ) {
                    drawOutlineStroke(Color.Black, two * 4f)
                    drawOutlineStroke(Color.White, two * 2f)
                }
                is Outline.Rounded -> clipPath(
                    Path().apply { addRoundRect(outline.roundRect) },
                    clipOp = ClipOp.Difference
                ) {
                    drawOutlineStroke(Color.Black, two * 4f)
                    drawOutlineStroke(Color.White, two * 2f)
                }
                is Outline.Generic -> clipPath(outline.path, clipOp = ClipOp.Difference) {
                    drawOutlineStroke(Color.Black, two * 4f)
                    drawOutlineStroke(Color.White, two * 2f)
                }
            }
        } else {
            // Transparent is reserved for known static backgrounds. Its outer
            // ring remains centered 3dp outside for backwards compatibility.
            drawContent()
            val outer = shape.createOutline(
                androidx.compose.ui.geometry.Size(size.width + two * 6f, size.height + two * 6f),
                layoutDirection,
                this
            )
            when (outer) {
                is Outline.Rectangle -> drawRect(
                    brand,
                    topLeft = outer.rect.topLeft - androidx.compose.ui.geometry.Offset(two * 3f, two * 3f),
                    size = outer.rect.size,
                    style = Stroke(two)
                )
                is Outline.Rounded -> {
                    val path = Path().apply { addRoundRect(outer.roundRect) }
                    drawContext.canvas.save()
                    drawContext.canvas.translate(-two * 3f, -two * 3f)
                    drawPath(path, brand, style = Stroke(two))
                    drawContext.canvas.restore()
                }
                is Outline.Generic -> {
                    drawContext.canvas.save()
                    drawContext.canvas.translate(-two * 3f, -two * 3f)
                    drawPath(outer.path, brand, style = Stroke(two))
                    drawContext.canvas.restore()
                }
            }
        }

        // The brand stroke is clipped to the original component outline, so
        // only its inner 2dp half remains. It shares the exact same path as both
        // halo bands and is therefore concentric for every Shape implementation.
        when (outline) {
            is Outline.Rectangle -> clipRect(
                outline.rect.left,
                outline.rect.top,
                outline.rect.right,
                outline.rect.bottom
            ) { drawOutlineStroke(brand, two * 2f) }
            is Outline.Rounded -> clipPath(Path().apply { addRoundRect(outline.roundRect) }) {
                drawOutlineStroke(brand, two * 2f)
            }
            is Outline.Generic -> clipPath(outline.path) {
                drawOutlineStroke(brand, two * 2f)
            }
        }
    }
}
