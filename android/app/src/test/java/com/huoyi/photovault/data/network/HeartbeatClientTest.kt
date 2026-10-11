package com.huoyi.photovault.data.network

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
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** [buildHeartbeatClient] keeps routing, drops BODY logging and uses 5/5 s (design §5.5, R2#11). */
class HeartbeatClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `heartbeat client keeps routing, drops logging and uses 5 second timeouts`() {
        val config = FakeConfigSource(publicAddress = "https://photos.example.com", lanAddress = null)
        val resolver = EndpointResolver(config, FakeNetworkMonitor(), FakeLanProbe(), FakeClock(), scope)
        val routing = EndpointRoutingInterceptor(resolver, config)
        val shared = OkHttpClient.Builder()
            .addInterceptor(routing)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
            .addNetworkInterceptor(DispatchMarkerInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val heartbeat = buildHeartbeatClient(shared)

        assertTrue(heartbeat.interceptors.none { it is HttpLoggingInterceptor })
        assertTrue(heartbeat.interceptors.contains(routing))
        assertTrue(heartbeat.networkInterceptors.contains(DispatchMarkerInterceptor))
        assertEquals(5_000, heartbeat.connectTimeoutMillis)
        assertEquals(5_000, heartbeat.readTimeoutMillis)
        // The shared client itself is untouched.
        assertTrue(shared.interceptors.any { it is HttpLoggingInterceptor })
    }
}
