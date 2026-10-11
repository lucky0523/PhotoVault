package com.huoyi.photovault.data.network.endpoint

import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ConnectionErrorClassifierTest {

    @Test
    fun `connection level exceptions`() {
        listOf(
            UnknownHostException("nas"),
            ConnectException("refused"),
            NoRouteToHostException("no route"),
            PortUnreachableException("unreachable"),
            SocketTimeoutException("timeout"),
            SocketException("Connection reset"),
            SSLHandshakeException("bad cert"),
            EOFException(),
            IOException("unexpected end of stream on http://192.168.1.10:8000/..."),
            InterruptedIOException("timeout")
        ).forEach { assertTrue("$it", ConnectionErrorClassifier.isConnectionLevel(it)) }
    }

    @Test
    fun `root cause is inspected`() {
        assertTrue(ConnectionErrorClassifier.isConnectionLevel(Exception("连接失败", ConnectException())))
        assertTrue(ConnectionErrorClassifier.isConnectionLevel(IOException("wrapped", IOException("x", SocketTimeoutException()))))
    }

    @Test
    fun `non connection level exceptions`() {
        assertFalse(ConnectionErrorClassifier.isConnectionLevel(null))
        assertFalse(ConnectionErrorClassifier.isConnectionLevel(IOException("Canceled")))
        assertFalse(ConnectionErrorClassifier.isConnectionLevel(InterruptedIOException("interrupted")))
        assertFalse(ConnectionErrorClassifier.isConnectionLevel(ProtocolException("bad status line")))
        assertFalse(ConnectionErrorClassifier.isConnectionLevel(IllegalStateException("boom")))
        assertFalse(ConnectionErrorClassifier.isConnectionLevel(Exception("HTTP 500")))
    }

    // ---- Replayability ----

    private fun request(method: String, path: String, body: RequestBody? = null) =
        Request.Builder().url("http://photovault.invalid$path").method(method, body).build()

    @Test
    fun `idempotent and whitelisted requests are replayable`() {
        assertTrue(Replayability.isReplayable(request("GET", "/api/v1/files/list")))
        assertTrue(Replayability.isReplayable(request("HEAD", "/api/v1/files/list")))
        assertTrue(Replayability.isReplayable(request("GET", "/api/v1/connection/test")))
        assertTrue(Replayability.isReplayable(request("POST", "/api/v1/backup/chunk", "x".toRequestBody())))
        assertTrue(Replayability.isReplayable(request("POST", "/api/v1/auth/refresh", "{}".toRequestBody())))
    }

    @Test
    fun `other writes are not replayable`() {
        assertFalse(Replayability.isReplayable(request("POST", "/api/v1/backup/init", "{}".toRequestBody())))
        assertFalse(Replayability.isReplayable(request("POST", "/api/v1/backup/complete", "{}".toRequestBody())))
        assertFalse(Replayability.isReplayable(request("POST", "/api/v1/auth/login", "{}".toRequestBody())))
        assertFalse(Replayability.isReplayable(request("DELETE", "/api/v1/files/1")))
    }

    @Test
    fun `one-shot body is never replayable`() {
        val oneShot = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun writeTo(sink: BufferedSink) = Unit
            override fun isOneShot(): Boolean = true
        }
        assertFalse(Replayability.isReplayable(request("POST", "/api/v1/backup/chunk", oneShot)))
    }
}
