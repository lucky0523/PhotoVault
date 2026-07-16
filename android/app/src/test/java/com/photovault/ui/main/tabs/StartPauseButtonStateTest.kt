package com.photovault.ui.main.tabs

import com.photovault.service.FileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the manual start/pause button state derived by [TasksTabUiState]
 * (Requirement 24: 备份任务的手动开始/暂停控制).
 *
 * Covers:
 * - R-24.1: button shows "暂停" while a backup is actively in progress, "开始"
 *   when paused or idle ([TasksTabUiState.isStartPauseShowingPause]).
 * - R-24.4: button is disabled when the queue is empty AND no task is in progress
 *   ([TasksTabUiState.isStartPauseEnabled]).
 */
class StartPauseButtonStateTest {

    private fun file(name: String = "a.jpg") = FileInfo(
        uri = "content://$name",
        fileName = name,
        fileSize = 1L,
        createdTime = 0L,
        mimeType = "image/jpeg",
        folderUri = "content://folder"
    )

    // --- R-24.1: label semantics ---

    @Test
    fun `shows pause while backup actively running`() {
        val state = TasksTabUiState(isBackupRunning = true, isPaused = false)
        assertTrue(state.isStartPauseShowingPause)
    }

    @Test
    fun `shows start while paused`() {
        val state = TasksTabUiState(isBackupRunning = true, isPaused = true)
        assertFalse(state.isStartPauseShowingPause)
    }

    @Test
    fun `shows start while idle`() {
        val state = TasksTabUiState(isBackupRunning = false, isPaused = false)
        assertFalse(state.isStartPauseShowingPause)
    }

    // --- R-24.4: enabled/disabled semantics ---

    @Test
    fun `disabled when queue empty and nothing in progress`() {
        val state = TasksTabUiState(
            isBackupRunning = false,
            isPaused = false,
            queuedFiles = emptyList()
        )
        assertFalse(state.isStartPauseEnabled)
    }

    @Test
    fun `enabled when queue has files even if idle`() {
        val state = TasksTabUiState(
            isBackupRunning = false,
            isPaused = false,
            queuedFiles = listOf(file())
        )
        assertTrue(state.isStartPauseEnabled)
    }

    @Test
    fun `enabled while running`() {
        val state = TasksTabUiState(isBackupRunning = true, isPaused = false)
        assertTrue(state.isStartPauseEnabled)
    }

    @Test
    fun `enabled while paused with empty queue so user can resume`() {
        val state = TasksTabUiState(
            isBackupRunning = false,
            isPaused = true,
            queuedFiles = emptyList()
        )
        assertTrue(state.isStartPauseEnabled)
    }
}
