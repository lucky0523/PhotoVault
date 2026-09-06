package com.huoyi.photovault.ui.main.tabs

import com.huoyi.photovault.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pause-reason semantics shown on the backup tasks tab.
 * Text itself lives in localized resources; this test pins each semantic state
 * to the appropriate resource instead of pinning one locale's wording.
 */
class PauseReasonDistinctionTest {

    @Test
    fun `user pause is flagged as user pause`() {
        assertTrue(PauseReason.UserPaused.isUserPause)
    }

    @Test
    fun `user pause uses manual continuation resources`() {
        assertEquals(R.string.pause_user_message, PauseReason.UserPaused.messageRes)
        assertEquals(R.string.pause_user_hint, PauseReason.UserPaused.resumeHintRes)
    }

    @Test
    fun `condition pauses are not user pauses`() {
        assertFalse(PauseReason.LowBattery.isUserPause)
        assertFalse(PauseReason.NoWifi.isUserPause)
        assertFalse(PauseReason.LowBatteryAndNoWifi.isUserPause)
    }

    @Test
    fun `condition pauses use automatic recovery resources`() {
        assertEquals(R.string.pause_low_battery_hint, PauseReason.LowBattery.resumeHintRes)
        assertEquals(R.string.pause_no_wifi_hint, PauseReason.NoWifi.resumeHintRes)
        assertEquals(
            R.string.pause_conditions_hint,
            PauseReason.LowBatteryAndNoWifi.resumeHintRes
        )
    }

    @Test
    fun `no wifi condition uses wifi recovery resources`() {
        assertEquals(R.string.pause_no_wifi_message, PauseReason.NoWifi.messageRes)
        assertEquals(R.string.pause_no_wifi_hint, PauseReason.NoWifi.resumeHintRes)
    }
}
