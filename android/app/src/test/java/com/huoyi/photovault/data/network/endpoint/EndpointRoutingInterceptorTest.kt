package com.huoyi.photovault.data.network.endpoint

import com.huoyi.photovault.data.local.CredentialSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Routing execution layer against real sockets (design §4.4, §9). The LAN address is
 * `127.0.0.1:<closed port>` (connection refused) or a MockWebServer bound to 127.0.0.1,
 * so OkHttp has exactly one route and never retries internally across routes.
 */
class EndpointRoutingInterceptorTest {

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
    private val MockWebServer.hostPort: String get() = "127.0.0.1:$port"

    private inner class Env(
        publicAddress: String?,
        lanAddress: String?,
        initialNetwork: NetworkKey = WIFI_1,
        readTimeoutMs: Long = 2_000L,
        sleeper: Sleeper? = null
    ) {
        val config = FakeConfigSource(publicAddress, lanAddress)
        val network = FakeNetworkMonitor(initialNetwork)
        val probe = FakeLanProbe(ProbeOutcome.Success)
        val clock = FakeClock()
        val resolver = EndpointResolver(config, network, probe, clock, scope)
        val recordingSleeper = RecordingSleeper()
        val counting = CountingInterceptor()
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(EndpointRoutingInterceptor(resolver, config, sleeper ?: recordingSleeper))
            .addInterceptor(counting)
            .addNetworkInterceptor(DispatchMarkerInterceptor)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()

        init {
            resolver.start()
            runBlocking { resolver.awaitAmbientEvaluation() }
        }

        fun request(path: String = "/api/v1/files/list", method: String = "GET", body: String? = null): Request =
            Request.Builder()
                .url("http://photovault.invalid$path")
                .method(method, body?.toRequestBody())
                .build()

        fun execute(request: Request): Response = client.newCall(request).execute()

        fun ambientRoute(): Route = resolver.ambient.routeForRequest().first
    }

    // ---- AC-11 ----

    @Test
    fun `AC-11 refused LAN is tried 4 times then public once, later requests go straight to public`() {
        val public = server().apply {
            enqueue(MockResponse().setBody("first"))
            enqueue(MockResponse().setBody("second"))
        }
        val lanPort = closedPort()
        val env = Env(public.address, "http://127.0.0.1:$lanPort")
        assertEquals(Route.LAN, env.ambientRoute())

        env.execute(env.request()).use {
            assertEquals(200, it.code)
            assertEquals("first", it.body!!.string())
        }
        assertEquals(List(4) { "127.0.0.1:$lanPort" } + public.hostPort, env.counting.hostPorts)
        assertEquals(listOf(1_000L, 2_000L, 2_000L), env.recordingSleeper.sleeps)
        assertEquals(1, public.requestCount)
        assertEquals(ProbeOutcome.Failed(LockReason.EXHAUSTED), env.resolver.probeCacheForTest()?.outcome)
        assertEquals(Route.PUBLIC, env.ambientRoute())

        env.counting.attempts.clear()
        env.execute(env.request()).use { assertEquals("second", it.body!!.string()) }
        assertEquals(listOf(public.hostPort), env.counting.hostPorts)
        assertEquals("only the initial evaluation probed", 1, env.probe.calls)
    }

    @Test
    fun `session failures from concurrent requests switch early without sleeping`() {
        val public = server().apply { enqueue(MockResponse().setBody("ok")) }
        val lanPort = closedPort()
        val env = Env(public.address, "http://127.0.0.1:$lanPort")
        repeat(3) { env.resolver.ambient.onLanFailure() }

        env.execute(env.request()).use { assertEquals(200, it.code) }
        assertEquals(listOf("127.0.0.1:$lanPort", public.hostPort), env.counting.hostPorts)
        assertTrue(env.recordingSleeper.sleeps.isEmpty())
        assertEquals(ProbeOutcome.Failed(LockReason.CONSECUTIVE), env.resolver.probeCacheForTest()?.outcome)
    }

    // ---- AC-12 ----

    @Test
    fun `AC-12 chunk read timeout on LAN is replayed on public`() {
        val public = server().apply { enqueue(MockResponse().setBody("{}")) }
        val lan = server().apply { repeat(4) { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) } }
        val env = Env(public.address, lan.address, readTimeoutMs = 300)

        env.execute(env.request("/api/v1/backup/chunk", "POST", "chunk-bytes")).use { assertEquals(200, it.code) }
        assertEquals(4, lan.requestCount)
        assertEquals(1, public.requestCount)
        val replayed = public.takeRequest()
        assertEquals("/api/v1/backup/chunk", replayed.path)
        assertEquals("chunk-bytes", replayed.body.readUtf8())
        assertTrue(env.counting.attempts.take(4).all { it.attemptState?.dispatched == true })
    }

    @Test
    fun `AC-12 dispatched init and complete are not replayed`() {
        for (path in listOf("/api/v1/backup/init", "/api/v1/backup/complete")) {
            val public = server()
            val lan = server().apply { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val env = Env(public.address, lan.address, readTimeoutMs = 300)

            assertThrows(path, SocketTimeoutException::class.java) {
                env.execute(env.request(path, "POST", "{}"))
            }
            assertEquals(path, 1, lan.requestCount)
            assertEquals(path, 0, public.requestCount)
            assertEquals(path, 1, env.resolver.ambient.lanFailures.get())
            assertTrue(path, env.recordingSleeper.sleeps.isEmpty())
            assertEquals(path, Route.LAN, env.ambientRoute())
        }
    }

    @Test
    fun `AC-12 three consecutive non-replayable failures switch the session to public`() {
        val public = server().apply { enqueue(MockResponse().setBody("{}")) }
        val lan = server().apply { repeat(3) { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) } }
        val env = Env(public.address, lan.address, readTimeoutMs = 300)

        repeat(3) { i ->
            assertThrows(SocketTimeoutException::class.java) {
                env.execute(env.request("/api/v1/backup/init", "POST", "{}"))
            }
            if (i < 2) assertEquals(Route.LAN, env.ambientRoute())
        }
        assertEquals(ProbeOutcome.Failed(LockReason.CONSECUTIVE), env.resolver.probeCacheForTest()?.outcome)
        assertEquals(Route.PUBLIC, env.ambientRoute())

        env.execute(env.request("/api/v1/backup/init", "POST", "{}")).use { assertEquals(200, it.code) }
        assertEquals(3, lan.requestCount)
        assertEquals(1, public.requestCount)
    }

    @Test
    fun `undispatched init (connection refused) is retried and falls back to public`() {
        val public = server().apply { enqueue(MockResponse().setBody("{}")) }
        val lanPort = closedPort()
        val env = Env(public.address, "http://127.0.0.1:$lanPort")

        env.execute(env.request("/api/v1/backup/init", "POST", "{\"a\":1}")).use { assertEquals(200, it.code) }
        assertEquals(5, env.counting.attempts.size)
        assertEquals("{\"a\":1}", public.takeRequest().body.readUtf8())
    }

    // ---- AC-13 / AC-15 ----

    @Test
    fun `AC-13 HTTP 500 and 401 on LAN do not fall back`() {
        val public = server()
        val lan = server().apply {
            enqueue(MockResponse().setResponseCode(500))
            enqueue(MockResponse().setResponseCode(401))
        }
        val env = Env(public.address, lan.address)
        env.resolver.ambient.onLanFailure()

        env.execute(env.request()).use { assertEquals(500, it.code) }
        assertEquals("any LAN response resets the counter", 0, env.resolver.ambient.lanFailures.get())
        env.execute(env.request()).use { assertEquals(401, it.code) }
        assertEquals(Route.LAN, env.ambientRoute())
        assertEquals(0, public.requestCount)
        assertTrue(env.recordingSleeper.sleeps.isEmpty())
    }

    @Test
    fun `AC-15 public failure never falls back to LAN`() {
        val lan = server()
        val publicPort = closedPort()
        val env = Env("http://127.0.0.1:$publicPort", lan.address, initialNetwork = CELLULAR)
        assertEquals(Route.PUBLIC, env.ambientRoute())

        assertThrows(ConnectException::class.java) { env.execute(env.request()) }
        assertEquals(listOf("127.0.0.1:$publicPort"), env.counting.hostPorts)
        assertEquals(0, lan.requestCount)
        assertTrue(env.recordingSleeper.sleeps.isEmpty())
    }

    @Test
    fun `lan only gives up after 4 attempts without looping`() {
        val lanPort = closedPort()
        val env = Env(null, "http://127.0.0.1:$lanPort")
        repeat(2) {
            env.counting.attempts.clear()
            env.recordingSleeper.sleeps.clear()
            assertThrows(ConnectException::class.java) { env.execute(env.request()) }
            assertEquals(4, env.counting.attempts.size)
            assertEquals(listOf(1_000L, 2_000L, 2_000L), env.recordingSleeper.sleeps)
        }
        assertNull(env.resolver.probeCacheForTest())
        assertEquals(Route.LAN, env.ambientRoute())
    }

    // ---- AC-18 / tags ----

    @Test
    fun `AC-18 session snapshot tag is unchanged across the switch`() {
        val public = server().apply { enqueue(MockResponse().setBody("ok")) }
        val publicAddress = public.address
        val env = Env(publicAddress, "http://127.0.0.1:${closedPort()}")

        env.execute(env.request()).use { assertEquals(200, it.code) }
        assertEquals(5, env.counting.attempts.size)
        env.counting.attempts.forEach {
            assertSame(env.config.snapshot, it.snapshot)
            assertEquals(publicAddress, it.snapshot?.serverAddress)
            assertTrue(it.snapshot!!.isSameSession(env.config.snapshot))
        }
    }

    @Test
    fun `untagged requests get snapshot and ambient session tags, explicit tags are kept`() {
        val lan = server().apply { repeat(3) { enqueue(MockResponse().setBody("ok")) } }
        val env = Env(server().address, lan.address)

        env.execute(env.request()).close()
        val untagged = env.counting.attempts.single()
        assertNotNull(untagged.snapshot)
        assertSame(env.resolver.ambient, untagged.session)
        assertNotNull(untagged.attemptState)

        val task = runBlocking { env.resolver.beginTask() }
        env.execute(env.request().newBuilder().tag(RouteSession::class.java, task).build()).close()
        assertSame(task, env.counting.attempts[1].session)

        val custom: CredentialSessionSnapshot = env.config.snapshot.copy(generation = 99L)
        env.execute(env.request().newBuilder().tag(CredentialSessionSnapshot::class.java, custom).build()).close()
        assertSame(custom, env.counting.attempts[2].snapshot)
        assertEquals(List(3) { lan.hostPort }, env.counting.hostPorts)
    }

    // ---- body-phase failures / cancellation ----

    @Test
    fun `failure while reading the response body is not retried`() {
        val lan = server().apply {
            enqueue(
                MockResponse()
                    .setBody(Buffer().write(ByteArray(256 * 1024)))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )
        }
        val env = Env(server().address, lan.address)

        val response = env.execute(env.request())
        assertEquals(200, response.code)
        assertThrows(IOException::class.java) { response.use { it.body!!.bytes() } }
        assertEquals(1, lan.requestCount)
        assertEquals(1, env.counting.attempts.size)
        assertEquals(0, env.resolver.ambient.lanFailures.get())
        assertEquals(Route.LAN, env.ambientRoute())
    }

    @Test
    fun `cancelling the call stops the retry sleep`() {
        val lanPort = closedPort()
        val env = Env(server().address, "http://127.0.0.1:$lanPort", sleeper = Sleeper.Default)
        val call = env.client.newCall(env.request())
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                call.execute().close()
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        worker.start()
        Thread.sleep(300)
        val cancelledAt = System.nanoTime()
        call.cancel()
        worker.join(5_000)
        val elapsedMs = (System.nanoTime() - cancelledAt) / 1_000_000
        assertTrue("worker still running", !worker.isAlive)
        assertTrue("got ${failure.get()}", failure.get() is IOException)
        assertTrue("took $elapsedMs ms", elapsedMs < 1_000)
        assertEquals(1, env.counting.attempts.size)
    }

    // ---- timeouts via a recording chain ----

    private class RecordingChain(
        private val request: Request,
        private val defaults: Timeouts,
        private val responder: (Request) -> Response
    ) : Interceptor.Chain {
        val timeoutCalls = mutableListOf<String>()
        val proceeded = mutableListOf<Request>()
        var defaultReads = 0
        private val call: Call = OkHttpClient().newCall(request)

        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            proceeded += request
            return responder(request)
        }
        override fun connection() = null
        override fun call(): Call = call
        override fun connectTimeoutMillis(): Int = defaults.connectMs.also { defaultReads++ }
        override fun readTimeoutMillis(): Int = defaults.readMs.also { defaultReads++ }
        override fun writeTimeoutMillis(): Int = defaults.writeMs.also { defaultReads++ }
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = apply {
            timeoutCalls += "connect=${unit.toMillis(timeout.toLong())}"
        }
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = apply {
            timeoutCalls += "read=${unit.toMillis(timeout.toLong())}"
        }
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = apply {
            timeoutCalls += "write=${unit.toMillis(timeout.toLong())}"
        }
    }

    private fun okResponse(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("".toResponseBody())
        .build()

    private fun placeholderRequest() = Request.Builder().url("http://photovault.invalid/api/v1/files/list").build()

    @Test
    fun `public only requests keep client timeouts and never probe`() {
        val config = FakeConfigSource("https://pub.example.com", null)
        val probe = FakeLanProbe()
        val resolver = EndpointResolver(config, FakeNetworkMonitor(), probe, FakeClock(), scope)
        resolver.start()
        val interceptor = EndpointRoutingInterceptor(resolver, config, RecordingSleeper())

        repeat(3) {
            val chain = RecordingChain(placeholderRequest(), Timeouts(15_000, 30_000, 30_000), ::okResponse)
            interceptor.intercept(chain).close()
            assertTrue(chain.timeoutCalls.isEmpty())
            assertEquals("pub.example.com", chain.proceeded.single().url.host)
            assertEquals("https", chain.proceeded.single().url.scheme)
        }
        assertEquals(0, probe.calls)
    }

    @Test
    fun `client defaults are read once and LAN attempts are capped by them`() {
        val config = FakeConfigSource("https://pub.example.com", "http://192.168.1.10:8000")
        val resolver = EndpointResolver(config, FakeNetworkMonitor(), FakeLanProbe(), FakeClock(), scope)
        resolver.start()
        runBlocking { resolver.awaitAmbientEvaluation() }
        val sleeper = RecordingSleeper()
        val interceptor = EndpointRoutingInterceptor(resolver, config, sleeper)

        val chain = RecordingChain(placeholderRequest(), Timeouts(5_000, 5_000, 30_000)) { request ->
            if (request.url.host == "192.168.1.10") throw ConnectException("refused") else okResponse(request)
        }
        interceptor.intercept(chain).close()

        assertEquals(3, chain.defaultReads)
        assertEquals(5, chain.proceeded.size)
        val lanAttempt = listOf("connect=3000", "read=5000", "write=10000")
        assertEquals(List(4) { lanAttempt }.flatten(), chain.timeoutCalls)
        assertEquals(listOf(1_000L, 2_000L, 2_000L), sleeper.sleeps)
    }

    @Test
    fun `timeout override applies on public`() {
        val config = FakeConfigSource("https://pub.example.com", null)
        val resolver = EndpointResolver(config, FakeNetworkMonitor(), FakeLanProbe(), FakeClock(), scope)
        val interceptor = EndpointRoutingInterceptor(resolver, config, RecordingSleeper())
        val request = placeholderRequest().newBuilder()
            .tag(TimeoutOverride::class.java, TimeoutOverride(10_000, 10_000, 10_000))
            .build()
        val chain = RecordingChain(request, Timeouts(15_000, 30_000, 30_000), ::okResponse)
        interceptor.intercept(chain).close()
        assertEquals(listOf("connect=10000", "read=10000", "write=10000"), chain.timeoutCalls)
    }
}
