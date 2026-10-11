package com.huoyi.photovault.data.network.endpoint

import com.huoyi.photovault.data.local.CredentialSessionSnapshot
import com.huoyi.photovault.data.local.StableAccountIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Shared test doubles for the dual-endpoint routing core (and later wiring tests). */

internal const val TEST_INSTANCE_ID = "00000000-0000-0000-0000-000000000000"
internal const val TEST_FINGERPRINT = "0aa7ca809fe5773d5f9134544860ff3c"

internal val WIFI_1 = NetworkKey(100L, NetworkClass.LOCAL_CAPABLE)
internal val WIFI_2 = NetworkKey(101L, NetworkClass.LOCAL_CAPABLE)
internal val CELLULAR = NetworkKey(200L, NetworkClass.OTHER)

internal fun baseUrl(address: String?): HttpUrl? = address?.let { ServerAddress.legacyNormalize(it).toHttpUrl() }

/** A port that refuses connections (bound, then closed). */
internal fun closedPort(): Int = ServerSocket(0).use { it.localPort }

/** Mutable [EndpointConfigSource]; every [update] bumps the version and emits on [changes]. */
internal class FakeConfigSource(
    publicAddress: String?,
    lanAddress: String?,
    instanceId: String? = TEST_INSTANCE_ID
) : EndpointConfigSource {

    @Volatile
    private var config = EndpointConfig(baseUrl(publicAddress), baseUrl(lanAddress), instanceId, 1L)

    private val _changes = MutableStateFlow(1L)
    override val changes: StateFlow<Long> = _changes

    @Volatile
    var snapshot = CredentialSessionSnapshot(
        serverAddress = publicAddress,
        accessToken = "access",
        refreshToken = "refresh",
        accessTokenExpiresAt = Long.MAX_VALUE,
        identity = StableAccountIdentity(TEST_INSTANCE_ID, 1L),
        generation = 1L
    )

    override fun current(): EndpointConfig = config

    override fun sessionSnapshot(): CredentialSessionSnapshot = snapshot

    fun update(publicAddress: String?, lanAddress: String?, instanceId: String? = config.instanceId) {
        val version = config.version + 1
        config = EndpointConfig(baseUrl(publicAddress), baseUrl(lanAddress), instanceId, version)
        _changes.value = version
    }
}

/**
 * [NetworkMonitor] backed by a real [NetworkKeyTracker]. Seeds [initial] on [start];
 * [deliver] simulates a callback. In [degraded] mode [current] polls [polledKey].
 */
internal class FakeNetworkMonitor(private val initial: NetworkKey = WIFI_1) : NetworkMonitor {
    val tracker = NetworkKeyTracker()
    private var onChange: (() -> Unit)? = null
    var startCount = 0
        private set

    @Volatile
    var degraded = false

    @Volatile
    var polledKey: NetworkKey? = null

    override fun current(): NetworkClass {
        if (degraded) polledKey?.let { deliver(it) }
        return tracker.current.networkClass
    }

    override fun start(onChange: () -> Unit) {
        startCount++
        this.onChange = onChange
        tracker.seed(initial)
    }

    fun deliver(key: NetworkKey) {
        if (tracker.offer(key)) onChange?.invoke()
    }
}

/** Counting [LanProbe] with a configurable result and (virtual-time) delay. */
internal class FakeLanProbe(
    @Volatile var outcome: ProbeOutcome = ProbeOutcome.Success,
    @Volatile var delayMs: Long = 0L
) : LanProbe {
    data class Invocation(val lanBase: HttpUrl, val expectedFingerprint: String?, val skipIdentity: Boolean)

    private val counter = AtomicInteger()
    val invocations: MutableList<Invocation> = Collections.synchronizedList(mutableListOf())
    val calls: Int get() = counter.get()

    override suspend fun probe(lanBase: HttpUrl, expectedFingerprint: String?, skipIdentity: Boolean): ProbeOutcome {
        counter.incrementAndGet()
        invocations += Invocation(lanBase, expectedFingerprint, skipIdentity)
        if (delayMs > 0) delay(delayMs)
        return outcome
    }
}

internal class FakeClock(@Volatile var now: Long = 1_000_000L) : Clock {
    override fun nowMillis(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}

/** [Sleeper] that records requested delays without sleeping. */
internal class RecordingSleeper : Sleeper {
    val sleeps: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    override fun sleep(ms: Long, call: Call) {
        sleeps += ms
    }
}

/** Application interceptor placed after the routing layer; records every forwarded attempt. */
internal class CountingInterceptor : Interceptor {
    data class Attempt(
        val hostPort: String,
        val path: String,
        val session: RouteSession?,
        val snapshot: CredentialSessionSnapshot?,
        val attemptState: AttemptState?,
        val timeoutOverride: TimeoutOverride?
    )

    val attempts: MutableList<Attempt> = Collections.synchronizedList(mutableListOf())
    val hostPorts: List<String> get() = synchronized(attempts) { attempts.map { it.hostPort } }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        attempts += Attempt(
            hostPort = "${request.url.host}:${request.url.port}",
            path = request.url.encodedPath,
            session = request.tag(RouteSession::class.java),
            snapshot = request.tag(CredentialSessionSnapshot::class.java),
            attemptState = request.tag(AttemptState::class.java),
            timeoutOverride = request.tag(TimeoutOverride::class.java)
        )
        return chain.proceed(request)
    }
}

internal fun HttpUrl.hostPort(): String = "$host:$port"
