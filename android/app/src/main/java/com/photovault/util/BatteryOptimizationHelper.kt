package com.photovault.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the battery-optimization (Doze) whitelist.
 *
 * Automatic backup relies on background execution — the periodic scan worker, the MediaStore
 * observer, and condition-recovery. Aggressive OEM ROMs (e.g. ColorOS/OxygenOS/MIUI) defer or
 * outright kill these unless the app is exempt from battery optimization. Prompting the user to
 * whitelist PhotoVault is the single most effective way to keep background backup reliable.
 */
object BatteryOptimizationHelper {

    /**
     * Whether the app is currently exempt from battery optimization (on the Doze whitelist).
     * Returns true on API levels where the concept doesn't apply, so callers treat it as "fine".
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Fire the system dialog that asks the user to exempt this app from battery optimization
     * ([Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]). Returns false if no activity can
     * handle it (some ROMs remove it), so the caller can fall back to the generic settings screen.
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startIfResolvable(context, intent)
    }

    /**
     * Open the system's battery-optimization list so the user can find and whitelist the app
     * manually. Used as a fallback when the direct request dialog isn't available.
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
