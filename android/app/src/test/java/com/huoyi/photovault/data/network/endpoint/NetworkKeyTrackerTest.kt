package com.huoyi.photovault.data.network.endpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkKeyTrackerTest {

    @Test
    fun `unseeded tracker reports NONE`() {
        assertEquals(NetworkKey.NONE, NetworkKeyTracker().current)
    }

    @Test
    fun `seeded key is not a change`() {
        val tracker = NetworkKeyTracker()
        tracker.seed(WIFI_1)
        assertEquals(WIFI_1, tracker.current)
        assertFalse(tracker.offer(WIFI_1))
    }

    @Test
    fun `different handle or losing the network is a change`() {
        val tracker = NetworkKeyTracker()
        tracker.seed(WIFI_1)
        assertTrue(tracker.offer(WIFI_2))
        assertEquals(WIFI_2, tracker.current)
        assertTrue(tracker.offer(NetworkKey.NONE))
        assertEquals(NetworkClass.NONE, tracker.current.networkClass)
    }

    @Test
    fun `same handle with a different class is a change`() {
        val tracker = NetworkKeyTracker()
        tracker.seed(WIFI_1)
        assertTrue(tracker.offer(NetworkKey(WIFI_1.handle, NetworkClass.OTHER)))
    }

    @Test
    fun `repeated offers of the same key report only the first`() {
        val tracker = NetworkKeyTracker()
        tracker.seed(WIFI_1)
        assertTrue(tracker.offer(CELLULAR))
        assertFalse(tracker.offer(CELLULAR))
        assertFalse(tracker.offer(CELLULAR))
    }
}
