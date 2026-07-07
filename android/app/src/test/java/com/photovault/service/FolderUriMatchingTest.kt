package com.photovault.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [canonicalFolderKey].
 *
 * Feature: rebackup-status-refresh — LocalTab 计数刷新修复。
 *
 * 验证 SAF tree uri 的「存储的 percent 编码形式」与「重新备份经导航双重解码后的
 * 形式」归一后得到相同 key，从而 folder 能被正确匹配（否则聚合计数不更新）。
 */
class FolderUriMatchingTest {

    // 数据库里存储的原始形式（SAF uri.toString() 带 percent 编码）。
    private val stored =
        "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"

    // 重新备份经过导航 URLEncoder + Navigation 解码 + NavGraph 再解码后的形式。
    private val decoded =
        "content://com.android.externalstorage.documents/tree/primary:DCIM/Camera"

    @Test
    fun `stored and url-decoded forms canonicalize to the same key`() {
        assertEquals(canonicalFolderKey(stored), canonicalFolderKey(decoded))
    }

    @Test
    fun `canonical key is idempotent`() {
        val once = canonicalFolderKey(stored)
        assertEquals(once, canonicalFolderKey(once))
    }

    @Test
    fun `distinct folders remain distinct`() {
        val other =
            "content://com.android.externalstorage.documents/tree/primary%3APictures"
        assertEquals(false, canonicalFolderKey(stored) == canonicalFolderKey(other))
    }
}
