package com.huoyi.photovault.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticColorContrastTest {
    @Test fun semanticContainerPairsMeetWcagInBothModes() {
        for (dark in listOf(false, true)) for (tone in SemanticTone.entries) {
            val colors = semanticColorSet(tone, dark)
            assertEquals(1f, colors.container.alpha)
            assertTrue("$tone dark=$dark", contrast(colors.foreground, colors.container) >= 4.5)
        }
    }

    @Test fun semanticForegroundPairsMeetWcagOnSurfaces() {
        val lightSurface = Color(0xFFFFFFFF)
        val darkSurface = Color(0xFF1A1113)
        for (tone in listOf(SemanticTone.Success, SemanticTone.Warning, SemanticTone.Danger)) {
            assertTrue(contrast(semanticColorSet(tone, false).foreground, lightSurface) >= 4.5)
            assertTrue(contrast(semanticColorSet(tone, true).foreground, darkSurface) >= 4.5)
        }
    }

    @Test fun photoBadgePairsAreOpaqueAndMeetWcag() {
        assertEquals(1f, PhotoBadgeColors.container.alpha)
        for (tone in listOf(SemanticTone.Success, SemanticTone.Warning, SemanticTone.Danger)) {
            assertTrue(contrast(PhotoBadgeColors.foreground(tone), PhotoBadgeColors.container) >= 4.5)
        }
    }
}
