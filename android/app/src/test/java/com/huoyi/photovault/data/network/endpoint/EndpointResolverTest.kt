package com.huoyi.photovault.data.network.endpoint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EndpointResolver] state machine (design §4.3, §9). The resolver scope is the
 * test's backgroundScope (StandardTestDispatcher), the probe is a counting fake and
 * the clock is manual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EndpointResolverTest {

    private val publicAddress = "https://pub.example.com:8443"
    private val lanAddress = "http://192.168.1.10:8000"
    private val lanAddress2 = "http://192.168.1.20:8000"

    private class Env(
        scope: CoroutineScope,
        publicAddress: String?,
        lanAddress: String?,
        initialNetwork: NetworkKey = WIFI_1,
        outcome: ProbeOutcome = ProbeOutcome.Success,
        probeDelayMs: Long = 0L,
        instanceId: String? = TEST_INSTANCE_ID
    ) {
        val config = FakeConfigSource(publicAddress, lanAddress, instanceId)
        val network = FakeNetworkMonitor(initialNetwork)
        val probe = FakeLanProbe(outcome, probeDelayMs)
        val clock = FakeClock()
        val resolver = EndpointResolver(config, network, probe, clock, scope)

        fun ambientRoute(): Route = resolver.ambient.routeForRequest().first
    }

    private fun TestScope.dual(
        network: NetworkKey = WIFI_1,
        outcome: ProbeOutcome = ProbeOutcome.Success,
        probeDelayMs: Long = 0L,
        start: Boolean = true
    ): Env = Env(backgroundScope, publicAddress, lanAddress, network, outcome, probeDelayMs).also {
        if (start) it.resolver.start()
    }

    /**
     * Lets probes on the resolver scope (backgroundScope) finish. advanceUntilIdle()
     * ignores background-only work, so advance virtual time explicitly.
     */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    // ---- AC-3: public only never probes ----

    @Test
    fun `public only never probes`() = runTest {
        val env = Env(backgroundScope, publicAddress, null).also { it.resolver.start() }
        repeat(3) { assertEquals(Route.PUBLIC, env.ambientRoute()) }
        env.resolver.awaitAmbientEvaluation()
        val task = env.resolver.beginTask()
        assertEquals(Route.PUBLIC, task.route)
        assertEquals("pub.example.com:8443", task.routeForRequest().second.hostPort())
        env.network.deliver(WIFI_2)
        settle()
        assertEquals(0, env.probe.calls)
    }

    // ---- cold start / AC-5 ----

    @Test
    fun `cold start routes public then LAN after the probe`() = runTest {
        val env = dual()
        assertEquals(Route.PUBLIC, env.ambientRoute())
        runCurrent()
        assertEquals(1, env.probe.calls)
        val (route, base) = env.resolver.ambient.routeForRequest()
        assertEquals(Route.LAN, route)
        assertEquals("192.168.1.10:8000", base.hostPort())
        assertEquals(1, env.probe.calls)
    }

    @Test
    fun `probe receives the expected fingerprint of the logged-in instance`() = runTest {
        val env = dual()
        runCurrent()
        val invocation = env.probe.invocations.single()
        assertEquals(TEST_FINGERPRINT, invocation.expectedFingerprint)
        assertFalse(invocation.skipIdentity)
        assertEquals("192.168.1.10:8000", invocation.lanBase.hostPort())
    }

    @Test
    fun `missing instance id probes without a baseline`() = runTest {
        val env = Env(backgroundScope, publicAddress, lanAddress, outcome = ProbeOutcome.NoBaseline, instanceId = null)
        env.resolver.start()
        runCurrent()
        assertNull(env.probe.invocations.single().expectedFingerprint)
        assertEquals(Route.PUBLIC, env.ambientRoute())
    }

    // ---- AC-6: every failure routes public ----

    @Test
    fun `every probe failure routes public`() = runTest {
        val failures = listOf(
            ProbeOutcome.Timeout,
            ProbeOutcome.ConnectFailed("ConnectException"),
            ProbeOutcome.HttpStatus(503),
            ProbeOutcome.HttpStatus(500),
            ProbeOutcome.NotPhotoVault,
            ProbeOutcome.Malformed,
            ProbeOutcome.IdentityMismatch,
            ProbeOutcome.NoBaseline
        )
        for (failure in failures) {
            val env = dual(outcome = failure, start = false)
            env.resolver.start()
            val task = env.resolver.beginTask()
            assertEquals("$failure", Route.PUBLIC, task.route)
            assertEquals("$failure", LockReason.PROBE_FAILED, task.lockedReason)
            assertEquals("$failure", Route.PUBLIC, env.ambientRoute())
            assertEquals("$failure", failure, env.resolver.probeCacheForTest()?.outcome)
            assertEquals(1, env.probe.calls)
        }
    }

    // ---- AC-7: non-local network ----

    @Test
    fun `cellular never probes and locks tasks public`() = runTest {
        val env = dual(network = CELLULAR)
        assertEquals(Route.PUBLIC, env.ambientRoute())
        env.resolver.awaitAmbientEvaluation()
        val task = env.resolver.beginTask()
        assertEquals(Route.PUBLIC, task.route)
        assertEquals(LockReason.NOT_LOCAL_NETWORK, task.lockedReason)
        assertEquals(0, task.lockGeneration)
        settle()
        assertEquals(0, env.probe.calls)
    }

    // ---- AC-8: cache hit, expiry, network change ----

    @Test
    fun `cache is reused within 60s and revalidated after`() = runTest {
        val env = dual()
        runCurrent()
        repeat(5) { assertEquals(Route.LAN, env.ambientRoute()) }
        env.clock.advance(59_999)
        assertEquals(Route.LAN, env.ambientRoute())
        runCurrent()
        assertEquals(1, env.probe.calls)

        env.clock.advance(2) // now 60_001 ms old
        assertEquals("stale-while-revalidate keeps the old verdict", Route.LAN, env.ambientRoute())
        assertEquals(Route.LAN, env.ambientRoute())
        runCurrent()
        assertEquals(2, env.probe.calls)
    }

    @Test
    fun `network change triggers a new probe and routes public until it succeeds`() = runTest {
        val env = dual(probeDelayMs = 1_000)
        settle()
        assertEquals(Route.LAN, env.ambientRoute())
        env.network.deliver(WIFI_2)
        assertEquals(Route.PUBLIC, env.ambientRoute())
        runCurrent()
        assertEquals(2, env.probe.calls)
        assertEquals(Route.PUBLIC, env.ambientRoute())
        settle()
        assertEquals(Route.LAN, env.ambientRoute())
        assertEquals(2, env.probe.calls)
    }

    // ---- AC-9: single flight ----

    @Test
    fun `concurrent evaluations share one probe`() = runTest {
        val env = dual(probeDelayMs = 500)
        val t1 = async { env.resolver.beginTask() }
        val t2 = async { env.resolver.beginTask() }
        val pre = async { env.resolver.awaitAmbientEvaluation() }
        assertEquals(Route.PUBLIC, env.ambientRoute())
        settle()
        pre.await()
        assertEquals(Route.LAN, t1.await().route)
        assertEquals(Route.LAN, t2.await().route)
        assertEquals(Route.LAN, env.ambientRoute())
        assertEquals(1, env.probe.calls)
    }

    @Test
    fun `task probe result is reused by ambient`() = runTest {
        val env = dual(start = false)
        // Seed the network without resolver.start(), so there is no initial ambient kick.
        env.network.start(env.resolver::onNetworkChanged)
        val task = env.resolver.beginTask()
        assertEquals(Route.LAN, task.route)
        assertEquals(Route.LAN, env.ambientRoute())
        runCurrent()
        assertEquals(1, env.probe.calls)
    }

    // ---- Wi-Fi → Wi-Fi ----

    @Test
    fun `switching to another wifi locks LAN tasks and keeps ambient public until reprobed`() = runTest {
        val env = dual(probeDelayMs = 1_000)
        val task = env.resolver.beginTask()
        assertEquals(Route.LAN, task.route)
        val probesBefore = env.probe.calls

        env.network.deliver(WIFI_2)
        assertEquals(Route.PUBLIC, task.route)
        assertEquals(LockReason.NETWORK_CHANGED, task.lockedReason)
        assertEquals(1, task.lockGeneration)
        assertEquals(Route.PUBLIC, env.ambientRoute())

        settle()
        assertEquals("ambient re-probes the new network", probesBefore + 1, env.probe.calls)
        assertEquals(Route.LAN, env.ambientRoute())
        assertEquals("task stays locked", Route.PUBLIC, task.routeForRequest().first)
    }

    // ---- markLanUnavailable ----

    @Test
    fun `markLanUnavailable writes Failed for 60s then ambient reprobes`() = runTest {
        val env = dual()
        runCurrent()
        assertEquals(Route.LAN, env.ambientRoute())

        env.resolver.ambient.markLanUnavailable(LockReason.CONSECUTIVE)
        assertEquals(ProbeOutcome.Failed(LockReason.CONSECUTIVE), env.resolver.probeCacheForTest()?.outcome)
        env.clock.advance(59_000)
        repeat(3) { assertEquals(Route.PUBLIC, env.ambientRoute()) }
        runCurrent()
        assertEquals(1, env.probe.calls)

        val task = env.resolver.beginTask()
        assertEquals("new task does not reuse the old success", Route.PUBLIC, task.route)
        assertEquals(LockReason.PROBE_FAILED, task.lockedReason)
        assertEquals(1, env.probe.calls)
        env.resolver.endTask(task)

        env.clock.advance(1_001)
        assertEquals(Route.PUBLIC, env.ambientRoute())
        runCurrent()
        assertEquals(2, env.probe.calls)
        assertEquals(Route.LAN, env.ambientRoute())
    }

    @Test
    fun `markLanUnavailable supersedes an in-flight probe`() = runTest {
        val env = dual(probeDelayMs = 1_000)
        runCurrent() // probe in flight
        env.resolver.ambient.markLanUnavailable(LockReason.EXHAUSTED)
        settle()
        assertEquals(ProbeOutcome.Failed(LockReason.EXHAUSTED), env.resolver.probeCacheForTest()?.outcome)
        assertEquals(Route.PUBLIC, env.ambientRoute())
    }

    // ---- AC-30 / AC-31 ----

    @Test
    fun `AC-30 locked task stays public across network changes and time, next task reprobes`() = runTest {
        val env = dual()
        val task = env.resolver.beginTask()
        assertEquals(Route.LAN, task.route)
        assertEquals(1, env.probe.calls)

        task.markLanUnavailable(LockReason.CONSECUTIVE)
        assertEquals(Route.PUBLIC, task.route)
        env.network.deliver(CELLULAR)
        env.clock.advance(60_001)
        repeat(3) { assertEquals(Route.PUBLIC, task.routeForRequest().first) }
        settle()
        assertEquals("no probe while locked", 1, env.probe.calls)

        env.network.deliver(WIFI_2)
        assertEquals(Route.PUBLIC, task.routeForRequest().first)
        env.resolver.endTask(task)
        val next = env.resolver.beginTask()
        assertEquals(2, env.probe.calls)
        assertEquals(Route.LAN, next.route)
        assertEquals(Route.PUBLIC, task.route)
    }

    @Test
    fun `AC-31 LAN task does not reprobe as time passes`() = runTest {
        val env = dual()
        val task = env.resolver.beginTask()
        assertEquals(Route.LAN, task.route)
        repeat(3) {
            env.clock.advance(61_000)
            assertEquals(Route.LAN, task.routeForRequest().first)
        }
        settle()
        assertEquals(1, env.probe.calls)
    }

    @Test
    fun `lockGeneration counts only LAN to PUBLIC transitions`() = runTest {
        val env = dual()
        val task = env.resolver.beginTask()
        assertEquals(0, task.lockGeneration)
        task.markLanUnavailable(LockReason.EXHAUSTED)
        assertEquals(1, task.lockGeneration)
        assertEquals(LockReason.EXHAUSTED, task.lockedReason)
        task.markLanUnavailable(LockReason.CONSECUTIVE)
        env.network.deliver(WIFI_2)
        assertEquals(1, task.lockGeneration)
        assertEquals(LockReason.EXHAUSTED, task.lockedReason)

        val cellEnv = dual(network = CELLULAR)
        val startedPublic = cellEnv.resolver.beginTask()
        assertEquals(0, startedPublic.lockGeneration)
    }

    // ---- task boundaries ----

    @Test
    fun `endTask of an old task keeps the newer active task`() = runTest {
        val env = dual()
        val old = env.resolver.beginTask()
        val new = env.resolver.beginTask()
        assertSame(new, env.resolver.activeTask)
        env.resolver.endTask(old)
        assertSame(new, env.resolver.activeTask)
        assertFalse(env.resolver.isLiveForTest(old))
        assertTrue(env.resolver.isLiveForTest(new))
        env.resolver.endTask(new)
        assertNull(env.resolver.activeTask)
    }

    @Test
    fun `cancelling beginTask while probing unregisters the task`() = runTest {
        val env = dual(probeDelayMs = 1_000)
        val job = launch { env.resolver.beginTask() }
        runCurrent()
        val registered = env.resolver.activeTask!!
        assertTrue(env.resolver.isLiveForTest(registered))
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertNull(env.resolver.activeTask)
        assertFalse(env.resolver.isLiveForTest(registered))
    }

    // ---- LAN only ----

    @Test
    fun `lan only never probes and never locks`() = runTest {
        val env = Env(backgroundScope, null, lanAddress).also { it.resolver.start() }
        assertEquals(Route.LAN, env.ambientRoute())
        val task = env.resolver.beginTask()
        assertEquals(Route.LAN, task.route)
        env.network.deliver(CELLULAR)
        env.network.deliver(WIFI_2)
        task.markLanUnavailable(LockReason.EXHAUSTED)
        env.config.update(null, lanAddress2)
        settle()
        assertEquals(Route.LAN, task.route)
        assertEquals(0, task.lockGeneration)
        assertEquals("192.168.1.20:8000", task.routeForRequest().second.hostPort())
        assertEquals(Route.LAN, env.ambientRoute())
        assertEquals(0, env.probe.calls)
    }

    // ---- config changes / current-config rule ----

    @Test
    fun `config change locks LAN tasks to public`() = runTest {
        val env = dual()
        val task = env.resolver.beginTask()
        assertEquals(Route.LAN, task.route)
        env.config.update(publicAddress, lanAddress2)
        runCurrent() // config.changes collector
        assertEquals(Route.PUBLIC, task.route)
        assertEquals(LockReason.CONFIG_CHANGED, task.lockedReason)
        assertEquals(1, task.lockGeneration)
    }

    @Test
    fun `config change is applied even before the collector runs`() = runTest {
        val env = dual()
        val task = env.resolver.beginTask()
        env.config.update(publicAddress, lanAddress2)
        assertEquals(Route.PUBLIC, task.routeForRequest().first)
        assertEquals(LockReason.CONFIG_CHANGED, task.lockedReason)
    }

    @Test
    fun `locked task with lan-only config has no endpoint`() = runTest {
        val env = dual()
        val task = env.resolver.beginTask()
        task.markLanUnavailable(LockReason.CONSECUTIVE)
        env.config.update(null, lanAddress)
        assertThrows(NoEndpointConfiguredException::class.java) { task.routeForRequest() }
        assertEquals(Route.PUBLIC, task.route)
    }

    @Test
    fun `LAN task stays on LAN when config becomes lan-only and uses the new address`() = runTest {
        val env = dual()
        val task = env.resolver.beginTask()
        env.config.update(null, lanAddress2)
        runCurrent()
        val (route, base) = task.routeForRequest()
        assertEquals(Route.LAN, route)
        assertEquals("192.168.1.20:8000", base.hostPort())
        assertEquals(0, task.lockGeneration)
    }

    @Test
    fun `config change during the start probe locks the task public`() = runTest {
        val env = dual(probeDelayMs = 1_000)
        val pending = async { env.resolver.beginTask() }
        runCurrent()
        env.config.update(publicAddress, lanAddress2)
        settle()
        val task = pending.await()
        assertEquals(Route.PUBLIC, task.route)
        assertEquals(LockReason.CONFIG_CHANGED, task.lockedReason)
    }

    @Test
    fun `network change during the start probe locks the task public`() = runTest {
        val env = dual(probeDelayMs = 1_000)
        val pending = async { env.resolver.beginTask() }
        runCurrent()
        env.network.deliver(WIFI_2)
        settle()
        val task = pending.await()
        assertEquals(Route.PUBLIC, task.route)
        assertEquals(LockReason.NETWORK_CHANGED, task.lockedReason)
    }

    // ---- isolation ----

    @Test
    fun `ambient and task failures are isolated`() = runTest {
        val env = dual()
        val task = env.resolver.beginTask()
        repeat(3) { env.resolver.ambient.onLanFailure() }
        env.resolver.ambient.markLanUnavailable(LockReason.CONSECUTIVE)
        assertEquals(Route.PUBLIC, env.ambientRoute())
        assertEquals("ambient failure does not move the task", Route.LAN, task.route)
        assertEquals(0, task.lanFailures.get())
        assertEquals(0, task.lockGeneration)

        task.onLanFailure()
        assertEquals(3, env.resolver.ambient.lanFailures.get())
        task.onHttpResponse(Route.LAN)
        assertEquals(0, task.lanFailures.get())
        assertEquals("task success does not reset ambient", 3, env.resolver.ambient.lanFailures.get())
    }

    @Test
    fun `successful probe resets the ambient failure counter`() = runTest {
        val env = dual()
        runCurrent()
        repeat(2) { env.resolver.ambient.onLanFailure() }
        env.network.deliver(WIFI_2)
        runCurrent()
        assertEquals(0, env.resolver.ambient.lanFailures.get())
    }

    @Test
    fun `lan http response refreshes the ambient cache only`() = runTest {
        val env = dual()
        runCurrent()
        val task = env.resolver.beginTask()
        val at = env.resolver.probeCacheForTest()!!.atMillis
        env.clock.advance(30_000)
        task.onHttpResponse(Route.LAN)
        assertEquals(at, env.resolver.probeCacheForTest()!!.atMillis)
        env.resolver.ambient.onHttpResponse(Route.LAN)
        assertEquals(at + 30_000, env.resolver.probeCacheForTest()!!.atMillis)
    }

    // ---- network monitor edge cases ----

    @Test
    fun `degraded monitor reports changes while polling`() = runTest {
        val env = dual()
        val task = env.resolver.beginTask()
        env.network.degraded = true
        env.network.polledKey = WIFI_2
        assertEquals(Route.PUBLIC, env.ambientRoute())
        assertEquals(1L, env.resolver.networkGenerationForTest())
        assertEquals(Route.PUBLIC, task.route)
        assertEquals(LockReason.NETWORK_CHANGED, task.lockedReason)
    }

    @Test
    fun `first callback for the seeded network is not a change`() = runTest {
        val env = dual()
        val task = env.resolver.beginTask()
        assertEquals(Route.LAN, task.route)
        env.network.deliver(WIFI_1)
        assertEquals(0L, env.resolver.networkGenerationForTest())
        assertEquals(Route.LAN, task.route)
        assertEquals(0, task.lockGeneration)
    }

    @Test
    fun `without start the network is NONE`() = runTest {
        val env = dual(start = false)
        val task = env.resolver.beginTask()
        assertEquals(Route.PUBLIC, task.route)
        assertEquals(LockReason.NOT_LOCAL_NETWORK, task.lockedReason)
        assertEquals(0, env.probe.calls)
    }

    @Test
    fun `start is idempotent`() = runTest {
        val env = dual()
        env.resolver.start()
        assertEquals(1, env.network.startCount)
        runCurrent()
        assertEquals(1, env.probe.calls)
    }

    // ---- displayRoute ----

    @Test
    fun `displayRoute is recomputed on every state change`() = runTest {
        val env = dual()
        val display = env.resolver.displayRoute
        runCurrent()
        assertEquals(Route.LAN, display.value)

        env.network.deliver(CELLULAR)
        assertEquals("network change applies immediately", Route.PUBLIC, display.value)
        env.network.deliver(WIFI_1)
        assertEquals(Route.PUBLIC, display.value)
        runCurrent()
        assertEquals(Route.LAN, display.value)

        env.resolver.ambient.markLanUnavailable(LockReason.CONSECUTIVE)
        assertEquals(Route.PUBLIC, display.value)

        env.config.update(publicAddress, lanAddress)
        runCurrent() // collector applies the version change and re-probes
        assertEquals(Route.LAN, display.value)

        val task = env.resolver.beginTask()
        assertEquals(Route.LAN, display.value)
        env.network.deliver(CELLULAR)
        assertEquals(Route.PUBLIC, display.value)
        env.network.deliver(WIFI_1)
        runCurrent()
        assertEquals(Route.LAN, env.ambientRoute())
        assertEquals("active task drives the indicator", Route.PUBLIC, display.value)
        env.resolver.endTask(task)
        assertEquals(Route.LAN, display.value)

        env.config.update(null, null)
        runCurrent()
        assertNull(display.value)
    }

    // ---- awaitAmbientEvaluation ----

    @Test
    fun `awaitAmbientEvaluation waits for the probe on a cold cache`() = runTest {
        val env = dual(probeDelayMs = 1_000)
        val before = currentTime
        env.resolver.awaitAmbientEvaluation()
        assertEquals(1_000, currentTime - before)
        assertEquals(Route.LAN, env.ambientRoute())
        assertEquals(1, env.probe.calls)
    }

    @Test
    fun `awaitAmbientEvaluation returns at once with a fresh cache`() = runTest {
        val env = dual()
        runCurrent()
        val before = currentTime
        env.resolver.awaitAmbientEvaluation()
        env.resolver.ambient.markLanUnavailable(LockReason.CONSECUTIVE)
        env.resolver.awaitAmbientEvaluation()
        assertEquals(before, currentTime)
        assertEquals(1, env.probe.calls)
    }

    @Test
    fun `awaitAmbientEvaluation reprobes a stale cache`() = runTest {
        val env = dual(probeDelayMs = 100)
        settle()
        env.clock.advance(60_000)
        env.resolver.awaitAmbientEvaluation()
        assertEquals(2, env.probe.calls)
    }

    @Test
    fun `awaitAmbientEvaluation never probes single-address or non-local configs`() = runTest {
        val publicOnly = Env(backgroundScope, publicAddress, null).also { it.resolver.start() }
        val lanOnly = Env(backgroundScope, null, lanAddress).also { it.resolver.start() }
        val cellular = dual(network = CELLULAR)
        for (env in listOf(publicOnly, lanOnly, cellular)) {
            env.resolver.awaitAmbientEvaluation()
        }
        settle()
        assertEquals(0, publicOnly.probe.calls + lanOnly.probe.calls + cellular.probe.calls)
    }

    @Test
    fun `awaitAmbientEvaluation shares the probe with beginTask`() = runTest {
        val env = dual(probeDelayMs = 500)
        val task = async { env.resolver.beginTask() }
        env.resolver.awaitAmbientEvaluation()
        assertEquals(Route.LAN, task.await().route)
        assertEquals(1, env.probe.calls)
    }

    @Test
    fun `awaitAmbientEvaluation returns after a probe timeout with ambient public`() = runTest {
        val env = dual(outcome = ProbeOutcome.Timeout, probeDelayMs = 1_500)
        env.resolver.awaitAmbientEvaluation()
        assertEquals(Route.PUBLIC, env.ambientRoute())
        assertEquals(ProbeOutcome.Timeout, env.resolver.probeCacheForTest()?.outcome)
    }

    @Test
    fun `cancelling one waiter does not cancel the shared probe`() = runTest {
        val env = dual(probeDelayMs = 1_000)
        val waiter = async { env.resolver.awaitAmbientEvaluation() }
        val task = async { env.resolver.beginTask() }
        runCurrent()
        waiter.cancel()
        settle()
        assertTrue(waiter.isCancelled)
        val thrown = try {
            waiter.await()
            null
        } catch (e: CancellationException) {
            e
        }
        assertTrue(thrown is CancellationException)
        assertEquals(Route.LAN, task.await().route)
        assertEquals(1, env.probe.calls)
    }

    // ---- selectLoginTarget ----

    @Test
    fun `login target with a single address uses it without probing`() = runTest {
        val env = dual()
        runCurrent()
        val before = env.probe.calls
        assertEquals(LoginTarget("http://192.168.1.10:8000", Route.LAN), env.resolver.selectLoginTarget("", "192.168.1.10:8000"))
        assertEquals(LoginTarget("https://pub.example.com:8443", Route.PUBLIC), env.resolver.selectLoginTarget(publicAddress, "  "))
        assertEquals(before, env.probe.calls)
    }

    @Test
    fun `login target prefers a reachable LAN without identity check`() = runTest {
        val env = dual()
        runCurrent()
        assertEquals(LoginTarget(lanAddress, Route.LAN), env.resolver.selectLoginTarget(publicAddress, lanAddress))
        val invocation = env.probe.invocations.last()
        assertTrue(invocation.skipIdentity)
        assertNull(invocation.expectedFingerprint)

        env.probe.outcome = ProbeOutcome.ConnectFailed("ConnectException")
        assertEquals(LoginTarget(publicAddress, Route.PUBLIC), env.resolver.selectLoginTarget(publicAddress, lanAddress))
    }

    @Test
    fun `login target on cellular is public without probing`() = runTest {
        val env = dual(network = CELLULAR)
        assertEquals(LoginTarget(publicAddress, Route.PUBLIC), env.resolver.selectLoginTarget(publicAddress, lanAddress))
        assertEquals(0, env.probe.calls)
    }

    // ---- derived URLs ----

    @Test
    fun `image base url follows the instance id`() = runTest {
        val env = dual()
        assertEquals("http://0aa7ca809fe5773d.photovault.invalid", env.resolver.imageBaseUrl())
        env.config.update(publicAddress, lanAddress, instanceId = "3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d")
        runCurrent()
        assertEquals(ServerAddress.imageBaseUrlFor("3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d"), env.resolver.imageBaseUrl())
    }

    @Test
    fun `ambient base url string has no trailing slash`() = runTest {
        val env = Env(backgroundScope, publicAddress, null).also { it.resolver.start() }
        assertEquals("https://pub.example.com:8443", env.resolver.ambientBaseUrlString())
        env.config.update(null, null)
        assertNull(env.resolver.ambientBaseUrlString())
        assertThrows(NoEndpointConfiguredException::class.java) { env.resolver.ambient.routeForRequest() }
    }
}
