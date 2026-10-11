package com.huoyi.photovault.data.network.endpoint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** [ReachabilityCheck] through a client that has only the routing layer (design §5.2, §9). */
class ReachabilityCheckTest {

    private val servers = mutableListOf<MockWebServer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
        scope.cancel()
    }

    private fun server(): MockWebServer = MockWebServer().also {
        it.start(InetAddress.getByName("127.0.0.1"), 0)
        servers += it
    }

    private val MockWebServer.address: String get() = "http://127.0.0.1:$port"

    private inner class Env(publicAddress: String?, lanAddress: String?, retryOnConnectionFailure: Boolean = true) {
        val config = FakeConfigSource(publicAddress, lanAddress)
        val network = FakeNetworkMonitor(WIFI_1)
        val probe = FakeLanProbe(ProbeOutcome.Success)
        val resolver = EndpointResolver(config, network, probe, FakeClock(), scope)
        val sleeper = RecordingSleeper()
        val counting = CountingInterceptor()
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(EndpointRoutingInterceptor(resolver, config, sleeper))
            .addInterceptor(counting)
            .addNetworkInterceptor(DispatchMarkerInterceptor)
            .retryOnConnectionFailure(retryOnConnectionFailure)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        fun isReachable(session: RouteSession? = null): Boolean = runBlocking {
            ReachabilityCheck.isServerReachable(client, resolver, config, session)
        }
    }

    @Test
    fun `2xx is reachable and the request carries session and timeout tags`() {
        val public = server().apply { enqueue(MockResponse().setBody("{\"status\":\"ok\"}")) }
        val env = Env(public.address, null)
        assertTrue(runBlocking { ReachabilityCheck.check(env.client, env.resolver.ambient) })
        val attempt = env.counting.attempts.single()
        assertSame(env.resolver.ambient, attempt.session)
        assertEquals(TimeoutOverride(10_000, 10_000, 10_000), attempt.timeoutOverride)
        assertEquals("/api/v1/connection/test", public.takeRequest().path)
    }

    @Test
    fun `5xx is not reachable`() {
        val public = server().apply { enqueue(MockResponse().setResponseCode(500)) }
        val env = Env(public.address, null)
        assertFalse(env.isReachable())
    }

    @Test
    fun `no address is not reachable and sends nothing`() {
        val env = Env(null, null)
        assertFalse(env.isReachable())
        assertTrue(env.counting.attempts.isEmpty())
        assertFalse(runBlocking { ReachabilityCheck.check(env.client, env.resolver.ambient) })
        assertTrue(env.counting.attempts.isEmpty())
    }

    @Test
    fun `cancellation propagates`() {
        val public = server().apply { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val env = Env(public.address, null)
        val thrown = runBlocking {
            val pending = async { ReachabilityCheck.check(env.client, env.resolver.ambient) }
            delay(200)
            pending.cancel()
            try {
                pending.await()
                null
            } catch (e: CancellationException) {
                e
            }
        }
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `cold start pre-check waits for the probe and uses the LAN when public is down`() {
        val public = server().also { it.shutdown() }
        val lan = server().apply { enqueue(MockResponse().setBody("{}")) }
        val env = Env(public.address, lan.address)
        env.resolver.start()

        assertTrue(env.isReachable())
        assertEquals(1, env.probe.calls)
        assertEquals(1, lan.requestCount)
        assertEquals(listOf("127.0.0.1:${lan.port}"), env.counting.hostPorts)
    }

    @Test
    fun `explicit task session does not wait for an ambient evaluation`() {
        val lan = server().apply { enqueue(MockResponse().setBody("{}")) }
        val env = Env(server().address, lan.address)
        env.resolver.start()
        val task = runBlocking { env.resolver.beginTask() }
        assertEquals(Route.LAN, task.route)
        val probes = env.probe.calls

        env.resolver.ambient.markLanUnavailable(LockReason.CONSECUTIVE) // ambient now prefers public

        assertTrue(env.isReachable(task))
        assertEquals(probes, env.probe.calls)
        assertSame(task, env.counting.attempts.single().session)
        assertEquals(1, lan.requestCount)
    }

    @Test
    fun `ambient failures during the check are not counted on the active task`() {
        val lan = server().apply {
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            enqueue(MockResponse().setBody("{}"))
        }
        val env = Env(server().address, lan.address, retryOnConnectionFailure = false)
        env.resolver.start()
        val task = runBlocking { env.resolver.beginTask() }
        repeat(3) { task.onLanFailure() }

        assertTrue(env.isReachable())
        assertEquals(2, env.counting.attempts.size)
        env.counting.attempts.forEach { assertSame(env.resolver.ambient, it.session) }
        assertEquals("one ambient LAN failure, retried after 1 s", listOf(1_000L), env.sleeper.sleeps)
        assertEquals(3, task.lanFailures.get())
        assertEquals(Route.LAN, task.route)
        assertEquals(0, task.lockGeneration)
    }
}
