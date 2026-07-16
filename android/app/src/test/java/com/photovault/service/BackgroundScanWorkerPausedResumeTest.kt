package com.photovault.service

import com.photovault.service.BackgroundScanWorker.Companion.PausedScanAction
import com.photovault.service.BackgroundScanWorker.Companion.decidePausedResumeAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [BackgroundScanWorker.decidePausedResumeAction] — how an
 * automatic scan handles a backup the user manually paused.
 *
 * Feature: "自动备份"手动暂停后的恢复触发 (trigger ③, periodic scan):
 *  - app in background → resume silently
 *  - app in foreground → ask the user to confirm
 *  - manual runs and runs with no outstanding user pause → normal start path
 */
class BackgroundScanWorkerPausedResumeTest {

    @Test
    fun `automatic run, user paused, background - resumes silently`() {
        assertEquals(
            PausedScanAction.RESUME,
            decidePausedResumeAction(
                allowBackup = true,
                isManualRun = false,
                userPaused = true,
                isForeground = false
            )
        )
    }

    @Test
    fun `automatic run, user paused, foreground - prompts for confirmation`() {
        assertEquals(
            PausedScanAction.PROMPT,
            decidePausedResumeAction(
                allowBackup = true,
                isManualRun = false,
                userPaused = true,
                isForeground = true
            )
        )
    }

    @Test
    fun `no outstanding user pause - defers to the normal start path`() {
        for (foreground in listOf(true, false)) {
            assertEquals(
                PausedScanAction.NONE,
                decidePausedResumeAction(
                    allowBackup = true,
                    isManualRun = false,
                    userPaused = false,
                    isForeground = foreground
                )
            )
        }
    }

    @Test
    fun `manual run never prompts or auto-resumes here`() {
        // A manual "立即备份" run resumes eagerly through its own path; the scan
        // tail must not additionally prompt/resume for it.
        for (foreground in listOf(true, false)) {
            assertEquals(
                PausedScanAction.NONE,
                decidePausedResumeAction(
                    allowBackup = true,
                    isManualRun = true,
                    userPaused = true,
                    isForeground = foreground
                )
            )
        }
    }

    @Test
    fun `backup not allowed - takes no resume action`() {
        // When auto-backup is off (and this isn't a manual run), the scan never
        // resumes on its own.
        assertEquals(
            PausedScanAction.NONE,
            decidePausedResumeAction(
                allowBackup = false,
                isManualRun = false,
                userPaused = true,
                isForeground = false
            )
        )
    }
}
