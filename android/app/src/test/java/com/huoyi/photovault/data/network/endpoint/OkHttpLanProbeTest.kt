package com.huoyi.photovault.data.network.endpoint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** [OkHttpLanProbe] against MockWebServer, using real time (OkHttp runs on its own threads). */
class OkHttpLanProbeTest {

    private lateinit var server: MockWebServer
    private lateinit var lanBase: HttpUrl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        lanBase = "http://127.0.0.1:${server.port}".toHttpUrl()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun body(status: String? = "ok", app: String? = "photovault", fingerprint: String? = TEST_FINGERPRINT): String {
        val fields = listOfNotNull(
            status?.let { "\"status\":\"$it\"" },
            "\"version\":\"1.4\"",
            app?.let { "\"app\":\"$it\"" },
            fingerprint?.let { "\"instance_fingerprint\":\"$it\"" }
        )
        return fields.joinToString(",", "{", "}")
    }

    private fun probe(
        expected: String? = TEST_FINGERPRINT,
        skipIdentity: Boolean = false,
        probe: OkHttpLanProbe = OkHttpLanProbe()
    ): ProbeOutcome = runBlocking { probe.probe(lanBase, expected, skipIdentity) }

    @Test
    fun `matching PhotoVault instance is a success`() {
        server.enqueue(MockResponse().setBody(body()))
        assertEquals(ProbeOutcome.Success, probe())
        val recorded = server.takeRequest()
        assertEquals("/api/v1/connection/test", recorded.path)
        assertEquals("GET", recorded.method)
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `missing or wrong app is not PhotoVault`() {
        server.enqueue(MockResponse().setBody(body(app = null)))
        assertEquals(ProbeOutcome.NotPhotoVault, probe())
        server.enqueue(MockResponse().setBody(body(app = "other")))
        assertEquals(ProbeOutcome.NotPhotoVault, probe())
        server.enqueue(MockResponse().setBody(body(status = "degraded")))
        assertEquals(ProbeOutcome.NotPhotoVault, probe())
    }

    @Test
    fun `different instance fingerprint is an identity mismatch`() {
        server.enqueue(MockResponse().setBody(body(fingerprint = "08cf6b7fb5c87de79237818508128056")))
        assertEquals(ProbeOutcome.IdentityMismatch, probe())
        server.enqueue(MockResponse().setBody(body(fingerprint = null)))
        assertEquals(ProbeOutcome.IdentityMismatch, probe())
    }

    @Test
    fun `no expected fingerprint is NoBaseline unless identity is skipped`() {
        server.enqueue(MockResponse().setBody(body()))
        assertEquals(ProbeOutcome.NoBaseline, probe(expected = null))
        server.enqueue(MockResponse().setBody(body(fingerprint = "08cf6b7fb5c87de79237818508128056")))
        assertEquals(ProbeOutcome.Success, probe(expected = null, skipIdentity = true))
    }

    @Test
    fun `non 2xx is an HTTP status failure`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody(body()))
        assertEquals(ProbeOutcome.HttpStatus(503), probe())
    }

    @Test
    fun `redirect is not followed`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${server.port}/elsewhere"))
        server.enqueue(MockResponse().setBody(body()))
        assertEquals(ProbeOutcome.HttpStatus(302), probe())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `invalid json is malformed`() {
        server.enqueue(MockResponse().setBody("<html>router login</html>"))
        assertEquals(ProbeOutcome.Malformed, probe())
        server.enqueue(MockResponse().setBody(""))
        assertEquals(ProbeOutcome.Malformed, probe())
    }

    @Test
    fun `refused connection is ConnectFailed`() {
        lanBase = "http://127.0.0.1:${closedPort()}".toHttpUrl()
        assertEquals(ProbeOutcome.ConnectFailed("ConnectException"), probe())
    }

    @Test
    fun `slow headers time out at about 1_5 s`() {
        server.enqueue(MockResponse().setBody(body()).setHeadersDelay(2, TimeUnit.SECONDS))
        val start = System.nanoTime()
        assertEquals(ProbeOutcome.Timeout, probe())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs in 1_300..2_000)
    }

    @Test
    fun `blocking DNS is bounded by the coroutine timeout`() {
        val dnsCalls = AtomicInteger()
        val client = defaultProbeClient().newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    dnsCalls.incrementAndGet()
                    Thread.sleep(3_000)
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            })
            .build()
        // Must be a host name: OkHttp skips the Dns for IP literals.
        lanBase = "http://slow-dns.test:${server.port}".toHttpUrl()
        val start = System.nanoTime()
        val outcome = probe(probe = OkHttpLanProbe(client))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(ProbeOutcome.Timeout, outcome)
        assertTrue("took $elapsedMs ms", elapsedMs in 1_400..2_000)
        assertEquals(1, dnsCalls.get())
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `single threaded caller is not blocked while probing`() {
        server.enqueue(MockResponse().setBody(body()).setHeadersDelay(500, TimeUnit.MILLISECONDS))
        newSingleThreadContext("probe-caller").use { caller ->
            runBlocking(caller) {
                val ticks = AtomicInteger()
                launch {
                    delay(100)
                    ticks.incrementAndGet()
                }
                val outcome = OkHttpLanProbe().probe(lanBase, TEST_FINGERPRINT, skipIdentity = false)
                assertEquals(ProbeOutcome.Success, outcome)
                assertEquals("caller thread ran other work during the probe", 1, ticks.get())
            }
        }
    }

    @Test
    fun `cancelling the caller cancels the call`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = defaultProbeClient()
        val thrown = runBlocking {
            val pending = async { OkHttpLanProbe(client).probe(lanBase, TEST_FINGERPRINT, skipIdentity = false) }
            delay(300)
            pending.cancel()
            try {
                pending.await()
                null
            } catch (e: CancellationException) {
                e
            }
        }
        assertTrue(thrown is CancellationException)
        // The underlying call is cancelled and finishes promptly.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (client.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(0, client.dispatcher.runningCallsCount())
        assertEquals(1, server.requestCount)
    }
}
