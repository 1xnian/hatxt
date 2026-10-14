package io.homeassistant.companion.android.common.data.endpoint

/**
 * Turns the outcome of a pre-connect resolution attempt into a [ConnectGateResult].
 *
 * The decision is kept free of coroutines, DNS and Android so the exact policy can be unit tested:
 * which failures may fall back to an older address, and when a login has to be refused.
 *
 * ## The rule that matters
 *
 * A **newly resolved** address is only ever used when it passed validation. An address that was
 * never validated is never used, no matter which option is selected. [EndpointStalePolicy] only
 * decides whether the *last known good* address may be reused, and even then the result carries its
 * timestamp so it can be presented as out of date.
 */
object EndpointConnectGate {

    /** A validated address together with the moment it was validated. */
    data class ValidatedAddress(val url: String, val resolvedAtMillis: Long?)

    /** Success path: the caller proceeds, possibly with a different address than before. */
    fun proceed(snapshot: EndpointSnapshot, addressChanged: Boolean): ConnectGateResult = ConnectGateResult(
        decision = if (addressChanged) {
            ConnectDecision.PROCEED_WITH_NEW_ADDRESS
        } else {
            ConnectDecision.PROCEED
        },
        url = snapshot.current?.url,
        resolvedAtMillis = snapshot.lastResolvedAtMillis,
        message = snapshot.current?.downgradeReason
            ?.let { "The connection to this address is not encrypted: $it" },
    )

    /**
     * Failure path.
     *
     * @param policy user setting that decides whether an older address may be reused.
     * @param reason why the fresh lookup failed, used verbatim in the message.
     * @param lastValidated last address that passed validation, or `null` when there is none.
     * @param nowMillis used to describe how old the fallback address is.
     */
    fun failure(
        policy: EndpointStalePolicy,
        reason: String,
        lastValidated: ValidatedAddress?,
        nowMillis: Long,
    ): ConnectGateResult {
        if (policy == EndpointStalePolicy.STRICT) {
            return ConnectGateResult(
                decision = ConnectDecision.BLOCKED,
                message = "$reason, and the settings do not allow using an older address",
            )
        }
        if (lastValidated == null) {
            return ConnectGateResult(
                decision = ConnectDecision.BLOCKED,
                message = "$reason and no address has ever been validated for this server",
            )
        }
        return ConnectGateResult(
            decision = ConnectDecision.PROCEED_WITH_STALE_ADDRESS,
            url = lastValidated.url,
            resolvedAtMillis = lastValidated.resolvedAtMillis,
            message = "$reason. This address was last validated ${
                describeAge(nowMillis - (lastValidated.resolvedAtMillis ?: nowMillis))
            } ago and may be out of date",
        )
    }

    /** Human readable age, deliberately coarse: the UI shows the exact timestamp separately. */
    fun describeAge(ageMillis: Long): String {
        val safeAge = if (ageMillis < 0) 0 else ageMillis
        val minutes = safeAge / 60_000
        return when {
            minutes < 1 -> "less than a minute"
            minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"}"
            else -> {
                val hours = minutes / 60
                "$hours hour${if (hours == 1L) "" else "s"}"
            }
        }
    }
}
