package com.photovault.util

import android.os.Build
import android.util.Log

/**
 * Resolves a human-readable device name to send to the server as `device_name`.
 *
 * ## Why not just [Build.MODEL]?
 *
 * [Build.MODEL] reads `ro.product.model`, which on Chinese OEM firmware is the
 * regulatory/certification model code rather than anything a user recognises.
 * On a Redmi handset it reads `2604FRK1EC`; on Xiaomi builds `ro.product.model`
 * and `ro.product.model_for_attestation` are in fact the same value. Since the
 * server uses `device_name` verbatim as a storage directory segment
 * (`{root}/{user}/{device}/...`, see `server/app/services/storage_path_engine.py`),
 * that produces unreadable folders on the NAS.
 *
 * Most OEMs also publish the marketing name as a separate, undocumented system
 * property. The name and partition differ per vendor, so we probe a known list:
 *
 * | Vendor                  | Property                          |
 * |-------------------------|-----------------------------------|
 * | Xiaomi / Redmi / POCO   | `ro.product.marketname`           |
 * | OPPO / OnePlus / realme | `ro.vendor.oplus.market.enname`   |
 * | vivo / iQOO             | `ro.vivo.market.name`             |
 * | Huawei / Honor          | `ro.config.marketing_name`        |
 *
 * None of these are AOSP properties: Pixel and Samsung firmware leaves them
 * unset, which is fine because their `ro.product.model` is already readable
 * (`Pixel 9 Pro`, `SM-S9280`). So the resolution order is vendor-specific
 * property, then a sweep of every known property, then [Build.MODEL].
 *
 * ## Caveats
 *
 * - The value is only as good as what the OEM wrote at build time. Engineering
 *   and pre-release units often carry the internal codename instead of the
 *   marketing name (an unreleased Redmi `prague` unit reports `UT02`).
 * - Vendor-partition properties (`ro.vendor.*`) may be readable only from a
 *   matching SELinux context. A denied read surfaces as an empty value here,
 *   which simply falls through to the next candidate.
 * - Resolution is cached for the process lifetime; system properties are
 *   read-only after boot, and this keeps the `getprop` fallback off hot paths.
 */
object DeviceNameProvider {

    private const val TAG = "DeviceNameProvider"

    /** Last-resort name when nothing is readable (e.g. JVM unit tests). */
    private const val FALLBACK = "android"

    /** Xiaomi, Redmi and POCO firmware. */
    private val XIAOMI_PROPS = listOf(
        "ro.product.marketname",
        "ro.product.odm.marketname"
    )

    /** OPPO, OnePlus and realme (ColorOS / OxygenOS). */
    private val OPLUS_PROPS = listOf(
        // English marketing name, e.g. "OnePlus 13". Preferred over
        // market.name, which on CN firmware is the localised name.
        "ro.vendor.oplus.market.enname",
        "ro.vendor.oplus.market.name",
        "ro.oppo.market.name"
    )

    /** vivo and iQOO (OriginOS / Funtouch). */
    private val VIVO_PROPS = listOf("ro.vivo.market.name")

    /** Huawei and Honor (EMUI / HarmonyOS / MagicOS). */
    private val HUAWEI_PROPS = listOf("ro.config.marketing_name")

    /**
     * Every known marketing-name property, probed when the manufacturer string
     * doesn't match a known vendor (rebrands, custom ROMs, regional variants).
     */
    private val ALL_PROPS: List<String> =
        (XIAOMI_PROPS + OPLUS_PROPS + VIVO_PROPS + HUAWEI_PROPS).distinct()

    /**
     * The resolved device name. Never blank.
     *
     * Note this is not a stable device *identifier*: two handsets of the same
     * model return the same string, and a firmware update that rewrites the
     * marketing-name property changes it.
     */
    val deviceName: String by lazy { resolve() }

    private fun resolve(): String {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val vendor = "$manufacturer $brand"

        val preferred = when {
            vendor.contains("xiaomi") ||
                vendor.contains("redmi") ||
                vendor.contains("poco") -> XIAOMI_PROPS

            vendor.contains("oppo") ||
                vendor.contains("oplus") ||
                vendor.contains("oneplus") ||
                vendor.contains("realme") -> OPLUS_PROPS

            vendor.contains("vivo") ||
                vendor.contains("iqoo") -> VIVO_PROPS

            vendor.contains("huawei") ||
                vendor.contains("honor") -> HUAWEI_PROPS

            else -> emptyList()
        }

        val resolved = firstNonBlankProp(preferred)
            ?: firstNonBlankProp(ALL_PROPS)
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: FALLBACK

        Log.i(TAG, "device_name resolved to '$resolved' (model=${Build.MODEL})")
        return resolved
    }

    private fun firstNonBlankProp(keys: List<String>): String? =
        keys.asSequence()
            .mapNotNull { readProperty(it) }
            .firstOrNull()

    /**
     * Reads a system property, or null when it is unset, unreadable or a
     * placeholder.
     *
     * Tries the hidden [android.os.SystemProperties] via reflection first (no
     * process spawn), then falls back to the `getprop` binary, which stays
     * available to apps even when non-SDK reflection is blocked.
     */
    private fun readProperty(key: String): String? =
        (readViaReflection(key) ?: readViaGetprop(key))
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }

    private fun readViaReflection(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        get.invoke(null, key) as? String
    } catch (e: Throwable) {
        // Non-SDK interface restrictions, or no Android runtime at all.
        null
    }

    private fun readViaGetprop(key: String): String? = try {
        val process = ProcessBuilder("getprop", key)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    } catch (e: Throwable) {
        null
    }
}
