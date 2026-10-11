package com.huoyi.photovault.data.api

import com.huoyi.photovault.data.local.CredentialManager
import com.huoyi.photovault.data.network.endpoint.DispatchMarkerInterceptor
import com.huoyi.photovault.data.network.endpoint.EndpointResolver
import com.huoyi.photovault.data.network.endpoint.EndpointRoutingInterceptor
import com.huoyi.photovault.data.network.endpoint.FakeClock
import com.huoyi.photovault.data.network.endpoint.FakeConfigSource
import com.huoyi.photovault.data.network.endpoint.FakeLanProbe
import com.huoyi.photovault.data.network.endpoint.FakeNetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The refresh client keeps today's 15/30/30 s timeouts and goes through the routing layer (design §5.2, R#11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TokenRefreshCoordinatorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun coordinator(): Pair<TokenRefreshCoordinator, EndpointRoutingInterceptor> {
        val config = FakeConfigSource(publicAddress = "https://photos.example.com", lanAddress = null)
        val resolver = EndpointResolver(config, FakeNetworkMonitor(), FakeLanProbe(), FakeClock(), scope)
        val routing = EndpointRoutingInterceptor(resolver, config)
        // CredentialManager construction is side-effect free (prefs are lazy).
        val credentialManager = CredentialManager(RuntimeEnvironment.getApplication())
        return TokenRefreshCoordinator(credentialManager, config, routing) to routing
    }

    @Test
    fun `refresh client keeps 15-30-30 second timeouts`() {
        val client = coordinator().first.refreshClient
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
    }

    @Test
    fun `refresh client routes through the endpoint layer and marks dispatch`() {
        val (coordinator, routing) = coordinator()
        val client = coordinator.refreshClient
        assertTrue(client.interceptors.contains(routing))
        assertTrue(client.interceptors.none { it is AuthInterceptor })
        assertTrue(client.networkInterceptors.contains(DispatchMarkerInterceptor))
    }
}
