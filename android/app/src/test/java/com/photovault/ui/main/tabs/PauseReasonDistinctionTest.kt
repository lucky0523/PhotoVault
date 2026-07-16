package com.photovault.ui.main.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [PauseReason] wording/semantics shown on the 备份任务 Tab
 * (Requirement 24.5: 区分"用户暂停"与"条件暂停").
 *
 * These assertions pin the distinction the banner relies on:
 * - The user pause ([PauseReason.UserPaused]) is flagged [PauseReason.isUserPause]
 *   and prompts the user to tap "开始" to continue — it must NOT advertise an
 *   automatic condition-recovery hint.
 * - Every condition pause is NOT a user pause and carries a specific
 *   auto-recovery hint.
 */
class PauseReasonDistinctionTest {

    @Test
    fun `user pause is flagged as user pause`() {
        assertTrue(PauseReason.UserPaused.isUserPause)
    }

    @Test
    fun `user pause prompts to tap start to continue`() {
        assertEquals("已手动暂停", PauseReason.UserPaused.message)
        assertEquals("点击开始继续", PauseReason.UserPaused.resumeHint)
    }

    @Test
    fun `user pause does not advertise automatic recovery`() {
        // A user pause must be resumed manually; it must not claim to auto-resume.
        assertFalse(PauseReason.UserPaused.resumeHint.contains("自动"))
    }

    @Test
    fun `condition pauses are not user pauses`() {
        assertFalse(PauseReason.LowBattery.isUserPause)
        assertFalse(PauseReason.NoWifi.isUserPause)
        assertFalse(PauseReason.LowBatteryAndNoWifi.isUserPause)
    }

    @Test
    fun `condition pauses describe automatic recovery`() {
        assertTrue(PauseReason.LowBattery.resumeHint.contains("自动"))
        assertTrue(PauseReason.NoWifi.resumeHint.contains("自动"))
        assertTrue(PauseReason.LowBatteryAndNoWifi.resumeHint.contains("自动"))
    }

    @Test
    fun `no wifi condition mentions wifi recovery`() {
        assertEquals("WiFi 未连接", PauseReason.NoWifi.message)
        assertTrue(PauseReason.NoWifi.resumeHint.contains("WiFi"))
    }
}
