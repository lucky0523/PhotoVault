package com.huoyi.photovault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.huoyi.photovault.ui.login.DarkLoginScrimAlphas
import com.huoyi.photovault.ui.login.LightLoginScrimAlphas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun lightSchemeMatchesApprovedRolesAndContrast() {
        assertScheme(
            LightColorScheme,
            core = listOf(
                "primary" to (0xFFBB103C to 0xFFFFFFFF),
                "primaryContainer" to (0xFFFFDADB to 0xFF40000E),
                "secondary" to (0xFF00D066 to 0xFF00391C),
                "secondaryContainer" to (0xFFB8F5D0 to 0xFF002110),
                "tertiary" to (0xFFFFA91F to 0xFF271900),
                "tertiaryContainer" to (0xFFFFECB8 to 0xFF4A3100),
                "error" to (0xFFFF5C72 to 0xFF5B101B),
                "errorContainer" to (0xFFFFDCE2 to 0xFF5B101B)
            ),
            expected = listOf(
                LightColorScheme.primary, LightColorScheme.primaryContainer,
                LightColorScheme.secondary, LightColorScheme.secondaryContainer,
                LightColorScheme.tertiary, LightColorScheme.tertiaryContainer,
                LightColorScheme.error, LightColorScheme.errorContainer
            ),
            surfaces = listOf(
                LightColorScheme.surfaceDim to 0xFFDCDCDC,
                LightColorScheme.surfaceBright to 0xFFFFFFFF,
                LightColorScheme.surfaceContainerLowest to 0xFFFFFFFF,
                LightColorScheme.surfaceContainerLow to 0xFFF6F6F6,
                LightColorScheme.surfaceContainer to 0xFFF1F1F1,
                LightColorScheme.surfaceContainerHigh to 0xFFEBEBEB,
                LightColorScheme.surfaceContainerHighest to 0xFFE5E5E5
            )
        )
        listOf(
            LightColorScheme.background to 0xFFFFFFFF,
            LightColorScheme.onBackground to 0xFF211A1B,
            LightColorScheme.surface to 0xFFFFFFFF,
            LightColorScheme.onSurface to 0xFF211A1B,
            LightColorScheme.surfaceVariant to 0xFFE5E5E5,
            LightColorScheme.onSurfaceVariant to 0xFF524345
        ).forEach { (actual, expected) -> assertColor(expected, actual) }
        assertColor(0xFF857375, LightColorScheme.outline)
        assertColor(0xFFD8C2C5, LightColorScheme.outlineVariant)
        assertColor(0xFF362F30, LightColorScheme.inverseSurface)
        assertColor(0xFFFBEEEE, LightColorScheme.inverseOnSurface)
        assertColor(0xFFFFB2B7, LightColorScheme.inversePrimary)
        assertColor(0xFFBB103C, LightColorScheme.surfaceTint)
    }

    @Test fun darkSchemeMatchesApprovedRolesAndContrast() {
        assertScheme(
            DarkColorScheme,
            core = listOf(
                "primary" to (0xFFFFB2B7 to 0xFF67001C),
                "primaryContainer" to (0xFF91002B to 0xFFFFDADB),
                "secondary" to (0xFF6FF0A6 to 0xFF00391C),
                "secondaryContainer" to (0xFF00522A to 0xFFB8F5D0),
                "tertiary" to (0xFFFFD47A to 0xFF422B00),
                "tertiaryContainer" to (0xFF815600 to 0xFFFFECB8),
                "error" to (0xFFFFB7C2 to 0xFF65101D),
                "errorContainer" to (0xFF982636 to 0xFFFFDADF)
            ),
            expected = listOf(
                DarkColorScheme.primary, DarkColorScheme.primaryContainer,
                DarkColorScheme.secondary, DarkColorScheme.secondaryContainer,
                DarkColorScheme.tertiary, DarkColorScheme.tertiaryContainer,
                DarkColorScheme.error, DarkColorScheme.errorContainer
            ),
            surfaces = listOf(
                DarkColorScheme.surfaceDim to 0xFF1A1113,
                DarkColorScheme.surfaceBright to 0xFF403638,
                DarkColorScheme.surfaceContainerLowest to 0xFF140C0E,
                DarkColorScheme.surfaceContainerLow to 0xFF21181A,
                DarkColorScheme.surfaceContainer to 0xFF251C1E,
                DarkColorScheme.surfaceContainerHigh to 0xFF302628,
                DarkColorScheme.surfaceContainerHighest to 0xFF3B3033
            )
        )
        listOf(
            DarkColorScheme.background to 0xFF1A1113,
            DarkColorScheme.onBackground to 0xFFF0DEE0,
            DarkColorScheme.surface to 0xFF1A1113,
            DarkColorScheme.onSurface to 0xFFF0DEE0,
            DarkColorScheme.surfaceVariant to 0xFF524345,
            DarkColorScheme.onSurfaceVariant to 0xFFD8C2C5
        ).forEach { (actual, expected) -> assertColor(expected, actual) }
        assertColor(0xFFA08C8F, DarkColorScheme.outline)
        assertColor(0xFF524345, DarkColorScheme.outlineVariant)
        assertColor(0xFFF0DEE0, DarkColorScheme.inverseSurface)
        assertColor(0xFF362F30, DarkColorScheme.inverseOnSurface)
        assertColor(0xFFBB103C, DarkColorScheme.inversePrimary)
        assertColor(0xFFFFB2B7, DarkColorScheme.surfaceTint)
    }

    @Test fun loginScrimStopsMeetApprovedFloor() {
        assertTrue(LightLoginScrimAlphas.all { it >= 0.58f })
        assertTrue(DarkLoginScrimAlphas.all { it >= 0.68f })
    }

    @Test fun fixedRolesScrimAndBrandInvariantsAreLocked() {
        for (scheme in listOf(LightColorScheme, DarkColorScheme)) {
            val fixed = listOf(
                scheme.primaryFixed to 0xFFFFDADB, scheme.primaryFixedDim to 0xFFFFB2B7,
                scheme.onPrimaryFixed to 0xFF40000E, scheme.onPrimaryFixedVariant to 0xFF91002B,
                scheme.secondaryFixed to 0xFFB8F5D0, scheme.secondaryFixedDim to 0xFF6FF0A6,
                scheme.onSecondaryFixed to 0xFF002110, scheme.onSecondaryFixedVariant to 0xFF00522A,
                scheme.tertiaryFixed to 0xFFFFECB8, scheme.tertiaryFixedDim to 0xFFFFD47A,
                scheme.onTertiaryFixed to 0xFF271900, scheme.onTertiaryFixedVariant to 0xFF4A3100
            )
            fixed.forEach { (actual, expected) -> assertColor(expected, actual) }
            assertColor(0xFF000000, scheme.scrim)
            listOf(
                scheme.onPrimaryFixed to scheme.primaryFixed,
                scheme.onPrimaryFixedVariant to scheme.primaryFixed,
                scheme.onSecondaryFixed to scheme.secondaryFixedDim,
                scheme.onSecondaryFixedVariant to scheme.secondaryFixed,
                scheme.onTertiaryFixed to scheme.tertiaryFixedDim,
                scheme.onTertiaryFixedVariant to scheme.tertiaryFixed
            ).forEach { (foreground, background) ->
                assertTrue(contrast(foreground, background) >= 4.5)
            }
        }
        assertTrue(contrast(LogoBright, Color.White) < 4.5)
        assertEquals(21.0, contrast(Color.White, Color.Black), 0.0)
    }

    private fun assertScheme(
        scheme: ColorScheme,
        core: List<Pair<String, Pair<Long, Long>>>,
        expected: List<Color>,
        surfaces: List<Pair<Color, Long>>
    ) {
        core.zip(expected).forEach { (entry, actualPairBackground) ->
            val (name, pair) = entry
            val (background, foreground) = pair
            assertColor(background, actualPairBackground)
            val actualForeground = when (name) {
                "primary" -> scheme.onPrimary
                "primaryContainer" -> scheme.onPrimaryContainer
                "secondary" -> scheme.onSecondary
                "secondaryContainer" -> scheme.onSecondaryContainer
                "tertiary" -> scheme.onTertiary
                "tertiaryContainer" -> scheme.onTertiaryContainer
                "error" -> scheme.onError
                else -> scheme.onErrorContainer
            }
            assertColor(foreground, actualForeground)
            assertTrue("$name contrast", contrast(actualForeground, actualPairBackground) >= 4.5)
        }
        surfaces.forEach { (actual, expectedColor) ->
            assertColor(expectedColor, actual)
            assertTrue(contrast(scheme.onSurface, actual) >= 4.5)
            assertTrue(contrast(scheme.onSurfaceVariant, actual) >= 4.5)
        }
    }

    private fun assertColor(expected: Long, actual: Color) =
        assertEquals(expected.toInt(), actual.toArgb())
}

internal fun contrast(a: Color, b: Color): Double {
    fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
    val x = luminance(a)
    val y = luminance(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}
