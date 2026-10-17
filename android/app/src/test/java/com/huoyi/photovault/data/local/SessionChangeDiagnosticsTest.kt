package com.huoyi.photovault.data.local

import com.huoyi.photovault.data.local.SessionChangeDiagnostics.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [SessionChangeDiagnostics] (design 14.1, 验收 38). */
class SessionChangeDiagnosticsTest {

    private val instanceA = "ab12cd34-0000-4000-8000-aaaaaaaaaaaa"
    private val instanceB = "ef34gh56-1111-4111-8111-bbbbbbbbbbbb"

    @Test
    fun `previous missing`() {
        val incoming = StableAccountIdentity(instanceA, 5L)

        assertEquals(listOf(Reason.PREVIOUS_MISSING), SessionChangeDiagnostics.reasons(null, incoming))
        assertEquals(
            "activate changed=true reasons=[PREVIOUS_MISSING] previous=null " +
                "incoming=(instance=ab12cd…, user=5) reauth=false",
            SessionChangeDiagnostics.describe(null, incoming, changed = true, reauthenticatedSameAccount = false)
        )
    }

    @Test
    fun `instance changed only`() {
        val reasons = SessionChangeDiagnostics.reasons(
            StableAccountIdentity(instanceA, 3L), StableAccountIdentity(instanceB, 3L)
        )
        assertEquals(listOf(Reason.INSTANCE_CHANGED), reasons)
    }

    @Test
    fun `user changed only`() {
        val reasons = SessionChangeDiagnostics.reasons(
            StableAccountIdentity(instanceA, 3L), StableAccountIdentity(instanceA, 5L)
        )
        assertEquals(listOf(Reason.USER_CHANGED), reasons)
    }

    @Test
    fun `instance and user changed together`() {
        val previous = StableAccountIdentity(instanceA, 3L)
        val incoming = StableAccountIdentity(instanceB, 5L)

        assertEquals(
            listOf(Reason.INSTANCE_CHANGED, Reason.USER_CHANGED),
            SessionChangeDiagnostics.reasons(previous, incoming)
        )
        assertEquals(
            "activate changed=true reasons=[INSTANCE_CHANGED,USER_CHANGED] " +
                "previous=(instance=ab12cd…, user=3) incoming=(instance=ef34gh…, user=5) reauth=false",
            SessionChangeDiagnostics.describe(previous, incoming, changed = true, reauthenticatedSameAccount = false)
        )
    }

    @Test
    fun `unchanged identity logs empty reasons`() {
        val same = StableAccountIdentity(instanceA, 3L)

        assertTrue(SessionChangeDiagnostics.reasons(same, same).isEmpty())
        assertEquals(
            "activate changed=false reasons=[] previous=(instance=ab12cd…, user=3) " +
                "incoming=(instance=ab12cd…, user=3) reauth=true",
            SessionChangeDiagnostics.describe(same, same, changed = false, reauthenticatedSameAccount = true)
        )
    }

    @Test
    fun `output is one line and never contains a full instance id`() {
        val line = SessionChangeDiagnostics.describe(
            StableAccountIdentity(instanceA, 3L), StableAccountIdentity(instanceB, 5L),
            changed = true, reauthenticatedSameAccount = false
        )

        assertFalse(line.contains(instanceA))
        assertFalse(line.contains(instanceB))
        assertFalse(line.contains(instanceA.take(7)))
        assertFalse(line.contains('\n'))
    }

    @Test
    fun `maskInstanceId keeps six characters`() {
        assertEquals("ab12cd…", SessionChangeDiagnostics.maskInstanceId(instanceA))
        assertEquals("abc…", SessionChangeDiagnostics.maskInstanceId("abc"))
        assertEquals("null", SessionChangeDiagnostics.maskInstanceId(null))
    }
}
