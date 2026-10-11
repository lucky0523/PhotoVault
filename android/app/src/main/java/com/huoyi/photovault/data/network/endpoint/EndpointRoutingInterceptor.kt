package com.huoyi.photovault.data.network.endpoint

import com.huoyi.photovault.data.local.CredentialSessionSnapshot
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** No address is configured for the route a session needs. An [IOException], so callers treat it as a retryable failure. */
class NoEndpointConfiguredException : IOException("No server address configured")

/**
 * Routing execution layer (design §4.4). First application interceptor on every
 * PhotoVault client:
 * - rewrites scheme/host/port to the session's current base URL;
 * - tags each forwarded request with [CredentialSessionSnapshot] (former
 *   BaseUrlInterceptor duty), the [RouteSession] actually used and a fresh [AttemptState];
 * - on LAN connection-level failures retries after 1 s / 2 s / 2 s, then (or once the
 *   session has 4 consecutive failures) marks the LAN unavailable and retries once on
 *   the public address (CR-3). Non-replayable requests that may have been sent are
 *   never replayed. Public failures are never retried on the LAN (FR-3.5).
 *
 * Only failures thrown by `chain.proceed()` are handled; errors while the caller reads
 * the response body are passed through unchanged.
 */
class EndpointRoutingInterceptor(
    private val resolver: EndpointResolver,
    private val config: EndpointConfigSource,
    private val sleeper: Sleeper = Sleeper.Default
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Client configuration, read once: retries must not see the per-attempt overrides.
        val clientDefaults = Timeouts(chain.connectTimeoutMillis(), chain.readTimeoutMillis(), chain.writeTimeoutMillis())
        val session = original.tag(RouteSession::class.java) ?: resolver.ambient
        val snapshot = original.tag(CredentialSessionSnapshot::class.java) ?: config.sessionSnapshot()

        var (route, base) = session.routeForRequest()
        var lanRetries = 0
        while (true) {
            val attempt = AttemptState()
            val request = original.newBuilder()
                .url(rewrite(original.url, base))
                .tag(CredentialSessionSnapshot::class.java, snapshot)
                .tag(RouteSession::class.java, session)
                .tag(AttemptState::class.java, attempt)
                .build()
            val response = try {
                chainFor(chain, timeoutsFor(route, original, clientDefaults)).proceed(request)
            } catch (e: IOException) {
                if (chain.call().isCanceled() || !ConnectionErrorClassifier.isConnectionLevel(e)) throw e
                if (route == Route.PUBLIC) throw e
                val failures = session.onLanFailure()
                val replayOk = !attempt.dispatched || Replayability.isReplayable(original)
                if (!replayOk) {
                    // Possibly delivered (init/complete…): hand back to the caller.
                    if (failures >= CONSECUTIVE_THRESHOLD && config.current().publicBase != null) {
                        session.markLanUnavailable(LockReason.CONSECUTIVE)
                    }
                    throw e
                }
                val publicBase = config.current().publicBase
                if (lanRetries >= LAN_BACKOFF_MS.size) {
                    if (publicBase == null) throw e // LAN-only: nothing to fall back to
                    session.markLanUnavailable(LockReason.EXHAUSTED)
                    route = Route.PUBLIC
                    base = publicBase
                    continue
                }
                if (failures >= LAN_ATTEMPTS && publicBase != null) {
                    // Concurrent requests in this session already failed enough: switch now.
                    session.markLanUnavailable(LockReason.CONSECUTIVE)
                    route = Route.PUBLIC
                    base = publicBase
                    continue
                }
                sleeper.sleep(LAN_BACKOFF_MS[lanRetries], chain.call())
                lanRetries++
                if (chain.call().isCanceled()) throw IOException("Canceled")
                // Another request may have switched the session meanwhile.
                val next = session.routeForRequest()
                route = next.first
                base = next.second
                continue
            }
            session.onHttpResponse(route)
            return response // 4xx/5xx are returned as-is (FR-3.3)
        }
    }

    private fun chainFor(chain: Interceptor.Chain, timeouts: Timeouts?): Interceptor.Chain {
        if (timeouts == null) return chain
        return chain
            .withConnectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
            .withReadTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
            .withWriteTimeout(timeouts.writeMs, TimeUnit.MILLISECONDS)
    }

    private fun rewrite(url: HttpUrl, base: HttpUrl): HttpUrl = url.newBuilder()
        .scheme(base.scheme)
        .host(base.host)
        .port(base.port)
        .build()

    companion object {
        /** Retry delays on the LAN; 3 retries = 4 LAN attempts per request (CR-3). */
        internal val LAN_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 2_000L)
        internal const val LAN_ATTEMPTS = 4
        internal const val CONSECUTIVE_THRESHOLD = 3
    }
}

internal const val COMPLETE_PATH = "/api/v1/backup/complete"

/**
 * Per-attempt timeouts (design §4.4 table). LAN: 3/30/30 s for `complete`, 3/10/10 s
 * otherwise, each capped by the client's own value (0 = unlimited, use the table).
 * PUBLIC: the [TimeoutOverride] tag if present, else null (keep the client's values).
 */
internal fun timeoutsFor(route: Route, request: Request, clientDefaults: Timeouts): Timeouts? {
    val override = request.tag(TimeoutOverride::class.java)
    return when (route) {
        Route.PUBLIC -> override?.let { Timeouts(it.connectMs, it.readMs, it.writeMs) }
        Route.LAN -> {
            val isComplete = request.method == "POST" && request.url.encodedPath == COMPLETE_PATH
            val table = if (isComplete) Timeouts(3_000, 30_000, 30_000) else Timeouts(3_000, 10_000, 10_000)
            Timeouts(
                capped(table.connectMs, clientDefaults.connectMs),
                capped(table.readMs, clientDefaults.readMs),
                capped(table.writeMs, clientDefaults.writeMs)
            )
        }
    }
}

private fun capped(tableMs: Int, clientMs: Int): Int = if (clientMs == 0) tableMs else minOf(tableMs, clientMs)

/** Whether a failure means "this endpoint is not usable" (design §4.4). Any exception in the cause chain counts. */
object ConnectionErrorClassifier {
    fun isConnectionLevel(t: Throwable?): Boolean {
        var current = t
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (matches(current)) return true
            current = current.cause
        }
        return false
    }

    private fun matches(t: Throwable): Boolean = when (t) {
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException,
        is PortUnreachableException,
        is SocketTimeoutException,
        is SocketException,
        is SSLException,
        is EOFException -> true
        is InterruptedIOException -> t.message == "timeout"
        is IOException -> t.message?.startsWith("unexpected end of stream") == true
        else -> false
    }
}

/** Requests that are safe to send again after they may have reached the server (design §2, §4.4). */
object Replayability {
    // Chunks overwrite the same index file; refresh has no token rotation (design §2).
    // GET /api/v1/connection/test is covered by the GET rule.
    private val REPLAYABLE_POSTS = setOf("/api/v1/backup/chunk", "/api/v1/auth/refresh")

    fun isReplayable(request: Request): Boolean {
        if (request.body?.isOneShot() == true) return false
        return when (request.method) {
            "GET", "HEAD" -> true
            "POST" -> request.url.encodedPath in REPLAYABLE_POSTS
            else -> false
        }
    }
}

/** Per-attempt marker set by [DispatchMarkerInterceptor] once a connection exists. */
class AttemptState {
    @Volatile
    var dispatched: Boolean = false
}

/**
 * Network interceptor: runs only after a connection has been established, so a failure
 * with `dispatched == false` proves the request was never sent.
 */
object DispatchMarkerInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        chain.request().tag(AttemptState::class.java)?.dispatched = true
        return chain.proceed(chain.request())
    }
}

/** Retry delay, injectable for tests. Implementations must stop early when [call] is canceled. */
fun interface Sleeper {
    fun sleep(ms: Long, call: Call)

    companion object {
        private const val SLICE_MS = 100L

        /** Sleeps in 100 ms slices and throws `IOException("Canceled")` once [call] is canceled. */
        val Default: Sleeper = Sleeper { ms, call ->
            var remaining = ms
            while (remaining > 0) {
                if (call.isCanceled()) throw IOException("Canceled")
                val slice = minOf(SLICE_MS, remaining)
                try {
                    Thread.sleep(slice)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("interrupted")
                }
                remaining -= slice
            }
            if (call.isCanceled()) throw IOException("Canceled")
        }
    }
}

/**
 * First interceptor of the backup-task client: tags requests with the active task
 * session unless the caller already chose a session.
 */
class BackupTaskTagInterceptor(private val resolver: EndpointResolver) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val task = resolver.activeTask
        if (task == null || request.tag(RouteSession::class.java) != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().tag(RouteSession::class.java, task).build())
    }
}
