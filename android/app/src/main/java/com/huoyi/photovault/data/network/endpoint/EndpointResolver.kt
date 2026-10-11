package com.huoyi.photovault.data.network.endpoint

import android.util.Log
import com.huoyi.photovault.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which configured address (LAN or public) each request uses (design §4.3).
 *
 * - One probe cache entry keyed by (networkGeneration, configVersion) is the single
 *   source of truth; it is valid for 60 s (CR-2) and only within the same generation.
 * - Probes are single-flight per generation and run on [scope], so a cancelled caller
 *   never cancels a probe other callers are waiting for.
 * - [ambient] routes as a pure function of the cache and never blocks on a probe.
 * - Each backup run gets a [RouteSession.Task] via [beginTask]: evaluated once at
 *   start, afterwards it can only be locked from LAN to PUBLIC (CR-4).
 *
 * All mutable state is guarded by [lock]; no IO happens while holding it.
 */
@Singleton
class EndpointResolver @Inject constructor(
    private val config: EndpointConfigSource,
    private val network: NetworkMonitor,
    private val probe: LanProbe,
    private val clock: Clock,
    @EndpointScope private val scope: CoroutineScope
) {
    private val lock = Any()

    /** Shared session for every request that is not part of a backup task. */
    val ambient: RouteSession.Ambient = RouteSession.Ambient(this)

    // ---- state guarded by [lock] ----
    private var probeCache: ProbeCacheEntry? = null
    private var networkGeneration = 0L
    private var inFlight: InFlightProbe? = null
    private val liveTasks = LinkedHashSet<RouteSession.Task>()
    private var handledConfigVersion: Long? = null
    private var nextTaskId = 1L
    private var started = false

    /** Most recently started task; used only for tagging backup requests and [displayRoute]. */
    @Volatile
    var activeTask: RouteSession.Task? = null
        private set

    private val _displayRoute = MutableStateFlow<Route?>(null)

    /** Route shown by the connection indicator: the active task's route, else the ambient route. */
    val displayRoute: StateFlow<Route?> = _displayRoute.asStateFlow()

    @Volatile
    private var cachedImageBaseUrl: String? = null

    private class InFlightProbe(
        val deferred: CompletableDeferred<ProbeOutcome>,
        val networkGeneration: Long,
        val configVersion: Long
    ) {
        /** Set by markLanUnavailable: the result must not overwrite the newer `Failed` entry. */
        var superseded = false
    }

    /** Registers the network callback, follows config changes and kicks the first ambient evaluation. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        network.start(::onNetworkChanged)
        // onConfigChanged is idempotent per config version, so the initial StateFlow
        // value is harmless and a change racing with start() cannot be missed.
        scope.launch { config.changes.collect { onConfigChanged() } }
        val cfg = config.current()
        synchronized(lock) {
            syncConfigLocked(cfg)
            cachedImageBaseUrl = ServerAddress.imageBaseUrlFor(cfg.instanceId)
            recomputeDisplayRouteLocked(cfg)
            kickIfUsefulLocked(cfg)
        }
    }

    /**
     * Starts a backup task session. Waits for at most one shared probe (≤ 1.5 s) when
     * the cache has no fresh same-generation result. Never throws except
     * [CancellationException]; any failure simply locks the task to PUBLIC.
     */
    suspend fun beginTask(): RouteSession.Task {
        val cfg = config.current()
        var pending: CompletableDeferred<ProbeOutcome>? = null
        val task = synchronized(lock) {
            syncConfigLocked(cfg)
            val networkClass = network.current()
            val created = RouteSession.Task(nextTaskId++, this, networkGeneration, cfg.version)
            liveTasks += created
            activeTask = created
            when {
                !cfg.hasAnyAddress || cfg.isPublicOnly -> settleLocked(created, Route.PUBLIC, null)
                cfg.isLanOnly -> settleLocked(created, Route.LAN, null)
                networkClass != NetworkClass.LOCAL_CAPABLE ->
                    settleLocked(created, Route.PUBLIC, LockReason.NOT_LOCAL_NETWORK)
                isFreshLocked(cfg) -> settleFromOutcomeLocked(created, probeCache!!.outcome)
                else -> pending = ensureProbeLocked(cfg)
            }
            recomputeDisplayRouteLocked(cfg)
            created
        }
        val deferred = pending
        if (deferred != null) {
            val outcome = try {
                deferred.await()
            } catch (e: CancellationException) {
                endTask(task)
                throw e
            } catch (e: Exception) {
                ProbeOutcome.ConnectFailed(e.javaClass.simpleName)
            }
            finalizeTask(task, outcome)
        }
        FileLogger.log("Endpoint", "$task started on ${task.route}${task.lockedReason?.let { " ($it)" } ?: ""}")
        return task
    }

    /** Ends [task]; only clears [activeTask] if it is still this task (a newer task may have started). */
    fun endTask(task: RouteSession.Task) {
        val cfg = config.current()
        synchronized(lock) {
            liveTasks.remove(task)
            if (activeTask === task) activeTask = null
            recomputeDisplayRouteLocked(cfg)
        }
    }

    /**
     * Pre-check before a backup run (design §4.3): with both addresses on a
     * LOCAL_CAPABLE network and no fresh same-generation cache, waits for the shared
     * probe (≤ 1.5 s). Returns immediately otherwise. Throws only [CancellationException].
     */
    suspend fun awaitAmbientEvaluation() {
        val cfg = config.current()
        val deferred = synchronized(lock) {
            syncConfigLocked(cfg)
            if (!cfg.isDual) return
            if (network.current() != NetworkClass.LOCAL_CAPABLE) return
            if (isFreshLocked(cfg)) return
            ensureProbeLocked(cfg)
        }
        deferred.await()
    }

    /** Ambient base URL without trailing `/` (for ExoPlayer and other direct URL users); null when unconfigured. */
    fun ambientBaseUrlString(): String? = try {
        ambientRouteForRequest().second.toString().removeSuffix("/")
    } catch (e: NoEndpointConfiguredException) {
        null
    }

    /** Coil placeholder prefix `http://<fp16>.photovault.invalid` (rewritten by the routing interceptor). */
    fun imageBaseUrl(): String =
        cachedImageBaseUrl ?: ServerAddress.imageBaseUrlFor(config.current().instanceId).also {
            cachedImageBaseUrl = it
        }

    /**
     * Chooses the login target (design §5.4 step 4): the only filled address, or — with
     * both filled on a LOCAL_CAPABLE network — the LAN address when an identity-less
     * probe succeeds, else the public address. Inputs are raw field values.
     */
    suspend fun selectLoginTarget(publicRaw: String?, lanRaw: String?): LoginTarget {
        val public = loginAddress(publicRaw)
        val lan = loginAddress(lanRaw)
        require(public != null || lan != null) { "No server address provided" }
        if (lan == null) return LoginTarget(public!!, Route.PUBLIC)
        if (public == null) return LoginTarget(lan, Route.LAN)
        if (network.current() != NetworkClass.LOCAL_CAPABLE) return LoginTarget(public, Route.PUBLIC)
        val lanUrl = ServerAddress.legacyNormalize(lan).toHttpUrlOrNull()
            ?: return LoginTarget(public, Route.PUBLIC)
        val outcome = try {
            probe.probe(lanUrl, expectedFingerprint = null, skipIdentity = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeOutcome.ConnectFailed(e.javaClass.simpleName)
        }
        FileLogger.log("Endpoint", "login target: ${if (outcome == ProbeOutcome.Success) "LAN" else "PUBLIC ($outcome)"}")
        return if (outcome == ProbeOutcome.Success) LoginTarget(lan, Route.LAN) else LoginTarget(public, Route.PUBLIC)
    }

    // ---------------------------------------------------------------------
    // Callbacks
    // ---------------------------------------------------------------------

    /** Default network changed: new generation, lock LAN tasks, re-probe if useful. */
    internal fun onNetworkChanged() {
        val cfg = config.current()
        synchronized(lock) {
            networkGeneration++
            if (!cfg.isLanOnly) {
                liveTasks.filter { it.evaluated && it.route == Route.LAN }
                    .forEach { lockTaskLocked(it, LockReason.NETWORK_CHANGED) }
            }
            Log.i(TAG, "network changed, generation=$networkGeneration")
            recomputeDisplayRouteLocked(cfg)
            kickIfUsefulLocked(cfg)
        }
    }

    /** Endpoint configuration changed (saveSession / clearCredentials / setLanAddress). Idempotent per version. */
    internal fun onConfigChanged() {
        val cfg = config.current()
        synchronized(lock) { syncConfigLocked(cfg) }
    }

    // ---------------------------------------------------------------------
    // Session hooks (called by RouteSession)
    // ---------------------------------------------------------------------

    internal fun ambientRouteForRequest(): Pair<Route, HttpUrl> {
        val cfg = config.current()
        val route = synchronized(lock) {
            syncConfigLocked(cfg)
            ambientRouteLocked(cfg, kick = true)
        } ?: throw NoEndpointConfiguredException()
        return route to baseFor(route, cfg)
    }

    internal fun taskRouteForRequest(task: RouteSession.Task): Pair<Route, HttpUrl> {
        val cfg = config.current()
        val route = synchronized(lock) {
            syncConfigLocked(cfg)
            task.route
        }
        return route to baseFor(route, cfg)
    }

    internal fun onLanHttpResponse(session: RouteSession) {
        session.lanFailures.set(0)
        if (session !is RouteSession.Ambient) return
        val cfg = config.current()
        synchronized(lock) {
            val entry = probeCache
            if (entry != null && isSameGenerationLocked(entry, cfg) && entry.outcome == ProbeOutcome.Success) {
                probeCache = entry.copy(atMillis = clock.nowMillis())
            }
        }
    }

    internal fun markLanUnavailable(session: RouteSession, reason: LockReason) {
        val cfg = config.current()
        synchronized(lock) {
            probeCache = ProbeCacheEntry(ProbeOutcome.Failed(reason), clock.nowMillis(), networkGeneration, cfg.version)
            inFlight?.let {
                it.superseded = true
                inFlight = null
            }
            if (session is RouteSession.Task && session.route == Route.LAN && !cfg.isLanOnly) {
                lockTaskLocked(session, reason)
            }
            FileLogger.log("Endpoint", "LAN marked unavailable by $session: $reason")
            recomputeDisplayRouteLocked(cfg)
        }
    }

    // ---------------------------------------------------------------------
    // Internals (all *Locked functions require [lock])
    // ---------------------------------------------------------------------

    private fun finalizeTask(task: RouteSession.Task, outcome: ProbeOutcome) {
        val cfg = config.current()
        synchronized(lock) {
            if (task !in liveTasks) return
            syncConfigLocked(cfg)
            when {
                cfg.isLanOnly -> settleLocked(task, Route.LAN, null)
                !cfg.isDual -> settleLocked(task, Route.PUBLIC, null)
                networkGeneration != task.startNetworkGen ->
                    settleLocked(task, Route.PUBLIC, LockReason.NETWORK_CHANGED)
                cfg.version != task.startConfigVersion ->
                    settleLocked(task, Route.PUBLIC, LockReason.CONFIG_CHANGED)
                else -> {
                    // Prefer the committed cache (it may hold a newer `Failed` than this probe).
                    val entry = probeCache
                    val effective = if (entry != null && isSameGenerationLocked(entry, cfg)) entry.outcome else outcome
                    settleFromOutcomeLocked(task, effective)
                }
            }
            recomputeDisplayRouteLocked(cfg)
        }
    }

    private fun ambientRouteLocked(cfg: EndpointConfig, kick: Boolean): Route? {
        if (!cfg.hasAnyAddress) return null
        if (cfg.isPublicOnly) return Route.PUBLIC
        if (cfg.isLanOnly) return Route.LAN
        if (network.current() != NetworkClass.LOCAL_CAPABLE) return Route.PUBLIC
        val entry = probeCache
        if (entry == null || !isSameGenerationLocked(entry, cfg)) {
            if (kick) ensureProbeLocked(cfg)
            return Route.PUBLIC
        }
        if (kick && clock.nowMillis() - entry.atMillis >= CACHE_TTL_MS) {
            ensureProbeLocked(cfg) // stale-while-revalidate
        }
        return if (entry.outcome == ProbeOutcome.Success) Route.LAN else Route.PUBLIC
    }

    private fun isSameGenerationLocked(entry: ProbeCacheEntry, cfg: EndpointConfig): Boolean =
        entry.networkGeneration == networkGeneration && entry.configVersion == cfg.version

    private fun isFreshLocked(cfg: EndpointConfig): Boolean {
        val entry = probeCache ?: return false
        return isSameGenerationLocked(entry, cfg) && clock.nowMillis() - entry.atMillis < CACHE_TTL_MS
    }

    private fun kickIfUsefulLocked(cfg: EndpointConfig) {
        if (cfg.isDual && network.current() == NetworkClass.LOCAL_CAPABLE && !isFreshLocked(cfg)) {
            ensureProbeLocked(cfg)
        }
    }

    /** Joins the in-flight probe of the current generation or starts one on [scope]. Caller ensures [cfg] is dual. */
    private fun ensureProbeLocked(cfg: EndpointConfig): CompletableDeferred<ProbeOutcome> {
        val gen = networkGeneration
        inFlight?.let {
            if (it.networkGeneration == gen && it.configVersion == cfg.version) return it.deferred
        }
        val lanBase = cfg.lanBase ?: return CompletableDeferred(ProbeOutcome.SkippedNotLocalNetwork)
        val expectedFingerprint = cfg.instanceId?.takeIf { it.isNotBlank() }?.let(ServerAddress::instanceFingerprint)
        val handle = InFlightProbe(CompletableDeferred(), gen, cfg.version)
        inFlight = handle
        val job = scope.launch {
            val outcome = try {
                probe.probe(lanBase, expectedFingerprint, skipIdentity = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProbeOutcome.ConnectFailed(e.javaClass.simpleName)
            }
            commitProbe(handle, outcome)
            handle.deferred.complete(outcome)
        }
        // Scope cancelled before/while probing: never leave awaiters hanging.
        job.invokeOnCompletion { cause ->
            if (!handle.deferred.isCompleted) {
                synchronized(lock) { if (inFlight === handle) inFlight = null }
                handle.deferred.complete(ProbeOutcome.ConnectFailed(cause?.javaClass?.simpleName ?: "Cancelled"))
            }
        }
        return handle.deferred
    }

    private fun commitProbe(handle: InFlightProbe, outcome: ProbeOutcome) {
        val cfg = config.current()
        synchronized(lock) {
            if (inFlight === handle) inFlight = null
            if (handle.superseded) return
            if (handle.networkGeneration != networkGeneration || handle.configVersion != cfg.version) return
            probeCache = ProbeCacheEntry(outcome, clock.nowMillis(), handle.networkGeneration, handle.configVersion)
            if (outcome == ProbeOutcome.Success) ambient.lanFailures.set(0)
            FileLogger.log("Endpoint", "LAN probe result: $outcome")
            recomputeDisplayRouteLocked(cfg)
        }
    }

    /** Applies a config version change once: lock LAN tasks (unless LAN-only), refresh derived state, re-probe. */
    private fun syncConfigLocked(cfg: EndpointConfig) {
        val handled = handledConfigVersion
        if (handled == cfg.version) return
        handledConfigVersion = cfg.version
        if (handled == null) return // first observation, nothing to compare against
        cachedImageBaseUrl = ServerAddress.imageBaseUrlFor(cfg.instanceId)
        if (!cfg.isLanOnly) {
            liveTasks.filter { it.evaluated && it.route == Route.LAN }
                .forEach { lockTaskLocked(it, LockReason.CONFIG_CHANGED) }
        }
        recomputeDisplayRouteLocked(cfg)
        kickIfUsefulLocked(cfg)
    }

    private fun settleLocked(task: RouteSession.Task, route: Route, reason: LockReason?) {
        task.route = route
        task.lockedReason = reason
        task.evaluated = true
    }

    private fun settleFromOutcomeLocked(task: RouteSession.Task, outcome: ProbeOutcome) {
        if (outcome == ProbeOutcome.Success) {
            settleLocked(task, Route.LAN, null)
        } else {
            settleLocked(task, Route.PUBLIC, LockReason.PROBE_FAILED)
        }
    }

    private fun lockTaskLocked(task: RouteSession.Task, reason: LockReason) {
        task.route = Route.PUBLIC
        task.lockedReason = reason
        task.lockGeneration += 1
        FileLogger.log("Endpoint", "$task locked to PUBLIC: $reason")
    }

    private fun recomputeDisplayRouteLocked(cfg: EndpointConfig) {
        val task = activeTask
        _displayRoute.value = when {
            !cfg.hasAnyAddress -> null
            task != null && task.evaluated -> task.route
            else -> ambientRouteLocked(cfg, kick = false)
        }
    }

    private fun baseFor(route: Route, cfg: EndpointConfig): HttpUrl = when (route) {
        Route.LAN -> cfg.lanBase
        Route.PUBLIC -> cfg.publicBase
    } ?: throw NoEndpointConfiguredException()

    private fun loginAddress(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return (ServerAddress.validate(trimmed, null) as? ValidationResult.Ok)?.canonical ?: trimmed
    }

    // ---- test hooks ----

    @androidx.annotation.VisibleForTesting
    internal fun networkGenerationForTest(): Long = synchronized(lock) { networkGeneration }

    @androidx.annotation.VisibleForTesting
    internal fun probeCacheForTest(): ProbeCacheEntry? = synchronized(lock) { probeCache }

    @androidx.annotation.VisibleForTesting
    internal fun isLiveForTest(task: RouteSession.Task): Boolean = synchronized(lock) { task in liveTasks }

    companion object {
        private const val TAG = "PhotoVaultEndpoint"

        /** Probe cache lifetime (CR-2). */
        const val CACHE_TTL_MS = 60_000L
    }
}
