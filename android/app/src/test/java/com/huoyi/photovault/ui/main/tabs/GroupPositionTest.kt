package com.huoyi.photovault.ui.main.tabs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [groupPosition], which decides the corner shape of each row in the
 * grouped (one card per section) lists of the 备份任务 Tab.
 */
class GroupPositionTest {

    @Test
    fun singleItem_isSingle() {
        assertEquals(GroupPosition.Single, groupPosition(index = 0, count = 1))
    }

    @Test
    fun twoItems_areFirstAndLast() {
        assertEquals(GroupPosition.First, groupPosition(index = 0, count = 2))
        assertEquals(GroupPosition.Last, groupPosition(index = 1, count = 2))
    }

    @Test
    fun fiveItems_haveFirstMiddleLast() {
        assertEquals(GroupPosition.First, groupPosition(index = 0, count = 5))
        assertEquals(GroupPosition.Middle, groupPosition(index = 1, count = 5))
        assertEquals(GroupPosition.Middle, groupPosition(index = 2, count = 5))
        assertEquals(GroupPosition.Middle, groupPosition(index = 3, count = 5))
        assertEquals(GroupPosition.Last, groupPosition(index = 4, count = 5))
    }
}
