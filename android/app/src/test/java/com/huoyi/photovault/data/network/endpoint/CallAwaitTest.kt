package com.huoyi.photovault.data.network.endpoint

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress

class CallAwaitTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun request(port: Int = server.port) = Request.Builder().url("http://127.0.0.1:$port/x").build()

    @Test
    fun `returns the response`() {
        server.enqueue(MockResponse().setBody("hello"))
        val text = runBlocking { client.newCall(request()).await().use { it.body!!.string() } }
        assertEquals("hello", text)
    }

    @Test
    fun `rethrows the original failure`() {
        val call = client.newCall(request(closedPort()))
        assertThrows(ConnectException::class.java) { runBlocking { call.await() } }
    }

    @Test
    fun `cancelling the coroutine cancels the call`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = client.newCall(request())
        runBlocking {
            val job = launch { call.await() }
            delay(200)
            job.cancel()
            job.join()
        }
        assertTrue(call.isCanceled())
    }

    /** Call whose callback is driven by the test, to deliver a response after cancellation. */
    private class ManualCall(private val request: Request) : Call {
        var callback: Callback? = null
        @Volatile
        private var canceled = false

        override fun request(): Request = request
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }
        override fun cancel() {
            canceled = true
        }
        override fun isExecuted(): Boolean = callback != null
        override fun isCanceled(): Boolean = canceled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = ManualCall(request)
    }

    private class TrackingBody : ResponseBody() {
        @Volatile
        var closed = false
        private val buffer = Buffer().writeUtf8("late")
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = 4
        override fun source(): BufferedSource = buffer
        override fun close() {
            closed = true
            buffer.close()
        }
    }

    @Test
    fun `response arriving after cancellation is closed`() {
        val call = ManualCall(request())
        val body = TrackingBody()
        runBlocking {
            val job = launch { call.await() }
            yield() // let await() enqueue
            job.cancel()
            job.join()
            assertTrue(call.isCanceled())
            assertFalse(body.closed)
            val response = Response.Builder()
                .request(call.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
            call.callback!!.onResponse(call, response)
        }
        assertTrue("late response must be closed", body.closed)
    }
}
