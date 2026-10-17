package com.huoyi.photovault.data.local

/**
 * Builds the one-line diagnostic written when [AccountSessionManager.activate]
 * compares the saved account identity with the one returned by login.
 *
 * Pure so it can be unit-tested. The output never contains the full
 * instance_id, tokens, passwords or server addresses; user_id is a small
 * server-side integer and is kept as-is for diagnosis.
 */
internal object SessionChangeDiagnostics {

    enum class Reason { PREVIOUS_MISSING, INSTANCE_CHANGED, USER_CHANGED }

    /** Why [incoming] differs from [previous]; empty when they are equal. */
    fun reasons(previous: StableAccountIdentity?, incoming: StableAccountIdentity): List<Reason> {
        if (previous == null) return listOf(Reason.PREVIOUS_MISSING)
        return buildList {
            if (previous.instanceId != incoming.instanceId) add(Reason.INSTANCE_CHANGED)
            if (previous.userId != incoming.userId) add(Reason.USER_CHANGED)
        }
    }

    /** First 6 characters followed by "…", or "null". */
    fun maskInstanceId(id: String?): String = id?.take(6)?.plus("…") ?: "null"

    /**
     * e.g. `activate changed=true reasons=[INSTANCE_CHANGED,USER_CHANGED]
     * previous=(instance=ab12cd…, user=3) incoming=(instance=ef34gh…, user=5) reauth=false`
     * (one line). `changed=false` always logs `reasons=[]`.
     */
    fun describe(
        previous: StableAccountIdentity?,
        incoming: StableAccountIdentity,
        changed: Boolean,
        reauthenticatedSameAccount: Boolean
    ): String {
        val reasonList = if (changed) reasons(previous, incoming) else emptyList()
        return "activate changed=$changed reasons=[${reasonList.joinToString(",")}] " +
            "previous=${format(previous)} incoming=${format(incoming)} " +
            "reauth=$reauthenticatedSameAccount"
    }

    private fun format(identity: StableAccountIdentity?): String =
        if (identity == null) "null"
        else "(instance=${maskInstanceId(identity.instanceId)}, user=${identity.userId})"
}
