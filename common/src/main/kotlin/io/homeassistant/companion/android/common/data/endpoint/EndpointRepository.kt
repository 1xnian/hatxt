package io.homeassistant.companion.android.common.data.endpoint

import kotlinx.coroutines.flow.Flow

/** Why a refresh was started. Recorded so the diagnostics log can tell the cases apart. */
enum class EndpointRefreshSource {
    /** The user pressed "refresh now". */
    MANUAL,

    /** A scheduled foreground/background refresh. */
    PERIODIC,

    /** A refresh that has to happen before a login or a reconnect. */
    PRE_CONNECT,

    /** The device came back online. */
    NETWORK_RECOVERY,

    /** The app returned to the foreground. */
    FOREGROUND_RESUME,
}

/** Outcome of a refresh request. */
sealed interface EndpointRefreshOutcome {
    /** The attempt ran and its result was applied to the endpoint state. */
    data class Applied(val update: EndpointUpdate) : EndpointRefreshOutcome

    /** Another attempt was already running, this request was dropped (single-flight). */
    data object SkippedConcurrent : EndpointRefreshOutcome

    /** The resolver is in fixed-URL mode or has no record name, so there is nothing to refresh. */
    data object SkippedNotConfigured : EndpointRefreshOutcome

    /** The scheduled refresh is not due yet. */
    data object NotDue : EndpointRefreshOutcome

    /** The device is offline, querying DNS would be pointless. */
    data object SkippedOffline : EndpointRefreshOutcome
}

/** Decision of the pre-connect gate. */
enum class ConnectDecision {
    /** Continue with the address the client already uses. */
    PROCEED,

    /** Continue, but with a freshly resolved address that replaced the previous one. */
    PROCEED_WITH_NEW_ADDRESS,

    /**
     * Continue with the last validated address even though the current lookup failed. The caller
     * must surface that the address is stale and when it was last validated.
     */
    PROCEED_WITH_STALE_ADDRESS,

    /** Do not connect. No validated address is available and the policy forbids a stale one. */
    BLOCKED,
}

/**
 * Result of [EndpointRepository.ensureFreshBeforeConnect].
 *
 * @property decision what the caller has to do.
 * @property url address to connect to. `null` only when [decision] is [ConnectDecision.BLOCKED].
 * @property resolvedAtMillis when this address was validated, so the UI can show its age.
 * @property message human readable explanation, safe to show to the user.
 */
data class ConnectGateResult(
    val decision: ConnectDecision,
    val url: String? = null,
    val resolvedAtMillis: Long? = null,
    val message: String? = null,
) {
    /** True when the caller may start the connection. */
    val mayConnect: Boolean get() = decision != ConnectDecision.BLOCKED

    /** True when the address is a fallback that the current lookup could not confirm. */
    val isStale: Boolean get() = decision == ConnectDecision.PROCEED_WITH_STALE_ADDRESS
}

/**
 * Owns the dynamic address of one server.
 *
 * The repository is the only component that talks to DNS and to the endpoint state machine. Two
 * entry points matter to the rest of the app:
 *
 *  * [ensureFreshBeforeConnect] is the hard gate that runs right before a login or a reconnect. It
 *    must be awaited before a session is opened.
 *  * [snapshot] is the observable state the settings screen renders.
 *
 * Everything else ([refreshNow], [refreshIfDue]) drives the periodic refresh.
 */
interface EndpointRepository {

    /** Observable state of the dynamic endpoint. Always emits the current value on collection. */
    val snapshot: Flow<EndpointSnapshot>

    /** Current settings of this server. */
    suspend fun settings(): EndpointSettings

    /** Persists [settings] and re-arms the refresh schedule. */
    suspend fun updateSettings(settings: EndpointSettings)

    /** Persists whether the user accepts an unencrypted connection to this server. */
    suspend fun setPlaintextConsent(granted: Boolean)

    /** Runs a resolution attempt because the user asked for it. */
    suspend fun refreshNow(): EndpointRefreshOutcome

    /** Runs a resolution attempt only when the scheduled time has passed. */
    suspend fun refreshIfDue(): EndpointRefreshOutcome

    /**
     * Forces a resolution attempt right before a login or a reconnect and reports whether the
     * connection may start.
     *
     * This is the entry point required by section 9 of the specification. It is never skipped
     * because of a background scheduling delay.
     */
    suspend fun ensureFreshBeforeConnect(): ConnectGateResult

    /** Reloads settings and the persisted last validated endpoint, for example after onboarding. */
    suspend fun reload()

    /**
     * Marks the current resolved endpoint as stale so the next [ensureFreshBeforeConnect] re-resolves
     * it. Call this when a connection using that address is lost, so a changed dynamic (TXT/SRV)
     * port/IP is picked up on reconnect instead of the client retrying a dead address.
     */
    fun invalidate()
}
