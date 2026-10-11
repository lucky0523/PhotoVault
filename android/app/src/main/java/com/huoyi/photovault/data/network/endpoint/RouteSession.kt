package com.huoyi.photovault.data.network.endpoint

import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicInteger

/**
 * A routing context (design §4.3). [Ambient] is shared by browsing, thumbnails,
 * heartbeat and pre-checks; its route is a pure function of the shared probe cache.
 * Each backup run owns a [Task] whose route is decided once at start and can only
 * be locked from LAN to PUBLIC afterwards (CR-4).
 *
 * All state transitions go through [EndpointResolver] under its lock.
 */
sealed class RouteSession(internal val resolver: EndpointResolver) {

    /** Consecutive LAN connection-level failures in this session (reset by any LAN HTTP response). */
    internal val lanFailures = AtomicInteger(0)

    /**
     * Route and base URL for the next attempt, using the *current* configuration.
     * @throws NoEndpointConfiguredException when the needed address is not configured.
     */
    internal abstract fun routeForRequest(): Pair<Route, HttpUrl>

    /** Any HTTP response (including 4xx/5xx) was received on [route]. */
    internal fun onHttpResponse(route: Route) {
        if (route == Route.LAN) resolver.onLanHttpResponse(this)
    }

    /** Records one LAN connection-level failure; returns the new consecutive count. */
    internal fun onLanFailure(): Int = lanFailures.incrementAndGet()

    /** LAN is unusable for this session: writes a `Failed` cache entry; a Task is also locked to PUBLIC. */
    internal fun markLanUnavailable(reason: LockReason) = resolver.markLanUnavailable(this, reason)

    class Ambient internal constructor(resolver: EndpointResolver) : RouteSession(resolver) {
        override fun routeForRequest(): Pair<Route, HttpUrl> = resolver.ambientRouteForRequest()

        override fun toString(): String = "Ambient"
    }

    class Task internal constructor(
        val id: Long,
        resolver: EndpointResolver,
        val startNetworkGen: Long,
        val startConfigVersion: Long
    ) : RouteSession(resolver) {

        /** Current route. PUBLIC while the start-of-task evaluation is still running. */
        @Volatile
        var route: Route = Route.PUBLIC
            internal set

        /** Why the task is on PUBLIC (null when on LAN or when only the public address is configured). */
        @Volatile
        var lockedReason: LockReason? = null
            internal set

        /** Incremented every time this task is locked from LAN to PUBLIC (starting on PUBLIC does not count). */
        @Volatile
        var lockGeneration: Int = 0
            internal set

        /** False until [EndpointResolver.beginTask] has decided the initial route. */
        @Volatile
        internal var evaluated: Boolean = false

        override fun routeForRequest(): Pair<Route, HttpUrl> = resolver.taskRouteForRequest(this)

        override fun toString(): String = "Task#$id"
    }
}
