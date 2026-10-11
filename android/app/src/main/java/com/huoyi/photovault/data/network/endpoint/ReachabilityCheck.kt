package com.huoyi.photovault.data.network.endpoint

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * "Is the backup server reachable right now?" through the shared client, so the check
 * gets routing and LAN→public fallback (design §5.2). No Context / CredentialManager
 * dependency, JVM-testable.
 */
internal object ReachabilityCheck {

    private const val TAG = "PhotoVaultEndpoint"
    private const val CHECK_URL = "http://photovault.invalid/api/v1/connection/test"
    private val CHECK_TIMEOUTS = TimeoutOverride(10_000, 10_000, 10_000)

    /**
     * Uses [session], or the ambient session when null (never `activeTask`). For the
     * ambient session the shared probe is awaited first (≤ 1.5 s) so a cold process
     * still gives the LAN a chance before a backup run.
     */
    suspend fun isServerReachable(
        sharedClient: OkHttpClient,
        resolver: EndpointResolver,
        config: EndpointConfigSource,
        session: RouteSession?
    ): Boolean {
        if (!config.current().hasAnyAddress) return false
        val s = session ?: resolver.ambient
        if (s === resolver.ambient) resolver.awaitAmbientEvaluation()
        return check(sharedClient, s)
    }

    /** GET connection/test with 10/10/10 s timeouts; true on 2xx. */
    suspend fun check(sharedClient: OkHttpClient, session: RouteSession): Boolean {
        val request = Request.Builder()
            .url(CHECK_URL)
            .tag(RouteSession::class.java, session)
            .tag(TimeoutOverride::class.java, CHECK_TIMEOUTS)
            .get()
            .build()
        return try {
            // close() may drain the remaining body, so stay off Main.
            withContext(Dispatchers.IO) { sharedClient.newCall(request).await().use { it.isSuccessful } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "isServerReachable failed: ${e.javaClass.simpleName}")
            false // includes NoEndpointConfiguredException
        }
    }
}
