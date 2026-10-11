package com.huoyi.photovault.ui.login

import com.huoyi.photovault.data.network.endpoint.AddressError
import com.huoyi.photovault.data.network.endpoint.ServerAddress
import com.huoyi.photovault.data.network.endpoint.ValidationResult

/**
 * Outcome of checking the two login-page address fields (design §5.4, steps 2–3 of
 * both login() and testConnection()).
 *
 * - [requiredAnyMissing]: both fields are blank after trim (nothing else is set).
 * - [publicError] / [lanError]: per-field validation error, shown under that field.
 * - [publicCanonical] / [lanCanonical]: canonical `scheme://host[:port]` of a field that
 *   is non-blank and valid; null for blank or invalid fields.
 */
data class AddressCheckPlan(
    val requiredAnyMissing: Boolean,
    val publicError: AddressError?,
    val lanError: AddressError?,
    val publicCanonical: String?,
    val lanCanonical: String?
)

/**
 * Validates the public and LAN fields against each other. Blank fields produce neither
 * an error nor a canonical value; when both are blank only [AddressCheckPlan.requiredAnyMissing]
 * is set. Pure, so LoginViewModel only maps the result to text and requests.
 */
fun planAddressCheck(publicRaw: String, lanRaw: String): AddressCheckPlan {
    if (publicRaw.isBlank() && lanRaw.isBlank()) {
        return AddressCheckPlan(
            requiredAnyMissing = true,
            publicError = null,
            lanError = null,
            publicCanonical = null,
            lanCanonical = null
        )
    }
    val public = checkField(publicRaw, other = lanRaw)
    val lan = checkField(lanRaw, other = publicRaw)
    return AddressCheckPlan(
        requiredAnyMissing = false,
        publicError = public.first,
        lanError = lan.first,
        publicCanonical = public.second,
        lanCanonical = lan.second
    )
}

/** (error, canonical) for one field; (null, null) when blank. */
private fun checkField(raw: String, other: String): Pair<AddressError?, String?> {
    if (raw.isBlank()) return null to null
    return when (val result = ServerAddress.validate(raw, other = other)) {
        is ValidationResult.Ok -> null to result.canonical
        is ValidationResult.Err -> result.error to null
    }
}
