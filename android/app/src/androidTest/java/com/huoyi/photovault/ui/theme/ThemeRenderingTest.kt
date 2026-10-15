package com.huoyi.photovault.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.huoyi.photovault.ui.main.components.GlassScrollbar
import com.huoyi.photovault.ui.theme.liquid.LiquidBottomTab
import com.huoyi.photovault.ui.theme.liquid.LiquidBottomTabs
import com.huoyi.photovault.ui.theme.liquid.LiquidSlider
import com.huoyi.photovault.ui.theme.liquid.LiquidToggle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class ThemeRenderingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun explicitLightThemeDrivesProductionComponentMatrix() = verifyTheme(false)

    @Test fun explicitDarkThemeDrivesProductionComponentMatrix() = verifyTheme(true)

    @Test fun paintedFocusUsesConcentricBandsOnEveryRoundedEdgeAndCorner() {
        var activations = 0
        rule.setContent {
            PhotoVaultTheme(darkTheme = false) {
                val source = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .size(128.dp, 88.dp)
                        .background(Color(0xFF777777))
                        .padding(12.dp)
                        .testTag("focus_capture")
                ) {
                    Box(
                        Modifier
                            .size(104.dp, 64.dp)
                            .photoVaultFocusRing(shape, source)
                            .background(MaterialTheme.colorScheme.surface, shape)
                            .clickable(interactionSource = source, indication = null) { activations++ }
                            .focusable(interactionSource = source)
                            .testTag("rounded_focus_target")
                    )
                }
            }
        }
        val unfocused = rule.onNodeWithTag("focus_capture").captureToImage()
        rule.onNodeWithTag("rounded_focus_target")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val focused = rule.onNodeWithTag("focus_capture").captureToImage()
        assertTrue(focused.countColor(YingjiTone40) > unfocused.countColor(YingjiTone40))
        assertPaintedHaloGeometry(unfocused, focused, YingjiTone40)
        rule.onNodeWithTag("rounded_focus_target").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, activations) }
    }

    @Test fun liquidDialogRendersAllProductionStylesAndPaintedFocus() {
        rule.setContent {
            PhotoVaultTheme(darkTheme = true) {
                LiquidGlassDialog({}, "确认操作", "真实生产对话框") {
                    LiquidDialogButton("主操作", {}, LiquidDialogButtonStyle.Accent, Modifier.testTag("dialog_accent"))
                    LiquidDialogButton("取消", {}, LiquidDialogButtonStyle.Neutral, Modifier.testTag("dialog_neutral"))
                    LiquidDialogButton("删除", {}, LiquidDialogButtonStyle.Destructive, Modifier.testTag("dialog_danger"))
                }
            }
        }
        val scheme = DarkColorScheme
        rule.onNodeWithTag("dialog_accent").captureToImage().apply {
            assertContainsColor(scheme.primary, "dialog accent fill", 20)
            assertContainsColor(scheme.onPrimary, "dialog accent label")
        }
        rule.onNodeWithTag("dialog_danger").captureToImage().apply {
            assertContainsColor(scheme.error, "dialog danger fill", 20)
            assertContainsColor(scheme.onError, "dialog danger label")
        }
        rule.onNodeWithTag("dialog_neutral").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val focused = rule.onNodeWithTag("dialog_neutral").captureToImage()
        focused.assertContainsColor(Color.White, "dialog white halo")
        focused.assertContainsColor(Color.Black, "dialog black halo")
    }

    @Test fun modalBottomSheetConsumesExplicitSurfaceRoles() {
        rule.setContent {
            PhotoVaultTheme(darkTheme = false) {
                ModalBottomSheet(
                    onDismissRequest = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    modifier = Modifier.testTag("material_bottom_sheet")
                ) {
                    Text("底部工作表", Modifier.padding(24.dp))
                }
            }
        }
        rule.onNodeWithTag("material_bottom_sheet").captureToImage()
            .assertContainsColor(LightColorScheme.surfaceContainerLow, "bottom-sheet surface")
    }

    private fun verifyTheme(dark: Boolean) {
        var tab = 0
        var toggle = false
        var slider = 0.5f
        var surfaceClicks = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PhotoVaultTheme(darkTheme = dark) {
                val backdrop = rememberLayerBackdrop()
                val gridState = rememberLazyGridState()
                LaunchedEffect(Unit) { gridState.scrollToItem(12) }
                Box(Modifier.fillMaxSize().testTag("theme_matrix")) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .layerBackdrop(backdrop)
                    )
                    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                        Column(
                            Modifier
                                .width(340.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Button(onClick = {}, modifier = Modifier.testTag("material_button")) { Text("按钮") }
                            Card(Modifier.testTag("material_card")) { Text("卡片", Modifier.padding(16.dp)) }
                            Switch(checked = true, onCheckedChange = {}, modifier = Modifier.testTag("material_switch"))
                            LinearProgressIndicator(progress = { 0.6f }, modifier = Modifier.testTag("material_progress"))
                            OutlinedTextField(
                                value = "影集",
                                onValueChange = {},
                                modifier = Modifier.testTag("material_text_field")
                            )
                            SurfaceLiquidButton(
                                onClick = { surfaceClicks++ },
                                backdrop = null,
                                modifier = Modifier.size(120.dp, 48.dp).testTag("surface_fallback")
                            ) { Text("降级按钮", color = MaterialTheme.colorScheme.primary) }
                            LiquidBottomTabs(
                                selectedTabIndex = { tab },
                                onTabSelected = { tab = it },
                                backdrop = backdrop,
                                tabsCount = 2,
                                modifier = Modifier.width(300.dp).height(56.dp).testTag("liquid_tabs")
                            ) {
                                LiquidBottomTab { Text("本地") }
                                LiquidBottomTab { Text("云端") }
                            }
                            LiquidToggle(
                                selected = { toggle },
                                onSelect = { toggle = it },
                                backdrop = backdrop,
                                modifier = Modifier.padding(8.dp).testTag("liquid_toggle")
                            )
                            LiquidSlider(
                                value = { slider },
                                onValueChange = { slider = it },
                                valueRange = 0f..1f,
                                visibilityThreshold = 0.001f,
                                backdrop = backdrop,
                                modifier = Modifier.width(280.dp).padding(8.dp).testTag("liquid_slider")
                            )
                            Box(Modifier.size(180.dp, 140.dp).testTag("scrollbar_fixture")) {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    state = gridState,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items((0 until 40).toList()) { Text("照片 $it", Modifier.height(40.dp)) }
                                }
                                GlassScrollbar(
                                    state = gridState,
                                    columns = 2,
                                    modifier = Modifier.testTag("glass_scrollbar"),
                                    hideDelayMillis = 60_000
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val scheme = if (dark) DarkColorScheme else LightColorScheme
        rule.onNodeWithTag("theme_matrix").captureToImage().apply {
            assertContainsColor(scheme.background, "explicit background", 100)
            assertContainsColor(scheme.primary, "explicit primary", 20)
            assertContainsColor(scheme.surfaceContainerHighest, "explicit card surface")
            assertContainsColor(scheme.onPrimary, "button content")
        }
        rule.onNodeWithTag("surface_fallback").performScrollTo().captureToImage()
            .assertContainsColor(scheme.primary, "SurfaceLiquidButton explicit theme")
        rule.onNodeWithTag("glass_scrollbar").performScrollTo().captureToImage()

        rule.onNodeWithTag("surface_fallback").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.onNodeWithTag("surface_fallback").captureToImage().apply {
            assertContainsColor(Color.White, "surface white halo")
            assertContainsColor(Color.Black, "surface black halo")
        }
        rule.onNodeWithTag("surface_fallback").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, surfaceClicks) }

        rule.onNodeWithTag("liquid_tabs").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("liquid_tabs").performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals(1, tab) }
        rule.onNodeWithTag("liquid_toggle").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("liquid_toggle").performKeyInput { pressKey(Key.Spacebar) }
        rule.runOnIdle { assertTrue(toggle) }
        rule.onNodeWithTag("liquid_slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("liquid_slider").performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertTrue(slider > 0.5f) }
    }

    private fun assertPaintedHaloGeometry(unfocused: ImageBitmap, focused: ImageBitmap, brand: Color) {
        assertEquals(unfocused.width, focused.width)
        assertEquals(unfocused.height, focused.height)
        val density = focused.width / 128f
        val before = unfocused.toPixelMap()
        val after = focused.toPixelMap()

        fun coordinate(dp: Float, limit: Int): Int =
            kotlin.math.floor(dp * density).toInt().coerceIn(0, limit - 1)

        fun assertBandPixel(label: String, xDp: Float, yDp: Float, expected: Color) {
            val x = coordinate(xDp, focused.width)
            val y = coordinate(yDp, focused.height)
            assertTrue("$label at ($x,$y): ${after[x, y]}", after[x, y].matches(expected))
        }

        fun assertVisibleSegment(label: String, whiteDp: Pair<Float, Float>, blackDp: Pair<Float, Float>) {
            val whiteX = coordinate(whiteDp.first, focused.width)
            val whiteY = coordinate(whiteDp.second, focused.height)
            val blackX = coordinate(blackDp.first, focused.width)
            val blackY = coordinate(blackDp.second, focused.height)
            val whiteContrast = renderedContrast(after[whiteX, whiteY], before[whiteX, whiteY])
            val blackContrast = renderedContrast(after[blackX, blackY], before[blackX, blackY])
            assertTrue(
                "$label focused/unfocused visible band: white=$whiteContrast black=$blackContrast",
                maxOf(whiteContrast, blackContrast) >= 3.0
            )
        }

        val straightCoordinates = coordinate(32f, focused.width)..coordinate(96f, focused.width)
        val verticalCoordinates = coordinate(32f, focused.height)..coordinate(56f, focused.height)
        val topBands = listOf(
            brand to listOf(12.5f, 13.5f),
            Color.White to listOf(11.5f, 10.5f),
            Color.Black to listOf(9.5f, 8.5f)
        )
        topBands.forEach { (color, rows) ->
            rows.forEach { yDp ->
                val y = coordinate(yDp, focused.height)
                straightCoordinates.forEach { x ->
                    assertTrue("top continuous $color at ($x,$y)", after[x, y].matches(color))
                }
            }
        }
        topBands.forEach { (color, rows) ->
            rows.forEach { offsetY ->
                val bottomY = 88f - offsetY
                val y = coordinate(bottomY, focused.height)
                straightCoordinates.forEach { x ->
                    assertTrue("bottom continuous $color at ($x,$y)", after[x, y].matches(color))
                }
            }
        }
        val leftBands = listOf(
            brand to listOf(12.5f, 13.5f),
            Color.White to listOf(11.5f, 10.5f),
            Color.Black to listOf(9.5f, 8.5f)
        )
        leftBands.forEach { (color, columns) ->
            columns.forEach { xDp ->
                val x = coordinate(xDp, focused.width)
                verticalCoordinates.forEach { y ->
                    assertTrue("left continuous $color at ($x,$y)", after[x, y].matches(color))
                }
            }
        }
        leftBands.forEach { (color, columns) ->
            columns.forEach { offsetX ->
                val rightX = 128f - offsetX
                val x = coordinate(rightX, focused.width)
                verticalCoordinates.forEach { y ->
                    assertTrue("right continuous $color at ($x,$y)", after[x, y].matches(color))
                }
            }
        }

        assertVisibleSegment("top", 64f to 11f, 64f to 9f)
        assertVisibleSegment("right", 117f to 44f, 119f to 44f)
        assertVisibleSegment("bottom", 64f to 77f, 64f to 79f)
        assertVisibleSegment("left", 11f to 44f, 9f to 44f)

        data class Corner(val label: String, val centerX: Float, val centerY: Float, val nx: Float, val ny: Float)
        val diagonal = (kotlin.math.sqrt(0.5)).toFloat()
        val corners = listOf(
            Corner("top-left", 28f, 28f, -diagonal, -diagonal),
            Corner("top-right", 100f, 28f, diagonal, -diagonal),
            Corner("bottom-right", 100f, 60f, diagonal, diagonal),
            Corner("bottom-left", 28f, 60f, -diagonal, diagonal)
        )
        corners.forEach { corner ->
            fun point(radialDistance: Float): Pair<Float, Float> =
                (corner.centerX + corner.nx * radialDistance) to
                    (corner.centerY + corner.ny * radialDistance)

            listOf(14.5f, 15.5f).forEach { radius ->
                val point = point(radius)
                assertBandPixel("${corner.label} 2dp inner brand", point.first, point.second, brand)
            }
            listOf(16.5f, 17.5f).forEach { radius ->
                val point = point(radius)
                assertBandPixel("${corner.label} 2dp outer white", point.first, point.second, Color.White)
            }
            listOf(18.5f, 19.5f).forEach { radius ->
                val point = point(radius)
                assertBandPixel("${corner.label} 2dp outer black", point.first, point.second, Color.Black)
            }
            assertVisibleSegment(corner.label, point(17f), point(19f))
        }

        assertTrue("white/black halo boundary", renderedContrast(Color.White, Color.Black) == 21.0)
    }

    private fun Color.matches(expected: Color, tolerance: Int = 2): Boolean {
        fun channel(value: Float) = (value * 255).toInt()
        return kotlin.math.abs(channel(red) - channel(expected.red)) <= tolerance &&
            kotlin.math.abs(channel(green) - channel(expected.green)) <= tolerance &&
            kotlin.math.abs(channel(blue) - channel(expected.blue)) <= tolerance
    }
}
