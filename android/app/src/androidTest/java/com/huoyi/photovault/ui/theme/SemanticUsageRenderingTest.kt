package com.huoyi.photovault.ui.theme

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Region as AndroidRegion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.huoyi.photovault.data.local.entity.BackupFolder
import com.huoyi.photovault.data.local.entity.BackupStatus
import com.huoyi.photovault.data.network.ConnectionState
import com.huoyi.photovault.data.network.ConnectionType
import com.huoyi.photovault.ui.login.ConnectionStatusPill
import com.huoyi.photovault.ui.login.TestConnectionResult
import com.huoyi.photovault.ui.main.ConnectionPill
import com.huoyi.photovault.ui.main.PhotoFilterChip
import com.huoyi.photovault.ui.main.PhotoFilterKind
import com.huoyi.photovault.ui.main.tabs.BackgroundRunActionRow
import com.huoyi.photovault.ui.main.tabs.BatteryOptimizationStatusLabel
import com.huoyi.photovault.ui.main.tabs.CloudDestructiveActionContent
import com.huoyi.photovault.ui.main.tabs.ConnectionStatusItem
import com.huoyi.photovault.ui.main.tabs.FolderRow
import com.huoyi.photovault.ui.main.tabs.PauseReason
import com.huoyi.photovault.ui.main.tabs.PauseReasonBanner
import com.huoyi.photovault.ui.main.tabs.PausedTasksLoadErrorCard
import com.huoyi.photovault.ui.main.tabs.StatusIcon
import com.huoyi.photovault.ui.main.tabs.StatusLabel
import com.huoyi.photovault.ui.main.tabs.TrashEntryRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SemanticUsageRenderingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lightProductionConsumersUseSemanticFields() = verifyConsumers(false)

    @Test fun darkProductionConsumersUseSemanticFields() = verifyConsumers(true)

    private fun verifyConsumers(dark: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PhotoVaultTheme(darkTheme = dark) {
                Column(
                    Modifier
                        .width(360.dp)
                        .background(if (dark) DarkColorScheme.surface else LightColorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    PhotoFilterKind.entries.forEach { filter ->
                        PhotoFilterChip(filter, selected = true, onClick = {})
                        PhotoFilterChip(filter, selected = false, onClick = {})
                    }
                    Box(Modifier.testTag("login_pill_success_fixture")) {
                        ConnectionStatusPill(false, TestConnectionResult.Success, {}, true)
                    }
                    Box(Modifier.testTag("login_pill_danger_fixture")) {
                        ConnectionStatusPill(false, TestConnectionResult.Failure("失败"), {}, true)
                    }
                    PauseReasonBanner(PauseReason.NoWifi, Modifier.testTag("tasks_pause_fixture"))
                    PausedTasksLoadErrorCard({}, Modifier.testTag("tasks_error_fixture"))
                    Row(Modifier.testTag("tasks_history_fixture")) {
                        StatusIcon(BackupStatus.SUCCESS)
                        StatusLabel(BackupStatus.SUCCESS)
                    }
                    BatteryOptimizationStatusLabel(true, Modifier.testTag("settings_allowed_fixture"))
                    ConnectionStatusItem(
                        ConnectionState.Connected(ConnectionType.LAN),
                        Modifier.testTag("settings_connection_fixture")
                    )
                    BackgroundRunActionRow(false, {}, Modifier.testTag("settings_action_fixture"))
                    ConnectionPill(
                        ConnectionState.Connected(ConnectionType.LAN),
                        -1,
                        Modifier.testTag("main_connection_fixture")
                    )
                    FolderRow(
                        folder = folder(),
                        onClick = {},
                        onConfigurePolicy = {},
                        onRemove = {},
                        modifier = Modifier.testTag("local_row_fixture")
                    )
                    TrashEntryRow(3, {}, Modifier.testTag("cloud_trash_fixture"))
                    CloudDestructiveActionContent(2, false, Modifier.testTag("cloud_destructive_fixture"))
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        val scheme = if (dark) DarkColorScheme else LightColorScheme
        val localChipTones = mapOf(
            "已备份" to SemanticTone.Success,
            "未备份" to SemanticTone.Info,
            "回收站" to SemanticTone.Warning,
            "已删除" to SemanticTone.Danger
        )
        localChipTones.keys.forEachIndexed { index, label ->
            val colors = semanticColorSet(localChipTones.getValue(label), dark)
            assertStatusChip(label, colors, dark, firstInRow = index == 0)
        }

        val filterTones = mapOf(
            PhotoFilterKind.NOT_BACKED_UP to SemanticTone.Info,
            PhotoFilterKind.BACKED_UP to SemanticTone.Success,
            PhotoFilterKind.TRASHED to SemanticTone.Warning,
            PhotoFilterKind.PURGED to SemanticTone.Danger
        )
        // The folder-detail filter pills intentionally keep the original liquid-glass
        // look (user decision): vivid status-color text on a translucent light tint of
        // the same status color, and the selected pill filled with it. Only the color roles are
        // asserted here; the 4.5:1 text gate is waived for these pills.
        filterTones.forEach { (filter, tone) ->
            val colors = semanticColorSet(tone, dark)
            val selectedText = if (dark) colors.container else Color.White
            assertFilter(filter, selected = true, selectedText, colors.accent, minimum = null)
            assertFilter(filter, selected = false, colors.accent, null, minimum = null)
        }

        assertFilter(PhotoFilterKind.ALL, selected = true, scheme.onPrimary, scheme.primary)
        assertFilter(PhotoFilterKind.ALL, selected = false, scheme.primary, null, minimum = null)

        assertOpaquePair(
            "login_pill_success_fixture",
            "login_connection_status_success_label",
            semanticColorSet(SemanticTone.Success, dark).container,
            semanticColorSet(SemanticTone.Success, dark).foreground,
            "login success pill",
            Point(0.5f, 0.08f)
        )
        assertOpaquePair(
            "login_pill_danger_fixture",
            "login_connection_status_danger_label",
            semanticColorSet(SemanticTone.Danger, dark).container,
            semanticColorSet(SemanticTone.Danger, dark).foreground,
            "login danger pill",
            Point(0.5f, 0.08f)
        )
        val warning = semanticColorSet(SemanticTone.Warning, dark)
        assertOpaquePair(
            "tasks_pause_reason",
            "tasks_pause_reason_title",
            warning.container,
            warning.foreground,
            "Tasks pause title",
            Point(0.5f, 0.08f)
        )
        assertOpaquePair(
            "tasks_pause_reason",
            "tasks_pause_reason_hint",
            warning.container,
            warning.foreground,
            "Tasks pause hint",
            Point(0.5f, 0.08f)
        )
        assertColor("tasks_pause_reason_icon", warning.accent, "Tasks pause icon")
        assertOpaquePair(
            "tasks_load_error",
            "tasks_load_error_message",
            warning.container,
            warning.foreground,
            "Tasks load-error message",
            Point(0.5f, 0.08f)
        )
        assertColor("tasks_load_error_icon", warning.accent, "Tasks load-error icon")
        assertSurfaceForeground(
            "tasks_history_status_label",
            semanticColorSet(SemanticTone.Success, dark).foreground,
            "Tasks history label"
        )
        assertColor(
            "tasks_history_status_icon",
            semanticColorSet(SemanticTone.Success, dark).accent,
            "Tasks history icon"
        )
        assertSurfaceForeground(
            "settings_battery_status_allowed",
            semanticColorSet(SemanticTone.Success, dark).foreground,
            "Settings allowed text"
        )
        assertColor(
            "settings_connection_status",
            semanticColorSet(SemanticTone.Success, dark).accent,
            "Settings connection dot",
            Region(0.68f, 0f, 0.82f, 1f)
        )
        assertSurfaceForeground(
            "settings_connection_status",
            scheme.onSurface,
            "Settings connection text",
            Region(0.78f, 0f, 1f, 1f)
        )
        assertColor(
            "main_connection_pill",
            semanticColorSet(SemanticTone.Success, dark).accent,
            "Main connection dot",
            Region(0f, 0f, 0.36f, 1f)
        )
        assertSurfaceForeground(
            "main_connection_pill",
            scheme.onSurface,
            "Main connection text",
            Region(0.30f, 0f, 1f, 1f)
        )
        assertOpaquePair(
            "cloud_trash_icon_container",
            "cloud_trash_icon",
            semanticColorSet(SemanticTone.Warning, dark).container,
            semanticColorSet(SemanticTone.Warning, dark).accent,
            "Cloud trash icon",
            Point(0.5f, 0.08f),
            3.0
        )
        assertSurfaceForeground(
            "cloud_destructive_action_content",
            semanticColorSet(SemanticTone.Danger, dark).foreground,
            "Cloud destructive count",
            Region(0f, 0f, 0.48f, 1f)
        )
        assertSurfaceForeground(
            "cloud_destructive_action_content",
            semanticColorSet(SemanticTone.Danger, dark).foreground,
            "Cloud destructive icon",
            Region(0.48f, 0f, 1f, 1f)
        )
    }

    /**
     * StatusChip shares the filter-pill soft look (user decision): a faint
     * translucent [statusSoftFill] of the accent as background, and the vivid
     * accent for dot, label and count. The fill is translucent, so the expected
     * container pixel is the fill composited over the card pixel sampled in the
     * gap next to the chip. Like the filter pills, the 4.5:1 text gate is waived;
     * the roles (container vs. glyph vs. dot) are still asserted at fixed points.
     */
    private fun assertStatusChip(
        label: String,
        colors: SemanticColorSet,
        dark: Boolean,
        firstInRow: Boolean
    ) {
        val containerTag = "status_chip_${label}_container"
        val dotTag = "status_chip_${label}_dot"
        val foregroundTags = listOf(
            "status_chip_${label}_label" to "label",
            "status_chip_${label}_count" to "count"
        )
        val containerNode = uniqueNode(containerTag).performScrollTo()
        val containerBounds = containerNode.fetchSemanticsNode().boundsInRoot
        val contentLeft = (listOf(dotTag) + foregroundTags.map { it.first })
            .minOf { uniqueNode(it).fetchSemanticsNode().boundsInRoot.left }
        assertTrue("$label StatusChip has no left container inset", contentLeft > containerBounds.left)

        // Card pixel in the Row's inter-chip gap, at the chip's vertical center.
        val rowNode = uniqueNode("local_row_fixture")
        val rowBounds = rowNode.fetchSemanticsNode().boundsInRoot
        val rowImage = rowNode.captureToImage()
        val gapX = if (firstInRow) containerBounds.right + 3f else containerBounds.left - 3f
        val underlying = rowImage.toPixelMap()[
            (gapX - rowBounds.left).toInt().coerceIn(0, rowImage.width - 1),
            (containerBounds.center.y - rowBounds.top).toInt().coerceIn(0, rowImage.height - 1)
        ]
        val expectedContainer = statusSoftFill(colors.accent, dark).compositeOver(underlying)

        val containerImage = containerNode.captureToImage()
        val containerPoint = PixelPoint(
            x = ((contentLeft - containerBounds.left) / 2f).toInt(),
            y = containerImage.height / 2
        )
        val container = containerImage.pixel(
            containerPoint,
            expectedContainer,
            "$label StatusChip fixed inset soft-fill container",
            // Translucent blending rounds per channel; allow a few levels of drift.
            tolerance = 3
        )

        foregroundTags.forEach { (tag, role) ->
            val point = textGlyphInteriorPoint(tag)
            val foreground = capture(tag).pixel(
                point,
                colors.accent,
                "$label StatusChip $role glyph interior"
            )
            assertTrue(
                "$label StatusChip $role glyph must differ from its container",
                !foreground.matches(container)
            )
        }
        assertColor(dotTag, colors.accent, "$label Local FolderRow dot")
    }

    private fun textGlyphInteriorPoint(tag: String): PixelPoint {
        val layouts = mutableListOf<TextLayoutResult>()
        uniqueNode(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("$tag did not expose production text layout", action(layouts))
        }
        assertTrue("$tag expected one production text layout, got ${layouts.size}", layouts.size == 1)
        val layout = layouts.single()
        val textLength = layout.layoutInput.text.length
        assertTrue("$tag has no text geometry", textLength > 0)

        val scale = 8
        val glyphPath = Path(layout.getPathForRange(0, textLength).asAndroidPath())
        glyphPath.transform(Matrix().apply { setScale(scale.toFloat(), scale.toFloat()) })
        val glyphRegion = AndroidRegion().apply {
            setPath(
                glyphPath,
                AndroidRegion(0, 0, layout.size.width * scale, layout.size.height * scale)
            )
        }
        val point = (0 until layout.size.height).flatMap { y ->
            (0 until layout.size.width).map { x ->
                PixelPoint(x, y) to glyphClearance(glyphRegion, x * scale + scale / 2, y * scale + scale / 2)
            }
        }.maxByOrNull { it.second }
            ?: error("$tag has no glyph pixels")
        assertTrue("$tag has no stable glyph interior: $point", point.second >= 2)
        return point.first
    }

    private fun glyphClearance(region: AndroidRegion, centerX: Int, centerY: Int): Int {
        if (!region.contains(centerX, centerY)) return -1
        for (radius in 1..16) {
            for (offset in -radius..radius) {
                if (!region.contains(centerX + offset, centerY - radius) ||
                    !region.contains(centerX + offset, centerY + radius) ||
                    !region.contains(centerX - radius, centerY + offset) ||
                    !region.contains(centerX + radius, centerY + offset)
                ) return radius - 1
            }
        }
        return 16
    }

    private fun assertFilter(
        filter: PhotoFilterKind,
        selected: Boolean,
        expectedForeground: Color,
        expectedContainer: Color?,
        minimum: Double? = 4.5
    ) {
        val tag = "photo_filter_${filter.name.lowercase()}_${if (selected) "selected" else "unselected"}"
        val image = capture(tag)
        val foreground = image.findColor(
            expectedForeground,
            "$tag text",
            Region(0.12f, 0.08f, 0.88f, 0.92f)
        )
        val background = if (expectedContainer != null) {
            image.findColor(expectedContainer, "$tag container", Region(0f, 0f, 0.12f, 1f))
        } else {
            image.backgroundCorner("$tag background")
        }
        if (minimum != null) {
            val ratio = renderedContrast(foreground, background)
            assertTrue("$tag actual foreground/background WCAG=$ratio", ratio >= minimum)
        }
    }

    private fun assertOpaquePair(
        containerTag: String,
        foregroundTag: String,
        expectedContainer: Color,
        expectedForeground: Color,
        label: String,
        containerPoint: Point? = null,
        minimum: Double = 4.5
    ) {
        val containerImage = capture(containerTag)
        val background = if (containerPoint == null) {
            containerImage.findColor(expectedContainer, "$label container")
        } else {
            containerImage.pixel(containerPoint, expectedContainer, "$label fixed container")
        }
        val foreground = capture(foregroundTag).findColor(expectedForeground, "$label foreground")
        val ratio = renderedContrast(foreground, background)
        assertTrue("$label actual foreground/background WCAG=$ratio", ratio >= minimum)
    }

    private fun assertSurfaceForeground(
        tag: String,
        expectedForeground: Color,
        label: String,
        region: Region = Region.Full
    ) {
        val image = capture(tag)
        val foreground = image.findColor(expectedForeground, label, region)
        val background = image.backgroundCorner("$label background")
        val ratio = renderedContrast(foreground, background)
        assertTrue("$label actual foreground/background WCAG=$ratio", ratio >= 4.5)
    }

    private fun assertColor(
        tag: String,
        expected: Color,
        label: String,
        region: Region = Region.Full
    ) {
        capture(tag).findColor(expected, label, region)
    }

    private fun uniqueNode(tag: String): SemanticsNodeInteraction {
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
        return rule.onNodeWithTag(tag, useUnmergedTree = true)
    }

    private fun capture(tag: String) = uniqueNode(tag).performScrollTo().captureToImage()

    private fun ImageBitmap.findColor(expected: Color, label: String, region: Region = Region.Full): Color {
        val pixels = toPixelMap()
        val xStart = (width * region.left).toInt().coerceIn(0, width - 1)
        val xEnd = (width * region.right).toInt().coerceIn(xStart + 1, width)
        val yStart = (height * region.top).toInt().coerceIn(0, height - 1)
        val yEnd = (height * region.bottom).toInt().coerceIn(yStart + 1, height)
        for (y in yStart until yEnd) for (x in xStart until xEnd) {
            val actual = pixels[x, y]
            if (actual.matches(expected)) return actual
        }
        error("$label missing ${expected.toArgb().toUInt().toString(16)} in $region")
    }

    private fun ImageBitmap.pixel(
        point: PixelPoint,
        expected: Color,
        label: String,
        tolerance: Int = 1
    ): Color {
        val x = point.x.coerceIn(0, width - 1)
        val y = point.y.coerceIn(0, height - 1)
        val actual = toPixelMap()[x, y]
        assertTrue("$label at ($x,$y): $actual", actual.matches(expected, tolerance))
        return actual
    }

    private fun ImageBitmap.pixel(point: Point, expected: Color, label: String): Color {
        val x = (width * point.x).toInt().coerceIn(0, width - 1)
        val y = (height * point.y).toInt().coerceIn(0, height - 1)
        val actual = toPixelMap()[x, y]
        assertTrue("$label at ($x,$y): $actual", actual.matches(expected))
        return actual
    }

    private fun ImageBitmap.backgroundCorner(label: String): Color {
        val pixels = toPixelMap()
        val candidates = listOf(pixels[0, 0], pixels[width - 1, 0], pixels[0, height - 1], pixels[width - 1, height - 1])
        val background = candidates.groupingBy { it.toArgb() }.eachCount().maxByOrNull { it.value }?.key
            ?: error("$label has no pixels")
        return Color(background)
    }

    private fun Color.matches(expected: Color, tolerance: Int = 1): Boolean {
        val actual = toArgb()
        val target = expected.toArgb()
        return kotlin.math.abs((actual shr 16 and 0xFF) - (target shr 16 and 0xFF)) <= tolerance &&
            kotlin.math.abs((actual shr 8 and 0xFF) - (target shr 8 and 0xFF)) <= tolerance &&
            kotlin.math.abs((actual and 0xFF) - (target and 0xFF)) <= tolerance
    }

    private data class PixelPoint(val x: Int, val y: Int)

    private data class Point(val x: Float, val y: Float)

    private data class Region(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        companion object {
            val Full = Region(0f, 0f, 1f, 1f)
        }
    }

    private fun folder() = BackupFolder(
        id = 1,
        folderUri = "content://fixture/folder",
        folderName = "生产文件夹",
        totalImages = 8,
        backedUpImages = 2,
        trashedImages = 2,
        purgedImages = 2,
        lastScanTime = 0
    )
}
