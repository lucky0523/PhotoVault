package com.photovault.ui.main.tabs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [TasksTabViewModel.computeProgressPercent], the pure function
 * that derives an AUTO_OFF paused task's integer progress (0..100) from its
 * persisted resume state (R-26.2/26.3). Property 20 covers this exhaustively in
 * 27.10; these examples pin the boundary behaviour.
 */
class ComputeProgressPercentTest {

    @Test
    fun `non-positive total chunks yields zero`() {
        assertEquals(0, TasksTabViewModel.computeProgressPercent(-1, 0))
        assertEquals(0, TasksTabViewModel.computeProgressPercent(5, -3))
    }

    @Test
    fun `nothing confirmed yields zero`() {
        // uploadedChunkIndex = -1 means no chunk confirmed yet.
        assertEquals(0, TasksTabViewModel.computeProgressPercent(-1, 10))
    }

    @Test
    fun `first chunk confirmed floors to integer percent`() {
        // 1 of 10 chunks -> 10%
        assertEquals(10, TasksTabViewModel.computeProgressPercent(0, 10))
        // 1 of 3 chunks -> 33% (integer division floors)
        assertEquals(33, TasksTabViewModel.computeProgressPercent(0, 3))
    }

    @Test
    fun `last chunk confirmed yields one hundred`() {
        assertEquals(100, TasksTabViewModel.computeProgressPercent(9, 10))
    }

    @Test
    fun `index beyond total is clamped to one hundred`() {
        assertEquals(100, TasksTabViewModel.computeProgressPercent(20, 10))
    }
}
