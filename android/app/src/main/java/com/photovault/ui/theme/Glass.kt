package com.photovault.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/**
 * Liquid-glass design tokens and building blocks.
 *
 * Compose can't cheaply blur what's *behind* a surface, so the glass look is
 * approximated with translucent layered fills, a soft top highlight, a hairline
 * border and gentle elevation. Placed over the app's tinted gradient background
 * this reads as frosted glass.
 */
object Glass {
    val CornerLarge = 24.dp
    val CornerMedium = 20.dp
    val CornerSmall = 14.dp
}

/**
 * Vertical gradient used as the app backdrop so translucent glass surfaces have
 * something colourful to sit on top of.
 */
@Composable
fun appBackgroundBrush(): Brush {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        // Neutral dark grey, with a barely-there vertical shade so glass surfaces
        // still have some gradient to refract.
        Brush.verticalGradient(
            listOf(
                Color(0xFF1B1C1E),
                Color(0xFF141517)
            )
        )
    } else {
        // Clean light grey base.
        Brush.verticalGradient(
            listOf(
                Color(0xFFF6F7F9),
                Color(0xFFEDEFF2)
            )
        )
    }
}

/**
 * A frosted-glass card container. Use for list items, headers and bars.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Glass.CornerLarge),
    elevation: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isSystemInDarkTheme()
    // Keep the fill uniform (small alpha spread) so the card reads as one even
    // frosted pane rather than fading to opaque white at the bottom.
    val fill = if (dark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.06f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.38f),
                Color.White.copy(alpha = 0.28f)
            )
        )
    }
    val borderColor = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.65f)

    Box(
        modifier = modifier
            .shadow(elevation, shape, clip = true)
            .background(fill)
            .border(BorderStroke(1.dp, borderColor), shape),
        content = content
    )
}

/**
 * The active [Backdrop] used by real liquid-glass chrome (header pill, bottom
 * bar). It is provided at the app shell where a full-screen [layerBackdrop]
 * captures the gradient + content beneath the floating chrome.
 *
 * `null` means no backdrop is set up in the current subtree (e.g. the login
 * screen), in which case [GlassBar] falls back to the translucent [GlassCard].
 */
val LocalGlassBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * Bottom inset (navigation bar + floating tab bar footprint) that scrollable tab
 * content should add to its bottom `contentPadding`, so the last items can scroll
 * clear of the floating [LiquidGlassBottomTabs] instead of being hidden behind it.
 */
val LocalBottomBarPadding = compositionLocalOf { 0.dp }

/**
 * A real liquid-glass surface intended for *floating chrome* that sits on top of
 * (and is a sibling of) the content captured by [LocalGlassBackdrop] — e.g. the
 * top pill and the bottom navigation bar.
 *
 * It refracts and blurs whatever is behind it via the Backdrop library. Because
 * the effects are `RenderEffect`-based they only kick in on Android 12+ (blur)
 * and Android 13+ (lens/refraction); on older devices `drawBackdrop` simply
 * draws the captured backdrop with the surface tint, and when no backdrop is
 * available at all it degrades to the translucent [GlassCard] look.
 *
 * Do NOT use this for elements that live *inside* the captured content (such as
 * list items) — sampling a backdrop that contains the element itself produces
 * feedback artifacts. Use [GlassCard] there instead.
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Glass.CornerLarge),
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null) {
        GlassCard(modifier = modifier, shape = shape, content = content)
        return
    }

    val dark = isSystemInDarkTheme()
    // A light frosted wash keeps text/icons legible over the refracted backdrop.
    val surfaceTint = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.24f)
    // lens() requires a CornerBasedShape (RoundedCornerShape / CircleShape);
    // guard so an arbitrary shape can never throw at draw time.
    val cornerShaped = shape is CornerBasedShape

    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(8f.dp.toPx())
                if (cornerShaped) {
                    lens(12f.dp.toPx(), 20f.dp.toPx())
                }
            },
            onDrawSurface = { drawRect(surfaceTint) }
        ),
        content = content
    )
}

/**
 * A refractive liquid-glass card, styled after the Backdrop catalog's
 * `LazyScrollContainerContent`: a smooth [RoundedRectangle] with a pure
 * vibrancy + lens (refraction) effect over the app background — no flat surface
 * fill. Content is drawn on top of the refracted glass.
 *
 * Like [GlassBar], this samples [LocalGlassBackdrop] (the gradient-only backdrop
 * source), so it must NOT be used for elements that are themselves part of the
 * captured backdrop. When no backdrop is available (or on old API levels where
 * effects no-op) it degrades to the translucent [GlassCard].
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 32.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null) {
        GlassCard(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            content = content
        )
        return
    }

    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedRectangle(cornerRadius) },
            effects = {
                vibrancy()
                lens(16f.dp.toPx(), 32f.dp.toPx())
            }
        ),
        content = content
    )
}

/**
 * A capsule "Surface Liquid Button", styled after the Backdrop catalog's
 * `ButtonsContent` → Surface Liquid Button: a [Capsule] glass surface with a
 * translucent white fill (`surfaceColor`) over a vibrancy + blur + lens
 * refraction of the backdrop.
 *
 * Samples [LocalGlassBackdrop] (the gradient-only source) so it never samples
 * itself; falls back to a translucent capsule when no backdrop is available.
 * Size and content padding are controlled by the caller via [modifier]
 * (e.g. `Modifier.size(56.dp)` for an icon button, or
 * `Modifier.height(48.dp).padding(horizontal = 16.dp)` for a text pill).
 */
@Composable
fun SurfaceLiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    surfaceColor: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isSystemInDarkTheme()
    val surface = surfaceColor ?: if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.30f)
    val shape: Shape = Capsule()

    val surfaceModifier = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(2f.dp.toPx())
                lens(12f.dp.toPx(), 24f.dp.toPx())
            },
            onDrawSurface = { drawRect(surface) }
        )
    } else {
        val border = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.6f)
        Modifier
            .shadow(6.dp, shape, clip = false)
            .clip(shape)
            .background(surface)
            .border(BorderStroke(1.dp, border), shape)
    }

    Box(
        modifier = modifier
            .then(surfaceModifier)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}
