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

/**
 * PhotoVault brand palette.
 *
 * 色彩语言：
 * - Vault Blue: 可信赖的保险库蓝，用于主操作、当前导航与可点击强调。
 * - Sync Green: 鲜活翡翠绿，用于成功、已备份与设备连接。
 * - Archive Amber: 暖琥珀，用于待处理、扫描中与回收站提醒。
 * - Delete Rose: 克制玫红，用于删除、失败与不可逆风险。
 * - Mist Surface: 冷亮冰蓝中性色，用于玻璃背景和容器。
 */
object PhotoVaultColors {
    val VaultBlue = Color(0xFF2F6BFF)
    val VaultBlueDark = Color(0xFFA8C2FF)
    val DeepVault = Color(0xFF0B1B3A)
    val SyncGreen = Color(0xFF00D066)
    val SyncGreenDark = Color(0xFF6FF0A6)
    val ArchiveAmber = Color(0xFFFFA91F)
    val ArchiveAmberDark = Color(0xFFFFD47A)
    val DeleteRose = Color(0xFFFF5C72)
    val DeleteRoseDark = Color(0xFFFFB7C2)
    val Mist = Color(0xFFF7FAFF)
    val MistDark = Color(0xFF0D1724)
    val Slate = Color(0xFF14233A)
    val SoftSlate = Color(0xFF58677E)
}

private val LightColorScheme = lightColorScheme(
    primary = PhotoVaultColors.VaultBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EAFF),
    onPrimaryContainer = Color(0xFF071A4D),
    secondary = PhotoVaultColors.SyncGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F5D0),
    onSecondaryContainer = Color(0xFF002110),
    tertiary = PhotoVaultColors.ArchiveAmber,
    onTertiary = Color(0xFF271900),
    tertiaryContainer = Color(0xFFFFECB8),
    onTertiaryContainer = Color(0xFF4A3100),
    error = PhotoVaultColors.DeleteRose,
    onError = Color.White,
    errorContainer = Color(0xFFFFDCE2),
    onErrorContainer = Color(0xFF5B101B),
    background = PhotoVaultColors.Mist,
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE4ECF8),
    onSurfaceVariant = PhotoVaultColors.SoftSlate,
    outline = Color(0xFF96A5BA),
    outlineVariant = Color(0xFFC7D3E2),
    inverseSurface = PhotoVaultColors.Slate,
    inverseOnSurface = Color(0xFFF4F8FA),
    inversePrimary = Color(0xFFAEC3FF),
    scrim = Color(0xFF07111F),
)

private val DarkColorScheme = darkColorScheme(
    primary = PhotoVaultColors.VaultBlueDark,
    onPrimary = Color(0xFF09245F),
    primaryContainer = Color(0xFF1644B3),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = PhotoVaultColors.SyncGreenDark,
    onSecondary = Color(0xFF00391C),
    secondaryContainer = Color(0xFF00522A),
    onSecondaryContainer = Color(0xFFB8F5D0),
    tertiary = PhotoVaultColors.ArchiveAmberDark,
    onTertiary = Color(0xFF422B00),
    tertiaryContainer = Color(0xFF815600),
    onTertiaryContainer = Color(0xFFFFECB8),
    error = PhotoVaultColors.DeleteRoseDark,
    onError = Color(0xFF65101D),
    errorContainer = Color(0xFF982636),
    onErrorContainer = Color(0xFFFFDADF),
    background = PhotoVaultColors.MistDark,
    onBackground = Color(0xFFE8EEF6),
    surface = Color(0xFF132033),
    onSurface = Color(0xFFE8EEF6),
    surfaceVariant = Color(0xFF26364B),
    onSurfaceVariant = Color(0xFFC3CFDF),
    outline = Color(0xFF8190A5),
    outlineVariant = Color(0xFF394A61),
    inverseSurface = Color(0xFFE8EEF6),
    inverseOnSurface = Color(0xFF172033),
    inversePrimary = PhotoVaultColors.VaultBlue,
    scrim = Color(0xFF050A10),
)

@Composable
fun PhotoVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
