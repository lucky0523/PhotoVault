package com.huoyi.photovault.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.huoyi.photovault.ui.theme.DarkColorScheme
import com.huoyi.photovault.ui.theme.LightColorScheme
import com.huoyi.photovault.ui.theme.PhotoVaultTheme
import com.huoyi.photovault.ui.theme.SemanticTone
import com.huoyi.photovault.ui.theme.YingjiTone40
import com.huoyi.photovault.ui.theme.YingjiTone80
import com.huoyi.photovault.ui.theme.renderedContrast
import com.huoyi.photovault.ui.theme.semanticColorSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.floor

@OptIn(ExperimentalTestApi::class)
class LoginPhotoContrastTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lightLoginOverWhiteFixture() = verifyCompleteLogin(false, Color.White, "light_white")
    @Test fun lightLoginOverBlackFixture() = verifyCompleteLogin(false, Color.Black, "light_black")
    @Test fun lightLoginOverProductionWall() = verifyCompleteLogin(false, null, "light_masterworks")
    @Test fun darkLoginOverWhiteFixture() = verifyCompleteLogin(true, Color.White, "dark_white")
    @Test fun darkLoginOverBlackFixture() = verifyCompleteLogin(true, Color.Black, "dark_black")
    @Test fun darkLoginOverProductionWall() = verifyCompleteLogin(true, null, "dark_masterworks")

    @Test fun productionBackdropMeetsWhiteContentContrastAtEveryPixelEndpoint() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PhotoVaultTheme(darkTheme = false) {
                Column {
                    Fixture("white_fixture") { LoginPhotoBackdrop(Modifier.size(120.dp, 200.dp), Color.White) }
                    Fixture("black_fixture") { LoginPhotoBackdrop(Modifier.size(120.dp, 200.dp), Color.Black) }
                    Fixture("masterworks_fixture") { LoginPhotoBackdrop(Modifier.size(120.dp, 200.dp)) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        listOf("white_fixture", "black_fixture", "masterworks_fixture").forEach { tag ->
            val image = uniqueNode(tag).captureToImage()
            val pixels = image.toPixelMap()
            var minimum = Double.MAX_VALUE
            for (y in 0 until image.height) for (x in 0 until image.width) {
                minimum = minOf(minimum, renderedContrast(Color.White, pixels[x, y]))
            }
            assertTrue("$tag minimum white contrast=$minimum", minimum >= 4.5)
        }
    }

    @Test fun idleConnectionPillHasUniqueFocusAndActivation() =
        verifyConnectionPill("idle", false, null, null)

    @Test fun testingConnectionPillHasUniqueFocusAndActivation() =
        verifyConnectionPill("testing", true, null, null)

    @Test fun successConnectionPillHasUniqueFocusAndActivation() =
        verifyConnectionPill("success", false, TestConnectionResult.Success, SemanticTone.Success)

    @Test fun dangerConnectionPillHasUniqueFocusAndActivation() =
        verifyConnectionPill("danger", false, TestConnectionResult.Failure("失败"), SemanticTone.Danger)

    @Test fun productionPrimaryActionUsesPaintedFocusAndEnterActivation() {
        var activations = 0
        rule.setContent {
            PhotoVaultTheme(darkTheme = false) {
                Box(Modifier.background(Color(0xFF777777)).padding(8.dp).testTag("action_capture")) {
                    PrimaryActionButton("登录", false, true, false, { activations++ })
                }
            }
        }
        assertPaintedFocus("login_primary_action", false)
        uniqueNode("login_primary_action").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, activations) }
    }

    private fun verifyConnectionPill(
        label: String,
        testing: Boolean,
        result: TestConnectionResult?,
        tone: SemanticTone?
    ) {
        var activations = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PhotoVaultTheme(darkTheme = true) {
                Box(
                    Modifier
                        .background(Color(0xFF777777))
                        .padding(12.dp)
                        .testTag("pill_${label}_fixture")
                ) {
                    ConnectionStatusPill(testing, result, { activations++ }, true)
                }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val target = uniqueNode("login_connection_status")
        val expectedForeground: Color
        val expectedBackground: Color
        if (tone == null) {
            expectedForeground = DarkColorScheme.onPrimary
            expectedBackground = DarkColorScheme.primary
        } else {
            val colors = semanticColorSet(tone, true)
            expectedForeground = colors.foreground
            expectedBackground = colors.container
        }
        val background = target.captureToImage().pixel(Point(0.5f, 0.08f), "$label pill container")
        assertTrue("$label pill container responsibility", background.matches(expectedBackground))
        val foreground = uniqueNode("login_connection_status_${label}_label")
            .captureToImage()
            .findColor(expectedForeground, "$label pill label")
        assertContrast(foreground, background, 4.5, "$label pill")

        rule.runOnIdle { assertEquals(0, activations) }
        assertPaintedFocus("login_connection_status", true)
        target.performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, activations) }
    }

    private fun verifyCompleteLogin(dark: Boolean, fixture: Color?, label: String) {
        val state = LoginUiState(
            serverAddress = "██",
            lanAddress = "██",
            username = "██",
            password = "xx",
            rememberPassword = true,
            publicTestResult = TestConnectionResult.Success,
            lanTestResult = TestConnectionResult.Failure("连接失败")
        )
        val scheme = if (dark) DarkColorScheme else LightColorScheme
        val success = semanticColorSet(SemanticTone.Success, dark)
        val danger = semanticColorSet(SemanticTone.Danger, dark)
        val actionForeground = if (dark) Color(0xFF67001C) else Color.White
        val samples = listOf(
            FixedSample("login_title", Color.White, listOf(Point(0.12f, 0.30f), Point(0.20f, 0.70f)), 4.5),
            FixedSample("login_public_address", scheme.onSurface, FIELD_VALUE_POINTS, 4.5),
            FixedSample("login_lan_address", scheme.onSurface, FIELD_VALUE_POINTS, 4.5),
            FixedSample("login_username", scheme.onSurface, FIELD_VALUE_POINTS, 4.5),
            FixedSample("login_password", scheme.onSurface, PASSWORD_VALUE_POINTS, 4.5),
            FixedSample("login_public_address_leading_icon", scheme.onSurfaceVariant, DNS_ICON_POINTS, 3.0),
            FixedSample("login_lan_address_leading_icon", scheme.onSurfaceVariant, LAN_ICON_POINTS, 3.0),
            FixedSample("login_username_leading_icon", scheme.onSurfaceVariant, PERSON_ICON_POINTS, 3.0),
            FixedSample("login_password_leading_icon", scheme.onSurfaceVariant, LOCK_ICON_POINTS, 3.0),
            FixedSample("login_public_address_trailing_icon", scheme.primary, QR_ICON_POINTS, 3.0),
            FixedSample("login_password_trailing_icon", scheme.onSurfaceVariant, VISIBILITY_ICON_POINTS, 3.0),
            FixedSample(
                "login_supporting_success_foreground",
                success.foreground,
                SUPPORTING_TEXT_POINTS,
                4.5,
                success.container
            ),
            FixedSample(
                "login_supporting_danger_foreground",
                danger.foreground,
                SUPPORTING_TEXT_POINTS,
                4.5,
                danger.container
            ),
            FixedSample(
                "login_connection_status_danger_label",
                danger.foreground,
                PILL_LABEL_POINTS,
                4.5,
                danger.container
            ),
            FixedSample(
                "login_connection_status_danger_icon",
                danger.foreground,
                ERROR_ICON_POINTS,
                3.0,
                danger.container
            ),
            FixedSample("login_remember_password_label", Color.White, REMEMBER_LABEL_POINTS, 4.5),
            FixedSample("login_primary_action_label", actionForeground, ACTION_LABEL_POINTS, 4.5),
            FixedSample("login_primary_action_icon", actionForeground, ARROW_ICON_POINTS, 3.0)
        )

        val foregroundVisible = androidx.compose.runtime.mutableStateOf(true)
        renderLogin(dark, fixture, state) { foregroundVisible.value }
        val foreground = samples.associate { it.tag to uniqueNode(it.tag).captureToImage() }
        uniqueNode("login_public_address").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val focusedForeground = uniqueNode("login_public_address").captureToImage()

        rule.runOnIdle { foregroundVisible.value = false }
        rule.waitForIdle()
        val backgroundOnly = samples.associate { it.tag to uniqueNode(it.tag).captureToImage() }
        val focusedBackgroundOnly = uniqueNode("login_public_address").captureToImage()

        samples.forEach { sample ->
            assertFixedSamples(
                foreground.getValue(sample.tag),
                backgroundOnly.getValue(sample.tag),
                sample,
                label
            )
        }
        assertFixedSamples(
            focusedForeground,
            focusedBackgroundOnly,
            FixedSample(
                "login_public_address focused border",
                scheme.primary,
                FOCUSED_BORDER_POINTS,
                3.0
            ),
            label
        )

        rule.runOnIdle { foregroundVisible.value = true }
        rule.waitForIdle()
        listOf("login_connection_status", "login_remember_password", "login_primary_action").forEach { tag ->
            assertPaintedFocus(tag, dark)
        }
    }

    private fun renderLogin(
        dark: Boolean,
        fixture: Color?,
        state: LoginUiState,
        foregroundVisible: () -> Boolean
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PhotoVaultTheme(darkTheme = dark) {
                LoginScreenForRenderingTest(
                    renderingState = state,
                    backgroundFixtureColor = fixture,
                    renderForeground = foregroundVisible()
                )
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun assertFixedSamples(
        foregroundImage: ImageBitmap,
        backgroundOnlyImage: ImageBitmap,
        sample: FixedSample,
        fixtureLabel: String
    ) {
        assertEquals("${sample.tag} background-only width", foregroundImage.width, backgroundOnlyImage.width)
        assertEquals("${sample.tag} background-only height", foregroundImage.height, backgroundOnlyImage.height)
        assertTrue("${sample.tag} requires two committed solid points", sample.points.size >= 2)
        sample.points.forEachIndexed { index, point ->
            val foreground = foregroundImage.pixel(point, "$fixtureLabel ${sample.tag} foreground[$index]")
            assertTrue(
                "$fixtureLabel ${sample.tag} fixed foreground[$index] at $point: $foreground",
                foreground.matches(sample.expectedForeground)
            )
            val background = backgroundOnlyImage.pixel(point, "$fixtureLabel ${sample.tag} background[$index]")
            sample.expectedBackground?.let { expected ->
                assertTrue(
                    "$fixtureLabel ${sample.tag} fixed background[$index] at $point: $background",
                    background.matches(expected)
                )
            }
            assertContrast(
                foreground,
                background,
                sample.minimum,
                "$fixtureLabel ${sample.tag}[$index] same-coordinate"
            )
        }
    }

    private fun assertPaintedFocus(tag: String, dark: Boolean) {
        val target = uniqueNode(tag)
        val bounds: Rect = target.fetchSemanticsNode().boundsInRoot
        val before = rule.onRoot().captureToImage()
        target.performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val after = rule.onRoot().captureToImage()
        val density = displayDensity()
        val x = floor(bounds.center.x).toInt().coerceIn(0, after.width - 1)
        val brandY = floor(bounds.top + 1f * density).toInt().coerceIn(0, after.height - 1)
        val whiteY = floor(bounds.top - 1f * density).toInt().coerceIn(0, after.height - 1)
        val blackY = floor(bounds.top - 3f * density).toInt().coerceIn(0, after.height - 1)
        val focused = after.toPixelMap()
        val unfocused = before.toPixelMap()
        val brand = focused[x, brandY]
        val white = focused[x, whiteY]
        val black = focused[x, blackY]
        val expectedBrand = if (dark) YingjiTone80 else YingjiTone40
        assertTrue("$tag inner brand band", brand.matches(expectedBrand))
        assertTrue("$tag inner white halo", white.matches(Color.White))
        assertTrue("$tag outer black halo", black.matches(Color.Black))
        val visibleContrast = maxOf(
            renderedContrast(white, unfocused[x, whiteY]),
            renderedContrast(black, unfocused[x, blackY])
        )
        assertTrue("$tag focused/unfocused visible halo=$visibleContrast", visibleContrast >= 3.0)
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Double, label: String) {
        assertTrue("$label background-only point hit foreground", !background.matches(foreground))
        val ratio = renderedContrast(foreground, background)
        assertTrue("$label actual foreground/background WCAG=$ratio", ratio >= minimum)
    }

    private fun uniqueNode(tag: String): SemanticsNodeInteraction {
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
        return rule.onNodeWithTag(tag, useUnmergedTree = true)
    }

    private fun ImageBitmap.findColor(expected: Color, label: String): Color {
        val pixels = toPixelMap()
        for (y in 0 until height) for (x in 0 until width) {
            val actual = pixels[x, y]
            if (actual.matches(expected)) return actual
        }
        error("$label missing ${expected.toArgb().toUInt().toString(16)}")
    }

    private fun ImageBitmap.pixel(point: Point, label: String): Color {
        val x = floor(width * point.x).toInt().coerceIn(0, width - 1)
        val y = floor(height * point.y).toInt().coerceIn(0, height - 1)
        val color = toPixelMap()[x, y]
        assertTrue("$label is transparent", color.alpha > 0.99f)
        return color
    }

    private fun Color.matches(expected: Color, tolerance: Int = 2): Boolean {
        val actual = toArgb()
        val target = expected.toArgb()
        return kotlin.math.abs((actual shr 16 and 0xFF) - (target shr 16 and 0xFF)) <= tolerance &&
            kotlin.math.abs((actual shr 8 and 0xFF) - (target shr 8 and 0xFF)) <= tolerance &&
            kotlin.math.abs((actual and 0xFF) - (target and 0xFF)) <= tolerance
    }

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private data class FixedSample(
        val tag: String,
        val expectedForeground: Color,
        val points: List<Point>,
        val minimum: Double,
        val expectedBackground: Color? = null
    )

    private data class Point(val x: Float, val y: Float)

    @androidx.compose.runtime.Composable
    private fun Fixture(tag: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        Box(Modifier.testTag(tag)) { content() }
    }

    private companion object {
        // Coordinates are committed fractions of the production node bounds. Icon points
        // follow the fixed Material 24dp vector paths inside their tagged 20/24dp nodes;
        // field points follow the M3 leading-icon/value slots and the 56dp outline. They
        // are never discovered by scanning a rendered bitmap.
        val FIELD_VALUE_POINTS = listOf(Point(0.18f, 0.24f), Point(0.20f, 0.34f))
        val PASSWORD_VALUE_POINTS = listOf(Point(0.18f, 0.27f), Point(0.21f, 0.27f))
        val FOCUSED_BORDER_POINTS = listOf(Point(0.45f, 0.01f), Point(0.55f, 0.01f))

        val DNS_ICON_POINTS = listOf(Point(0.22f, 0.28f), Point(0.22f, 0.72f))
        val LAN_ICON_POINTS = listOf(Point(0.50f, 0.22f), Point(0.28f, 0.72f))
        val PERSON_ICON_POINTS = listOf(Point(0.50f, 0.28f), Point(0.50f, 0.72f))
        val LOCK_ICON_POINTS = listOf(Point(0.50f, 0.55f), Point(0.50f, 0.78f))
        val QR_ICON_POINTS = listOf(Point(0.22f, 0.22f), Point(0.78f, 0.22f))
        val VISIBILITY_ICON_POINTS = listOf(Point(0.50f, 0.50f), Point(0.22f, 0.50f))
        val ERROR_ICON_POINTS = listOf(Point(0.50f, 0.22f), Point(0.50f, 0.72f))
        val ARROW_ICON_POINTS = listOf(Point(0.52f, 0.50f), Point(0.72f, 0.50f))

        val SUPPORTING_TEXT_POINTS = listOf(Point(0.10f, 0.30f), Point(0.24f, 0.70f))
        val PILL_LABEL_POINTS = listOf(Point(0.12f, 0.30f), Point(0.38f, 0.70f))
        val REMEMBER_LABEL_POINTS = listOf(Point(0.04f, 0.30f), Point(0.12f, 0.70f))
        val ACTION_LABEL_POINTS = listOf(Point(0.12f, 0.30f), Point(0.38f, 0.70f))
    }
}
