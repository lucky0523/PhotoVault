package com.huoyi.photovault.ui.main

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.huoyi.photovault.R
import com.huoyi.photovault.data.local.entity.PhotoStatus
import com.huoyi.photovault.data.local.entity.PhotoStatusValue
import com.huoyi.photovault.ui.main.tabs.TrashCountdownBadge
import com.huoyi.photovault.ui.theme.PhotoBadgeColors
import com.huoyi.photovault.ui.theme.PhotoVaultTheme
import com.huoyi.photovault.ui.theme.SemanticTone
import com.huoyi.photovault.ui.theme.assertContainsColor
import com.huoyi.photovault.ui.theme.renderedContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PhotoBadgeContrastTest {
    @get:Rule val rule = createComposeRule()

    @Test fun eachProductionBadgeStaysOpaqueAndAccessibleOverWhiteBlackAndPhoto() {
        rule.setContent {
            PhotoVaultTheme(darkTheme = false) {
                Column {
                    BadgeFixture("white", Color.White, false)
                    BadgeFixture("black", Color.Black, false)
                    BadgeFixture("photo", Color.Transparent, true)
                }
            }
        }
        val tones = mapOf(
            PhotoStatusValue.ACTIVE to SemanticTone.Success,
            PhotoStatusValue.TRASHED to SemanticTone.Warning,
            PhotoStatusValue.PURGED to SemanticTone.Danger
        )
        for (fixture in listOf("white", "black", "photo")) {
            for ((status, tone) in tones) {
                val image = rule.onNodeWithTag("${fixture}_$status").captureToImage()
                image.assertContainsColor(PhotoBadgeColors.container, "$fixture $status opaque container", 20)
                image.assertContainsColor(PhotoBadgeColors.foreground(tone), "$fixture $status foreground")
                assertTrue(renderedContrast(PhotoBadgeColors.foreground(tone), PhotoBadgeColors.container) >= 4.5)
            }
            val countdown = rule.onNodeWithTag("${fixture}_countdown").captureToImage()
            countdown.assertContainsColor(PhotoBadgeColors.container, "$fixture Trash countdown opaque container", 20)
            countdown.assertContainsColor(
                PhotoBadgeColors.foreground(SemanticTone.Warning),
                "$fixture Trash countdown icon and 9sp text"
            )
        }
        assertEquals(1f, PhotoBadgeColors.container.alpha)
    }

    @androidx.compose.runtime.Composable
    private fun BadgeFixture(tag: String, background: Color, photo: Boolean) {
        Row(Modifier.testTag("${tag}_fixture")) {
            listOf(
                PhotoStatusValue.ACTIVE,
                PhotoStatusValue.TRASHED,
                PhotoStatusValue.PURGED
            ).forEach { status ->
                FixtureBackground(background, photo, Modifier.testTag("${tag}_$status")) {
                    StatusBadge(folderImage(status))
                }
            }
            FixtureBackground(background, photo, Modifier.testTag("${tag}_countdown")) {
                TrashCountdownBadge("2天", Modifier.align(androidx.compose.ui.Alignment.BottomStart))
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun FixtureBackground(
        background: Color,
        photo: Boolean,
        modifier: Modifier,
        content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
    ) {
        Box(modifier.size(90.dp, 72.dp).background(background)) {
            if (photo) {
                Image(
                    painter = painterResource(R.drawable.masterwork_01),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            content()
        }
    }

    private fun folderImage(status: String): FolderImage = FolderImage(
        uri = Uri.parse("content://test/$status"),
        name = "$status.jpg",
        fileSize = 1L,
        createdTime = 0L,
        mimeType = "image/jpeg",
        status = PhotoStatus(
            fileUri = "content://test/$status",
            fileHash = null,
            status = status,
            expiresAt = if (status == PhotoStatusValue.TRASHED) {
                System.currentTimeMillis() + 48 * 60 * 60 * 1000L
            } else {
                null
            }
        )
    )
}
