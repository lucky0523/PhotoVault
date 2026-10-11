package com.huoyi.photovault.data.network.endpoint

import android.util.Log
import com.google.gson.Gson
import com.huoyi.photovault.data.api.model.ProbeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Checks whether a LAN address is a reachable PhotoVault of the expected instance (design §4.2). */
interface LanProbe {
    /**
     * @param expectedFingerprint fingerprint of the logged-in instance; null means "no baseline".
     * @param skipIdentity login scenario: only `status`/`app` are checked.
     */
    suspend fun probe(lanBase: HttpUrl, expectedFingerprint: String?, skipIdentity: Boolean): ProbeOutcome
}

const val PROBE_TIMEOUT_MS = 1_500L

/**
 * Dedicated client for probes: own dispatcher and pool, no interceptors, no redirects,
 * no silent retries. `callTimeout` is only a second line of defence — the 1.5 s bound
 * is enforced by the caller-side coroutine timeout (DNS lookups are not interruptible).
 */
fun defaultProbeClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

/**
 * OkHttp implementation of [LanProbe]. Safe to call from any context, including Main:
 * the call is enqueued (never `execute()`), and the body is read on [ioDispatcher].
 */
class OkHttpLanProbe(
    private val client: OkHttpClient = defaultProbeClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LanProbe {

    private val gson = Gson()

    override suspend fun probe(lanBase: HttpUrl, expectedFingerprint: String?, skipIdentity: Boolean): ProbeOutcome {
        val call = client.newCall(probeRequest(lanBase))
        val outcome = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                val response = call.await()
                withContext(ioDispatcher) {
                    response.use { classify(it, expectedFingerprint, skipIdentity) }
                }
            } ?: run {
                call.cancel()
                ProbeOutcome.Timeout
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            classifyFailure(e)
        }
        log(lanBase, outcome)
        return outcome
    }

    private fun probeRequest(lanBase: HttpUrl): Request = Request.Builder()
        .url(lanBase.newBuilder().encodedPath(PROBE_PATH).query(null).fragment(null).build())
        .get()
        .build()

    private fun classify(response: Response, expectedFingerprint: String?, skipIdentity: Boolean): ProbeOutcome {
        if (!response.isSuccessful) return ProbeOutcome.HttpStatus(response.code)
        val text = response.body?.string() ?: return ProbeOutcome.Malformed
        val body = try {
            gson.fromJson(text, ProbeResponse::class.java)
        } catch (e: RuntimeException) {
            null
        } ?: return ProbeOutcome.Malformed
        if (body.status != "ok" || body.app != APP_NAME) return ProbeOutcome.NotPhotoVault
        if (skipIdentity) return ProbeOutcome.Success
        if (expectedFingerprint == null) return ProbeOutcome.NoBaseline
        if (body.instanceFingerprint != expectedFingerprint) return ProbeOutcome.IdentityMismatch
        return ProbeOutcome.Success
    }

    private fun classifyFailure(e: IOException): ProbeOutcome =
        // SocketTimeoutException and OkHttp's callTimeout InterruptedIOException("timeout").
        if (e is InterruptedIOException) ProbeOutcome.Timeout else ProbeOutcome.ConnectFailed(e.javaClass.simpleName)

    private fun log(lanBase: HttpUrl, outcome: ProbeOutcome) {
        val target = "${lanBase.host}:${lanBase.port}"
        when (outcome) {
            ProbeOutcome.Success -> Log.i(TAG, "LAN probe $target: success")
            ProbeOutcome.NotPhotoVault, ProbeOutcome.IdentityMismatch ->
                Log.w(TAG, "LAN probe $target: $outcome")
            else -> Log.i(TAG, "LAN probe $target: $outcome")
        }
    }

    private companion object {
        const val TAG = "PhotoVaultEndpoint"
        const val PROBE_PATH = "/api/v1/connection/test"
        const val APP_NAME = "photovault"
    }
}
