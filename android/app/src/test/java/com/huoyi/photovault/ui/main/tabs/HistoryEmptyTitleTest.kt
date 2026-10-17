package com.huoyi.photovault.ui.main.tabs

import com.huoyi.photovault.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [historyFilteredEmptyTitleRes], which picks the filter-specific
 * empty-state title of the 备份任务 history view.
 */
class HistoryEmptyTitleTest {

    @Test
    fun all_keepsDefaultTitle() {
        assertNull(historyFilteredEmptyTitleRes(HistoryFilter.ALL))
    }

    @Test
    fun success_usesSuccessTitle() {
        assertEquals(R.string.tasks_history_empty_success, historyFilteredEmptyTitleRes(HistoryFilter.SUCCESS))
    }

    @Test
    fun failed_usesFailedTitle() {
        assertEquals(R.string.tasks_history_empty_failed, historyFilteredEmptyTitleRes(HistoryFilter.FAILED))
    }

    @Test
    fun skipped_usesSkippedTitle() {
        assertEquals(R.string.tasks_history_empty_skipped, historyFilteredEmptyTitleRes(HistoryFilter.SKIPPED))
    }
}
