package com.huoyi.photovault.ui.login

import com.huoyi.photovault.data.network.endpoint.AddressError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressCheckPlanTest {

    @Test
    fun `both blank requires any and yields no canonical`() {
        val plan = planAddressCheck("", "  ")
        assertTrue(plan.requiredAnyMissing)
        assertNull(plan.publicError)
        assertNull(plan.lanError)
        assertNull(plan.publicCanonical)
        assertNull(plan.lanCanonical)
    }

    @Test
    fun `invalid public with valid LAN keeps the LAN canonical`() {
        val plan = planAddressCheck("ftp://nas.example.com", "192.168.1.10:8000")
        assertFalse(plan.requiredAnyMissing)
        assertEquals(AddressError.BAD_SCHEME, plan.publicError)
        assertNull(plan.publicCanonical)
        assertNull(plan.lanError)
        assertNotNull(plan.lanCanonical)
    }

    @Test
    fun `identical addresses fail on both sides`() {
        val plan = planAddressCheck("http://192.168.1.10:8000", "192.168.1.10:8000/")
        assertFalse(plan.requiredAnyMissing)
        assertEquals(AddressError.SAME_AS_OTHER, plan.publicError)
        assertEquals(AddressError.SAME_AS_OTHER, plan.lanError)
        assertNull(plan.publicCanonical)
        assertNull(plan.lanCanonical)
    }

    @Test
    fun `only valid LAN yields only the LAN canonical`() {
        val plan = planAddressCheck("", "192.168.1.10:8000")
        assertFalse(plan.requiredAnyMissing)
        assertNull(plan.publicError)
        assertNull(plan.lanError)
        assertNull(plan.publicCanonical)
        assertEquals("http://192.168.1.10:8000", plan.lanCanonical)
    }

    @Test
    fun `whitespace-only field has neither error nor canonical`() {
        val plan = planAddressCheck("https://photos.example.com", "   ")
        assertFalse(plan.requiredAnyMissing)
        assertNull(plan.publicError)
        assertEquals("https://photos.example.com", plan.publicCanonical)
        assertNull(plan.lanError)
        assertNull(plan.lanCanonical)
    }

    @Test
    fun `bare IP and port is canonicalised with http scheme`() {
        val plan = planAddressCheck("https://photos.example.com", "192.168.1.10:8000")
        assertEquals("http://192.168.1.10:8000", plan.lanCanonical)
        assertEquals("https://photos.example.com", plan.publicCanonical)
    }
}
