package com.huoyi.photovault.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class SemanticTone { Success, Info, Warning, Danger }

@Immutable
data class SemanticColorSet(
    val accent: Color,
    val container: Color,
    val foreground: Color
)

fun semanticColorSet(tone: SemanticTone, darkTheme: Boolean): SemanticColorSet =
    if (darkTheme) when (tone) {
        SemanticTone.Success -> SemanticColorSet(Color(0xFF6FF0A6), Color(0xFF2D382F), Color(0xFF6FF0A6))
        SemanticTone.Info -> SemanticColorSet(Color(0xFFA8C7FA), Color(0xFF35323C), Color(0xFFA8C7FA))
        SemanticTone.Warning -> SemanticColorSet(Color(0xFFFFD47A), Color(0xFF423428), Color(0xFFFFD47A))
        SemanticTone.Danger -> SemanticColorSet(Color(0xFFFFB7C2), Color(0xFF423033), Color(0xFFFFB7C2))
    } else when (tone) {
        SemanticTone.Success -> SemanticColorSet(Color(0xFF00D066), Color(0xFFD9F8E8), Color(0xFF00391C))
        SemanticTone.Info -> SemanticColorSet(Color(0xFF2B67A5), Color(0xFFDFE8F2), Color(0xFF173E69))
        SemanticTone.Warning -> SemanticColorSet(Color(0xFFFFA91F), Color(0xFFFFF2DD), Color(0xFF422B00))
        SemanticTone.Danger -> SemanticColorSet(Color(0xFFFF5C72), Color(0xFFFFE7EA), Color(0xFF5B101B))
    }

@Composable
fun semanticColorSet(tone: SemanticTone): SemanticColorSet =
    semanticColorSet(tone, LocalPhotoVaultDarkTheme.current)

/**
 * Shared "soft" status look used by the folder-detail filter pills and
 * [com.huoyi.photovault.ui.main.components.StatusChip]: a faint translucent
 * tint of the status [accent] as the background, with the vivid accent itself
 * as the text/dot color. Kept faint so it never competes with the label.
 */
fun statusSoftFill(accent: Color, darkTheme: Boolean): Color =
    accent.copy(alpha = if (darkTheme) 0.16f else 0.10f)

@Composable
fun statusSoftFill(accent: Color): Color =
    statusSoftFill(accent, LocalPhotoVaultDarkTheme.current)

object PhotoBadgeColors {
    val container = Color(0xFF21181A)
    fun foreground(tone: SemanticTone): Color = when (tone) {
        SemanticTone.Success -> Color(0xFF6FF0A6)
        SemanticTone.Warning -> Color(0xFFFFD47A)
        SemanticTone.Danger -> Color(0xFFFFB7C2)
        SemanticTone.Info -> Color(0xFFA8C7FA)
    }
}
