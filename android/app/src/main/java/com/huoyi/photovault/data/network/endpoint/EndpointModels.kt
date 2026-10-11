package com.huoyi.photovault.data.network.endpoint

import android.os.SystemClock
import com.huoyi.photovault.data.local.CredentialSessionSnapshot
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl

/** Which configured address a request is (or should be) sent to. */
enum class Route { LAN, PUBLIC }

/**
 * Snapshot of the configured endpoints. [version] increases on every persisted
 * change (saveSession / clearCredentials / setLanAddress) and keys the probe cache.
 */
data class EndpointConfig(
    val publicBase: HttpUrl?,
    val lanBase: HttpUrl?,
    val instanceId: String?,
    val version: Long
) {
    val hasAnyAddress: Boolean get() = publicBase != null || lanBase != null
    val isDual: Boolean get() = publicBase != null && lanBase != null
    val isLanOnly: Boolean get() = lanBase != null && publicBase == null
    val isPublicOnly: Boolean get() = publicBase != null && lanBase == null
}

/** Coarse classification of the default network (design §5.1). */
enum class NetworkClass {
    /** Wi-Fi or Ethernet without VPN: a LAN probe makes sense. */
    LOCAL_CAPABLE,
    OTHER,
    NONE
}

/**
 * Identity of the default network used to detect "network changed" (design §4.1).
 * [handle] is `Network.getNetworkHandle()`; a Long keeps it JVM-testable.
 */
data class NetworkKey(val handle: Long?, val networkClass: NetworkClass) {
    companion object {
        val NONE = NetworkKey(null, NetworkClass.NONE)
    }
}

/** Result of one LAN evaluation. Only [Success] routes to the LAN. */
sealed class ProbeOutcome {
    data object Success : ProbeOutcome()
    data object Timeout : ProbeOutcome()
    data class ConnectFailed(val reason: String) : ProbeOutcome()
    data class HttpStatus(val code: Int) : ProbeOutcome()
    data object NotPhotoVault : ProbeOutcome()
    data object Malformed : ProbeOutcome()
    data object IdentityMismatch : ProbeOutcome()
    data object NoBaseline : ProbeOutcome()
    data object SkippedNotLocalNetwork : ProbeOutcome()

    /** Written by `markLanUnavailable`: the LAN just failed real traffic (design §4.3). */
    data class Failed(val reason: LockReason) : ProbeOutcome()
}

/** The single shared probe cache entry; valid only for the same (networkGeneration, configVersion). */
data class ProbeCacheEntry(
    val outcome: ProbeOutcome,
    val atMillis: Long,
    val networkGeneration: Long,
    val configVersion: Long
)

/** Why a route session is on PUBLIC / why the LAN was marked unavailable. */
enum class LockReason { PROBE_FAILED, NOT_LOCAL_NETWORK, CONSECUTIVE, EXHAUSTED, NETWORK_CHANGED, CONFIG_CHANGED }

/** Per-attempt timeouts in milliseconds (0 = no timeout, as in OkHttp). */
data class Timeouts(val connectMs: Int, val readMs: Int, val writeMs: Int)

/** Request tag forcing per-attempt timeouts (used by the reachability check, 10/10/10 s). */
data class TimeoutOverride(val connectMs: Int, val readMs: Int, val writeMs: Int)

/** Monotonic clock, injectable for tests. */
fun interface Clock {
    fun nowMillis(): Long
}

object ElapsedRealtimeClock : Clock {
    override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}

/** Address chosen for the interactive login request (design §5.4). */
data class LoginTarget(val address: String, val route: Route)

/**
 * Source of the endpoint configuration, implemented by CredentialManager.
 * [sessionSnapshot] lets the routing layer tag requests with the credential
 * session (former BaseUrlInterceptor duty) without depending on CredentialManager.
 */
interface EndpointConfigSource {
    fun current(): EndpointConfig
    val changes: StateFlow<Long>
    fun sessionSnapshot(): CredentialSessionSnapshot
}
