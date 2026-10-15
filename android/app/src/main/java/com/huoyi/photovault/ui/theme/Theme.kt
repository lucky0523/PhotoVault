package com.huoyi.photovault.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val YingjiTone0 = Color(0xFF000000)
val YingjiTone10 = Color(0xFF40000E)
val YingjiTone20 = Color(0xFF67001C)
val YingjiTone30 = Color(0xFF91002B)
val YingjiTone40 = Color(0xFFBB103C)
val YingjiTone50 = Color(0xFFDE3152)
val YingjiTone60 = Color(0xFFFF506B)
val YingjiTone70 = Color(0xFFFF8792)
val YingjiTone80 = Color(0xFFFFB2B7)
val YingjiTone90 = Color(0xFFFFDADB)
val YingjiTone95 = Color(0xFFFFECEC)
val YingjiTone99 = Color(0xFFFFFBFF)
val YingjiTone100 = Color(0xFFFFFFFF)
val LogoBright = Color(0xFFF0464F)
val BrandSeed = Color(0xFFD92D4F)
val LogoDeep = Color(0xFFB3123A)

object PhotoVaultColors {
    val Slate = Color(0xFF211A1B)
    val SoftSlate = Color(0xFF58677E)
}

val LocalPhotoVaultDarkTheme = staticCompositionLocalOf { false }

internal val LightColorScheme = lightColorScheme(
    primary = YingjiTone40, onPrimary = Color.White,
    primaryContainer = YingjiTone90, onPrimaryContainer = YingjiTone10,
    inversePrimary = YingjiTone80,
    secondary = Color(0xFF00D066), onSecondary = Color(0xFF00391C),
    secondaryContainer = Color(0xFFB8F5D0), onSecondaryContainer = Color(0xFF002110),
    tertiary = Color(0xFFFFA91F), onTertiary = Color(0xFF271900),
    tertiaryContainer = Color(0xFFFFECB8), onTertiaryContainer = Color(0xFF4A3100),
    error = Color(0xFFFF5C72), onError = Color(0xFF5B101B),
    errorContainer = Color(0xFFFFDCE2), onErrorContainer = Color(0xFF5B101B),
    // 页面背景保持纯白；容器色阶用中性浅灰，避免与白底并列时发粉。
    background = Color.White, onBackground = Color(0xFF211A1B),
    surface = Color.White, onSurface = Color(0xFF211A1B),
    surfaceVariant = Color(0xFFE5E5E5), onSurfaceVariant = Color(0xFF524345),
    outline = Color(0xFF857375), outlineVariant = Color(0xFFD8C2C5),
    scrim = Color.Black, inverseSurface = Color(0xFF362F30),
    inverseOnSurface = Color(0xFFFBEEEE), surfaceTint = YingjiTone40,
    surfaceDim = Color(0xFFDCDCDC), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F6F6),
    surfaceContainer = Color(0xFFF1F1F1), surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    primaryFixed = YingjiTone90, primaryFixedDim = YingjiTone80,
    onPrimaryFixed = YingjiTone10, onPrimaryFixedVariant = YingjiTone30,
    secondaryFixed = Color(0xFFB8F5D0), secondaryFixedDim = Color(0xFF6FF0A6),
    onSecondaryFixed = Color(0xFF002110), onSecondaryFixedVariant = Color(0xFF00522A),
    tertiaryFixed = Color(0xFFFFECB8), tertiaryFixedDim = Color(0xFFFFD47A),
    onTertiaryFixed = Color(0xFF271900), onTertiaryFixedVariant = Color(0xFF4A3100)
)

internal val DarkColorScheme = darkColorScheme(
    primary = YingjiTone80, onPrimary = YingjiTone20,
    primaryContainer = YingjiTone30, onPrimaryContainer = YingjiTone90,
    inversePrimary = YingjiTone40,
    secondary = Color(0xFF6FF0A6), onSecondary = Color(0xFF00391C),
    secondaryContainer = Color(0xFF00522A), onSecondaryContainer = Color(0xFFB8F5D0),
    tertiary = Color(0xFFFFD47A), onTertiary = Color(0xFF422B00),
    tertiaryContainer = Color(0xFF815600), onTertiaryContainer = Color(0xFFFFECB8),
    error = Color(0xFFFFB7C2), onError = Color(0xFF65101D),
    errorContainer = Color(0xFF982636), onErrorContainer = Color(0xFFFFDADF),
    background = Color(0xFF1A1113), onBackground = Color(0xFFF0DEE0),
    surface = Color(0xFF1A1113), onSurface = Color(0xFFF0DEE0),
    surfaceVariant = Color(0xFF524345), onSurfaceVariant = Color(0xFFD8C2C5),
    outline = Color(0xFFA08C8F), outlineVariant = Color(0xFF524345),
    scrim = Color.Black, inverseSurface = Color(0xFFF0DEE0),
    inverseOnSurface = Color(0xFF362F30), surfaceTint = YingjiTone80,
    surfaceDim = Color(0xFF1A1113), surfaceBright = Color(0xFF403638),
    surfaceContainerLowest = Color(0xFF140C0E), surfaceContainerLow = Color(0xFF21181A),
    surfaceContainer = Color(0xFF251C1E), surfaceContainerHigh = Color(0xFF302628),
    surfaceContainerHighest = Color(0xFF3B3033),
    primaryFixed = YingjiTone90, primaryFixedDim = YingjiTone80,
    onPrimaryFixed = YingjiTone10, onPrimaryFixedVariant = YingjiTone30,
    secondaryFixed = Color(0xFFB8F5D0), secondaryFixedDim = Color(0xFF6FF0A6),
    onSecondaryFixed = Color(0xFF002110), onSecondaryFixedVariant = Color(0xFF00522A),
    tertiaryFixed = Color(0xFFFFECB8), tertiaryFixedDim = Color(0xFFFFD47A),
    onTertiaryFixed = Color(0xFF271900), onTertiaryFixedVariant = Color(0xFF4A3100)
)

@Composable
fun PhotoVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val controller = WindowCompat.getInsetsController((view.context as Activity).window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
    CompositionLocalProvider(LocalPhotoVaultDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme, content = content)
    }
}
