package com.photovault.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4C6FFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF00164E),
    secondary = Color(0xFF5A6BB0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E5FF),
    onSecondaryContainer = Color(0xFF141B45),
    tertiary = Color(0xFF00A98F),
    onTertiary = Color.White,
    error = Color(0xFFE5484D),
    onError = Color.White,
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF191B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191B22),
    surfaceVariant = Color(0xFFE6E8F1),
    onSurfaceVariant = Color(0xFF5A5D68),
    outline = Color(0xFFB7BAC7),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFAEBEFF),
    onPrimary = Color(0xFF00218B),
    primaryContainer = Color(0xFF2A3A93),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFFBCC4F0),
    onSecondary = Color(0xFF262E5F),
    secondaryContainer = Color(0xFF3B4377),
    onSecondaryContainer = Color(0xFFE1E5FF),
    tertiary = Color(0xFF54DBC0),
    onTertiary = Color(0xFF003730),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF11131A),
    onBackground = Color(0xFFE4E6EF),
    surface = Color(0xFF171A22),
    onSurface = Color(0xFFE4E6EF),
    surfaceVariant = Color(0xFF2A2E3A),
    onSurfaceVariant = Color(0xFFB9BCCB),
    outline = Color(0xFF474B58),
)

@Composable
fun PhotoVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge is enabled via enableEdgeToEdge() in the Activity, which
            // makes the system bars transparent. Setting statusBarColor /
            // navigationBarColor is deprecated (Android 15+), so we only control the
            // bar icon appearance (light vs dark) here.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
