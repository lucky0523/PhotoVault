package com.photovault.ui.theme.liquid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

/**
 * Ported from the Backdrop catalog: a single tab entry inside [LiquidBottomTabs].
 *
 * This is purely a visual slot (icon + label). Selection is driven by the
 * full-width gesture overlay in [LiquidBottomTabs], which handles both taps and
 * drags for every tab, so individual entries carry no click handler.
 */
@Composable
fun RowScope.LiquidBottomTab(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(0f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
