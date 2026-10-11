package com.huoyi.photovault.data.network.endpoint

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** design §4.4 timeout table and min rule. */
class TimeoutsForTest {

    private val shared = Timeouts(15_000, 30_000, 30_000)
    private val heartbeat = Timeouts(5_000, 5_000, 30_000)

    private fun get(path: String = "/api/v1/files/list") =
        Request.Builder().url("http://photovault.invalid$path").get().build()

    private fun post(path: String) =
        Request.Builder().url("http://photovault.invalid$path").post("{}".toRequestBody()).build()

    private fun withOverride(request: Request) = request.newBuilder()
        .tag(TimeoutOverride::class.java, TimeoutOverride(10_000, 10_000, 10_000))
        .build()

    @Test
    fun `lan complete keeps 30s read and write`() {
        assertEquals(Timeouts(3_000, 30_000, 30_000), timeoutsFor(Route.LAN, post("/api/v1/backup/complete"), shared))
    }

    @Test
    fun `lan other requests use 3-10-10`() {
        assertEquals(Timeouts(3_000, 10_000, 10_000), timeoutsFor(Route.LAN, get(), shared))
        assertEquals(Timeouts(3_000, 10_000, 10_000), timeoutsFor(Route.LAN, post("/api/v1/backup/init"), shared))
        assertEquals(Timeouts(3_000, 10_000, 10_000), timeoutsFor(Route.LAN, post("/api/v1/backup/chunk"), shared))
    }

    @Test
    fun `lan with override still uses 3-10-10`() {
        assertEquals(Timeouts(3_000, 10_000, 10_000), timeoutsFor(Route.LAN, withOverride(get()), shared))
    }

    @Test
    fun `public uses the override when present`() {
        assertEquals(Timeouts(10_000, 10_000, 10_000), timeoutsFor(Route.PUBLIC, withOverride(get()), shared))
    }

    @Test
    fun `public without override keeps client values`() {
        assertNull(timeoutsFor(Route.PUBLIC, get(), shared))
        assertNull(timeoutsFor(Route.PUBLIC, post("/api/v1/backup/complete"), shared))
    }

    @Test
    fun `lan never exceeds a tighter client value`() {
        assertEquals(Timeouts(3_000, 5_000, 10_000), timeoutsFor(Route.LAN, get(), heartbeat))
        assertEquals(Timeouts(1_000, 2_000, 3_000), timeoutsFor(Route.LAN, get(), Timeouts(1_000, 2_000, 3_000)))
    }

    @Test
    fun `zero client value means unlimited so the table value applies`() {
        assertEquals(Timeouts(3_000, 10_000, 10_000), timeoutsFor(Route.LAN, get(), Timeouts(0, 0, 0)))
        assertEquals(Timeouts(3_000, 30_000, 5_000), timeoutsFor(Route.LAN, post("/api/v1/backup/complete"), Timeouts(0, 0, 5_000)))
    }
}
