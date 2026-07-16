package com.photovault.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ConditionCheckWorker.shouldAutoResume].
 *
 * Feature: 备份任务的手动开始/暂停控制 (R-24), task 25.2.
 *
 * The condition-recovery worker may auto-(re)start a backup when battery/network
 * conditions recover, but a *user* pause must take priority over condition
 * recovery: while the backup was paused by the user (ACTION_PAUSE →
 * [PauseReason.USER]), condition recovery must NOT auto-resume it — only the user
 * tapping "开始" (ACTION_RESUME) may (R-24.5).
 */
class ConditionCheckAutoResumeTest {

    @Test
    fun `auto resumes when conditions recovered and not user-paused`() {
        assertTrue(
            ConditionCheckWorker.shouldAutoResume(
                autoBackupEnabled = true,
                queueSize = 3,
                conditionsRecovered = true,
                isUserPaused = false
            )
        )
    }

    @Test
    fun `user pause is not overridden by condition recovery`() {
        // Even with the auto-backup toggle on, queued work, and conditions
        // recovered, a user pause must keep the backup paused (R-24.5).
        assertFalse(
            ConditionCheckWorker.shouldAutoResume(
                autoBackupEnabled = true,
                queueSize = 3,
                conditionsRecovered = true,
                isUserPaused = true
            )
        )
    }

    @Test
    fun `does not resume when auto-backup toggle is off`() {
        assertFalse(
            ConditionCheckWorker.shouldAutoResume(
                autoBackupEnabled = false,
                queueSize = 3,
                conditionsRecovered = true,
                isUserPaused = false
            )
        )
    }

    @Test
    fun `does not resume when queue is empty`() {
        assertFalse(
            ConditionCheckWorker.shouldAutoResume(
                autoBackupEnabled = true,
                queueSize = 0,
                conditionsRecovered = true,
                isUserPaused = false
            )
        )
    }

    @Test
    fun `does not resume when conditions have not recovered`() {
        assertFalse(
            ConditionCheckWorker.shouldAutoResume(
                autoBackupEnabled = true,
                queueSize = 3,
                conditionsRecovered = false,
                isUserPaused = false
            )
        )
    }

    @Test
    fun `user pause blocks resume regardless of other flags`() {
        // isUserPaused dominates: any other combination is still blocked.
        for (auto in listOf(true, false)) {
            for (queue in listOf(0, 5)) {
                for (recovered in listOf(true, false)) {
                    assertFalse(
                        ConditionCheckWorker.shouldAutoResume(
                            autoBackupEnabled = auto,
                            queueSize = queue,
                            conditionsRecovered = recovered,
                            isUserPaused = true
                        )
                    )
                }
            }
        }
    }
}
