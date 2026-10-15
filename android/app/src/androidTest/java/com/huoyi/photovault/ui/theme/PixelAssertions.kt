package com.huoyi.photovault.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertTrue

internal fun ImageBitmap.countColor(expected: Color, tolerance: Int = 1): Int {
    val target = expected.toArgb()
    val targetR = target shr 16 and 0xFF
    val targetG = target shr 8 and 0xFF
    val targetB = target and 0xFF
    val pixels = toPixelMap()
    var count = 0
    for (y in 0 until height) for (x in 0 until width) {
        val actual = pixels[x, y].toArgb()
        if (kotlin.math.abs((actual shr 16 and 0xFF) - targetR) <= tolerance &&
            kotlin.math.abs((actual shr 8 and 0xFF) - targetG) <= tolerance &&
            kotlin.math.abs((actual and 0xFF) - targetB) <= tolerance
        ) count++
    }
    return count
}

internal fun ImageBitmap.assertContainsColor(expected: Color, label: String, minimumPixels: Int = 1) {
    assertTrue("$label missing ${expected.toArgb().toUInt().toString(16)}", countColor(expected) >= minimumPixels)
}

internal fun renderedContrast(foreground: Color, background: Color): Double {
    fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
    val a = luminance(foreground)
    val b = luminance(background)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
