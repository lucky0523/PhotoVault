package com.huoyi.photovault.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Decision order of [FailedUploadPolicy.decide] (design §5.3, §9). */
class FailedUploadPolicyTest {

    private class Calls {
        var shouldPause = 0
        var reachable = 0
    }

    private fun decide(
        shouldRetry: Boolean = true,
        pause: Boolean = false,
        lockChanged: () -> Boolean = { false },
        alreadyRetried: Boolean = false,
        calls: Calls = Calls(),
        reachable: suspend () -> Boolean = { true }
    ): FailedUploadAction = runBlocking {
        FailedUploadPolicy.decide(
            shouldRetry = shouldRetry,
            shouldPause = { calls.shouldPause++; pause },
            lockChangedDuringFile = lockChanged,
            alreadyRetriedAfterSwitch = alreadyRetried,
            isServerReachable = { calls.reachable++; reachable() }
        )
    }

    @Test
    fun `condition pause wins`() {
        val calls = Calls()
        assertEquals(
            FailedUploadAction.PAUSE_CONDITION,
            decide(pause = true, lockChanged = { true }, calls = calls)
        )
        assertEquals(0, calls.reachable)
    }

    @Test
    fun `non retryable failure is recorded without calling shouldPause or reachability`() {
        val calls = Calls()
        assertEquals(
            FailedUploadAction.RECORD_FAILED,
            decide(shouldRetry = false, pause = true, lockChanged = { true }, calls = calls)
        )
        assertEquals(0, calls.shouldPause)
        assertEquals(0, calls.reachable)
    }

    @Test
    fun `switched before the check re-queues without a reachability check`() {
        val calls = Calls()
        assertEquals(
            FailedUploadAction.REQUEUE_AFTER_SWITCH,
            decide(lockChanged = { true }, calls = calls)
        )
        assertEquals(0, calls.reachable)
    }

    @Test
    fun `second failure after a switch follows the existing path`() {
        assertEquals(
            FailedUploadAction.RECORD_FAILED,
            decide(lockChanged = { true }, alreadyRetried = true, reachable = { true })
        )
        assertEquals(
            FailedUploadAction.PAUSE_UNREACHABLE,
            decide(lockChanged = { true }, alreadyRetried = true, reachable = { false })
        )
    }

    @Test
    fun `without a switch the result matches today`() {
        assertEquals(FailedUploadAction.RECORD_FAILED, decide(reachable = { true }))
        assertEquals(FailedUploadAction.PAUSE_UNREACHABLE, decide(reachable = { false }))
    }

    @Test
    fun `switch during the reachability check re-queues and checks once`() {
        var locked = false
        val calls = Calls()
        val action = decide(
            lockChanged = { locked },
            calls = calls,
            reachable = { locked = true; true }
        )
        assertEquals(FailedUploadAction.REQUEUE_AFTER_SWITCH, action)
        assertEquals(1, calls.reachable)
    }

    @Test
    fun `switch during the check after an earlier retry records failed`() {
        var locked = false
        val action = decide(
            lockChanged = { locked },
            alreadyRetried = true,
            reachable = { locked = true; true }
        )
        assertEquals(FailedUploadAction.RECORD_FAILED, action)
    }

    @Test
    fun `switch during the check and unreachable still re-queues`() {
        var locked = false
        val action = decide(
            lockChanged = { locked },
            reachable = { locked = true; false }
        )
        assertEquals(FailedUploadAction.REQUEUE_AFTER_SWITCH, action)
    }
}
