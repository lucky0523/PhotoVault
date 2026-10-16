package com.huoyi.photovault.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Helpers for the Android 17 (API 37) local network permission.
 *
 * Apps targeting SDK 37+ have LAN access blocked by default and must declare and request
 * [Manifest.permission.ACCESS_LOCAL_NETWORK] (Nearby devices group) to reach the PhotoVault
 * server on the home network (e.g. http://192.168.1.10:8000/). Without it, TCP connects to
 * LAN hosts just time out, so to the networking code a missing grant looks like an
 * unreachable LAN address. Below API 37 the permission doesn't exist and must not be asked.
 */
object LocalNetworkPermission {
    const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK

    /** How the user answered a request that didn't end in a grant. */
    enum class Denial { RETRYABLE, PERMANENT }

    /** Whether this platform version gates LAN access behind [PERMISSION]. */
    fun isRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.CINNAMON_BUN

    /** True when LAN access is allowed: granted, or not required on this API level. */
    fun isGranted(context: Context): Boolean =
        !isRequired() ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Classifies the result of a request. Null when granted; [Denial.RETRYABLE] when the system
     * would still show its prompt (rationale true); otherwise [Denial.PERMANENT], where only the
     * app settings page can grant it.
     */
    fun denialAfterRequest(granted: Boolean, shouldShowRationale: Boolean): Denial? = when {
        granted -> null
        shouldShowRationale -> Denial.RETRYABLE
        else -> Denial.PERMANENT
    }

    /** Opens this app's system details page so the user can allow the permission there. */
    fun openAppSettings(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startIfResolvable(context, intent)
    }

    private fun startIfResolvable(context: Context, intent: Intent): Boolean {
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            true
        } else {
            false
        }
    }
}
