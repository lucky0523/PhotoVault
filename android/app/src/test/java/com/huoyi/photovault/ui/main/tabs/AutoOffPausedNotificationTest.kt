package com.huoyi.photovault.ui.main.tabs

import android.app.NotificationManager
import android.content.Context
import com.huoyi.photovault.R
import com.huoyi.photovault.service.BackupForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Wording/notification distinction tests for the AUTO_OFF (third) pause source.
 * User-visible wording is resolved through Android resources so the same
 * behavior is covered independently of the active locale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AutoOffPausedNotificationTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `posts a distinct AUTO_OFF reminder naming the disabled auto-backup and count`() {
        val context = context()

        BackupForegroundService.postAutoOffPausedNotification(context, count = 3)

        val nm = notificationManager(context)
        val notification =
            shadowOf(nm).getNotification(BackupForegroundService.AUTO_OFF_NOTIFICATION_ID)
        assertNotNull("an AUTO_OFF reminder must be posted", notification)

        val shadow = shadowOf(notification)
        val title = shadow.contentTitle?.toString().orEmpty()
        val text = shadow.contentText?.toString().orEmpty()

        assertEquals(context.getString(R.string.notification_backup_auto_off_title), title)
        assertEquals(context.getString(R.string.notification_backup_auto_off_text, 3), text)
        assertTrue("text carries the count N=3: $text", text.contains("3"))

        assertFalse(
            "AUTO_OFF text must not reuse the USER pause message",
            text.contains(context.getString(PauseReason.UserPaused.messageRes))
        )
        assertFalse(
            "AUTO_OFF text must not reuse the USER resume hint",
            text.contains(context.getString(PauseReason.UserPaused.resumeHintRes))
        )
        assertFalse(
            "AUTO_OFF text must not reuse the low-battery message",
            text.contains(context.getString(PauseReason.LowBattery.messageRes))
        )
        assertFalse(
            "AUTO_OFF text must not reuse the no-wifi message",
            text.contains(context.getString(PauseReason.NoWifi.messageRes))
        )
    }

    @Test
    fun `posts nothing when no in-flight task was preserved`() {
        val context = context()

        BackupForegroundService.postAutoOffPausedNotification(context, count = 0)

        val nm = notificationManager(context)
        assertNull(
            "no reminder should be posted when count <= 0",
            shadowOf(nm).getNotification(BackupForegroundService.AUTO_OFF_NOTIFICATION_ID)
        )
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun `USER and CONDITION pause resources are themselves distinct`() {
        assertTrue(PauseReason.UserPaused.isUserPause)
        assertFalse(PauseReason.LowBattery.isUserPause)
        assertFalse(PauseReason.NoWifi.isUserPause)
        assertFalse(PauseReason.LowBatteryAndNoWifi.isUserPause)

        assertEquals(R.string.pause_user_hint, PauseReason.UserPaused.resumeHintRes)
        assertEquals(R.string.pause_low_battery_hint, PauseReason.LowBattery.resumeHintRes)
        assertEquals(R.string.pause_no_wifi_hint, PauseReason.NoWifi.resumeHintRes)
        assertEquals(R.string.pause_conditions_hint, PauseReason.LowBatteryAndNoWifi.resumeHintRes)

        val messages = listOf(
            PauseReason.UserPaused.messageRes,
            PauseReason.LowBattery.messageRes,
            PauseReason.NoWifi.messageRes,
            PauseReason.LowBatteryAndNoWifi.messageRes
        )
        assertEquals("pause message resources must be mutually distinct", messages.size, messages.toSet().size)
    }

    @Test
    fun `the AUTO_OFF reminder uses a channel separate from the foreground progress channel`() {
        assertFalse(
            "AUTO_OFF channel must differ from the progress channel",
            BackupForegroundService.AUTO_OFF_CHANNEL_ID == BackupForegroundService.NOTIFICATION_CHANNEL_ID
        )
        assertFalse(
            "AUTO_OFF notification id must differ from the progress id",
            BackupForegroundService.AUTO_OFF_NOTIFICATION_ID == BackupForegroundService.NOTIFICATION_ID
        )
    }
}
