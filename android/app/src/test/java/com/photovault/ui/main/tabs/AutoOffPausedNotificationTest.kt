package com.photovault.ui.main.tabs

import android.app.NotificationManager
import android.content.Context
import com.photovault.service.BackupForegroundService
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
 * Wording/notification distinction tests for the AUTO_OFF (third) pause source
 * (photo-backup-service, task 27.11; R-30.1/R-30.2).
 *
 * The AUTO_OFF pause — files kept because the user turned off "自动备份"
 * mid-upload — must be worded distinctly from the two existing pause sources so
 * users understand it will NOT auto-resume and must be continued manually:
 * - **USER** pause  → [PauseReason.UserPaused] ("已手动暂停" / "点击开始继续").
 * - **CONDITION** pause → [PauseReason.LowBattery]/[PauseReason.NoWifi]/…
 *   ("…将…自动恢复").
 * - **AUTO_OFF** → the standalone reminder posted by
 *   [BackupForegroundService.postAutoOffPausedNotification] ("自动备份已关闭…").
 *
 * These assertions pin the notification text (R-30.2) and prove it is disjoint
 * from the USER/CONDITION wording exposed by the [PauseReason] sealed class
 * (R-30.1: the AUTO_OFF phrasing names "自动备份已关闭" and does not reuse the
 * USER/CONDITION messages).
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

        // R-30.2: names the cause and the number of preserved tasks.
        assertTrue("title names 自动备份已关闭: $title", title.contains("自动备份已关闭"))
        assertTrue("text carries the count N=3: $text", text.contains("3"))

        // R-30.1: distinct from the USER pause wording.
        assertFalse(
            "AUTO_OFF text must not reuse the USER pause message",
            text.contains(PauseReason.UserPaused.message)
        )
        assertFalse(
            "AUTO_OFF text must not reuse the USER resume hint",
            text.contains(PauseReason.UserPaused.resumeHint)
        )

        // R-30.1: distinct from the CONDITION pause wording.
        assertFalse(
            "AUTO_OFF text must not reuse the low-battery message",
            text.contains(PauseReason.LowBattery.message)
        )
        assertFalse(
            "AUTO_OFF text must not reuse the no-wifi message",
            text.contains(PauseReason.NoWifi.message)
        )
    }

    @Test
    fun `posts nothing when no in-flight task was preserved`() {
        // R-25.5: nothing was in flight → no reminder at all.
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
    fun `USER and CONDITION pause wordings are themselves distinct (R-30_1)`() {
        // The three sources' messages are mutually distinct, and only CONDITION
        // advertises automatic recovery — the USER (and, by design, AUTO_OFF)
        // pauses require a manual tap to continue.
        assertTrue(PauseReason.UserPaused.isUserPause)
        assertFalse("user pause must not claim auto-recovery", PauseReason.UserPaused.resumeHint.contains("自动"))
        assertTrue(PauseReason.LowBattery.resumeHint.contains("自动"))
        assertTrue(PauseReason.NoWifi.resumeHint.contains("自动"))

        val messages = listOf(
            PauseReason.UserPaused.message,
            PauseReason.LowBattery.message,
            PauseReason.NoWifi.message,
            PauseReason.LowBatteryAndNoWifi.message
        )
        assertEquals("pause messages must be mutually distinct", messages.size, messages.toSet().size)
    }

    @Test
    fun `the AUTO_OFF reminder uses a channel separate from the foreground progress channel`() {
        // R-30.2: the reminder survives the foreground service (and its ongoing
        // notification) being torn down, so it must use a distinct channel/ID.
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
