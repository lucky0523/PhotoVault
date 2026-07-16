package com.photovault.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BackupForegroundService.shouldStopAutoOnDisable].
 *
 * Feature: photo-backup-service, task 24 (修复：运行中关闭"自动备份"仅停止自动任务).
 *
 * When the user turns OFF the "自动备份" switch mid-run, the run source decides
 * what happens (R-3.13/3.14/3.15):
 * - An **automatic** run in progress is stopped and its queue cleared (R-3.14).
 * - A **manual** run in progress is left to finish, queue untouched (R-3.15).
 * - When the service isn't running but the queue still holds automatically
 *   enqueued files, those are cleared too (isManualRun is false → stop+clear).
 */
class AutoBackupToggleOffTest {

    @Test
    fun `stops and clears when an automatic run is in progress`() {
        // Running + automatic → stop the service and clear the queue (R-3.14).
        assertTrue(
            BackupForegroundService.shouldStopAutoOnDisable(
                isRunning = true,
                isManualRun = false
            )
        )
    }

    @Test
    fun `leaves a manual run untouched`() {
        // Running + manual → do nothing so the manual task finishes (R-3.15).
        assertFalse(
            BackupForegroundService.shouldStopAutoOnDisable(
                isRunning = true,
                isManualRun = true
            )
        )
    }

    @Test
    fun `clears residual automatically-queued files when service not running`() {
        // Not running (isManualRun is false while idle) → still clear the queue so
        // residual automatically-enqueued files don't upload later (R-3.14).
        assertTrue(
            BackupForegroundService.shouldStopAutoOnDisable(
                isRunning = false,
                isManualRun = false
            )
        )
    }

    @Test
    fun `stale manual marker while not running still clears`() {
        // Defensive: even if the manual marker weren't reset, "not running" must
        // still allow clearing residual queue — only a *running* manual task is
        // protected (R-3.15 applies to an in-progress manual task).
        assertTrue(
            BackupForegroundService.shouldStopAutoOnDisable(
                isRunning = false,
                isManualRun = true
            )
        )
    }
}
