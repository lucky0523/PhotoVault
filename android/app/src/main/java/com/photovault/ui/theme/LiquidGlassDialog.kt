package com.photovault.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/**
 * A liquid-glass confirmation dialog styled after the Backdrop catalog's
 * `DialogContent`: a large [RoundedRectangle] glass panel (blur + lens
 * refraction + plain highlight) floating over a dimmed scrim, with a title,
 * body text and a row of capsule action buttons.
 *
 * Because a [Dialog] renders in its own window it can't sample the app's main
 * backdrop, so the panel refracts a self-captured scrim layer. The frosted
 * surface tint, highlight and border carry the glass look; on API levels where
 * the `RenderEffect`-based effects no-op, it degrades to the tinted panel.
 */
@Composable
fun LiquidGlassDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    dismissOnClickOutside: Boolean = true,
    buttons: @Composable RowScope.() -> Unit
) {
    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.9f)
        else Color(0xFF121212).copy(0.8f)
    val dimColor =
        if (isLightTheme) Color(0xFF29293A).copy(0.23f)
        else Color(0xFF121212).copy(0.56f)

    val scrimBackdrop = rememberLayerBackdrop()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnClickOutside
        )
    ) {
        // The scrim-capture layer and the glass panel MUST be siblings, never
        // parent/child. If the panel (which samples `scrimBackdrop` via
        // drawBackdrop) lived *inside* the layer captured by `layerBackdrop`,
        // the backdrop would reference a RenderNode that contains itself and the
        // render tree would recurse infinitely (StackOverflow / native crash).
        Box(modifier = Modifier.fillMaxSize()) {
            // Sibling 1: full-screen dim scrim, recorded into the backdrop so the
            // panel has something to refract. Tapping it dismisses (when enabled).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(scrimBackdrop)
                    .drawWithContent {
                        drawContent()
                        drawRect(dimColor)
                    }
                    .then(
                        if (dismissOnClickOutside) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismissRequest
                            )
                        } else {
                            Modifier
                        }
                    )
            )

            // Sibling 2: the glass panel, centered. Only wraps its own content
            // height so taps outside it still reach the scrim to dismiss.
            Column(
                modifier = modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth()
                    // Swallow taps on the panel so they don't dismiss the dialog.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .drawBackdrop(
                        backdrop = scrimBackdrop,
                        shape = { RoundedRectangle(32f.dp) },
                        effects = {
                            vibrancy()
                            blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                            lens(20f.dp.toPx(), 40f.dp.toPx())
                        },
                        highlight = { Highlight.Plain },
                        onDrawSurface = { drawRect(containerColor) }
                    )
            ) {
                BasicTitleText(title, contentColor)
                BasicBodyText(text, contentColor)

                Row(
                    modifier = Modifier
                        .padding(20.dp, 12.dp, 20.dp, 20.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons
                )
            }
        }
    }
}

@Composable
private fun BasicTitleText(title: String, contentColor: Color) {
    androidx.compose.foundation.text.BasicText(
        text = title,
        modifier = Modifier.padding(24.dp, 24.dp, 24.dp, 8.dp),
        style = TextStyle(contentColor, 22f.sp, FontWeight.Medium)
    )
}

@Composable
private fun BasicBodyText(text: String, contentColor: Color) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = Modifier.padding(24.dp, 8.dp, 24.dp, 12.dp),
        style = TextStyle(contentColor.copy(0.68f), 15f.sp)
    )
}

/** Visual role of a [LiquidDialogButton]. */
enum class LiquidDialogButtonStyle { Accent, Neutral, Destructive }

/**
 * A capsule action button for [LiquidGlassDialog]. Placed inside the dialog's
 * `buttons` [RowScope]; each button takes an equal share of the row width.
 */
@Composable
fun RowScope.LiquidDialogButton(
    text: String,
    onClick: () -> Unit,
    style: LiquidDialogButtonStyle = LiquidDialogButtonStyle.Neutral,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val destructiveColor = if (isLightTheme) Color(0xFFE5484D) else Color(0xFFFF5A5F)
    val neutralFill =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.35f)
        else Color(0xFFFFFFFF).copy(0.12f)

    val (fill, labelColor) = when (style) {
        LiquidDialogButtonStyle.Accent -> accentColor to Color.White
        LiquidDialogButtonStyle.Destructive -> destructiveColor to Color.White
        LiquidDialogButtonStyle.Neutral -> neutralFill to contentColor
    }

    Row(
        modifier = modifier
            .weight(1f)
            .clip(Capsule())
            .background(fill)
            .clickable(onClick = onClick)
            .height(48.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.text.BasicText(
            text = text,
            style = TextStyle(labelColor, 16f.sp, FontWeight.Medium, textAlign = TextAlign.Center),
            maxLines = 1
        )
    }
}
