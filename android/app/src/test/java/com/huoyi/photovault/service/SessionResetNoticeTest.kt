package com.huoyi.photovault.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [SessionResetNotice]'s request/consume state. */
class SessionResetNoticeTest {

    @After
    fun tearDown() {
        // Process-wide singleton: leave it clean for other tests.
        SessionResetNotice.consume()
    }

    @Test
    fun `starts idle, request sets pending, consume clears it`() {
        SessionResetNotice.consume()
        assertFalse(SessionResetNotice.pending.value)

        SessionResetNotice.request()
        assertTrue(SessionResetNotice.pending.value)

        SessionResetNotice.consume()
        assertFalse(SessionResetNotice.pending.value)
    }

    @Test
    fun `repeated requests collapse into one pending notice`() {
        SessionResetNotice.request()
        SessionResetNotice.request()
        assertTrue(SessionResetNotice.pending.value)

        SessionResetNotice.consume()
        assertFalse(SessionResetNotice.pending.value)
    }
}
