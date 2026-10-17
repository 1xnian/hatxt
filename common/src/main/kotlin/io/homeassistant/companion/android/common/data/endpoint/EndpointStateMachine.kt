package io.homeassistant.companion.android.common.data.endpoint

/**
 * Lifecycle events of the dynamic endpoint.
 *
 * The specification requires these states to be distinguishable and not mixed up, both in the UI
 * and in the log. They are recorded through [EndpointEventRecorder].
 */
enum class EndpointLifecycleEvent {
    /** A TXT/SRV lookup produced a validated endpoint. */
    RESOLUTION_SUCCEEDED,

    /** A lookup failed. The previously validated endpoint, if any, stays in use. */
    RESOLUTION_FAILED,

    /** The resolved URL differs from the URL that was in use. */
    ADDRESS_CHANGED,

    /** The resolved URL equals the URL that was in use. */
    ADDRESS_UNCHANGED,

    /** The stored server configuration was updated with a new URL. */
    CONFIGURATION_UPDATED,

    /** A connection with the (new) URL succeeded. */
    CONNECTION_SUCCEEDED,

    /** A connection with the (new) URL failed. */
    CONNECTION_FAILED,

    /** A result arrived after a newer attempt had started and was discarded. */
    STALE_RESULT_DISCARDED,

    /** A manual refresh was requested by the user. */
    MANUAL_REFRESH_REQUESTED,

    /** A refresh was skipped because a lookup is already running. */
    REFRESH_SKIPPED_CONCURRENT,
}

/**
 * Non sensitive event sink. Implementations must never log credentials, tokens or full URLs that
 * contain a secret path.
 */
interface EndpointEventRecorder {
    fun record(event: EndpointLifecycleEvent, detail: String? = null)

    /** No-op recorder, used in tests and in code paths without a logger. */
    object NoOp : EndpointEventRecorder {
        override fun record(event: EndpointLifecycleEvent, detail: String?) = Unit
    }
}

/** Describes what a completed resolution attempt changed. */
sealed interface EndpointUpdate {
    /** Snapshot after the update. */
    val snapshot: EndpointSnapshot

    /** A newer attempt had already started, this result was discarded. */
    data class IgnoredStale(override val snapshot: EndpointSnapshot) : EndpointUpdate

    /** First successful resolution, there was no URL before. */
    data class Resolved(
        val endpoint: ResolvedEndpoint,
        override val snapshot: EndpointSnapshot,
    ) : EndpointUpdate

    /** Successful resolution that produced the same URL as before. */
    data class Unchanged(override val snapshot: EndpointSnapshot) : EndpointUpdate

    /** Successful resolution that changed the URL. */
    data class Changed(
        val previousUrl: String?,
        val endpoint: ResolvedEndpoint,
        override val snapshot: EndpointSnapshot,
    ) : EndpointUpdate

    /** Resolution failed, the previous endpoint (if any) stays in use and is marked stale. */
    data class Failed(
        val error: EndpointResolutionError,
        override val snapshot: EndpointSnapshot,
    ) : EndpointUpdate
}

/**
 * Holds the state of the dynamic endpoint and applies resolution results to it.
 *
 * Two invariants are enforced here, they are the reason this class exists:
 *
 * 1. **A failed or unvalidated address never replaces a working one.** [applyResult] only replaces
 *    [EndpointSnapshot.current] for a result whose [EndpointResolutionResult.success] is `true`,
 *    which by construction carries a validated endpoint. Failures only add an error and mark the
 *    current endpoint as stale.
 * 2. **An outdated result never overwrites a newer one.** Every attempt takes a generation token
 *    from [beginResolving]; [applyResult] discards results whose token is no longer the current
 *    one. This makes concurrent or out of order completions harmless.
 *
 * All methods are synchronous and thread safe so the class can be used from any dispatcher.
 */
class EndpointStateMachine(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var state = EndpointSnapshot()
    private var generation = 0L

    @Synchronized
    fun snapshot(): EndpointSnapshot = state

    /**
     * Adopts the persisted settings and seeds the snapshot with the currently stored URL.
     *
     * The stored URL is adopted as [EndpointSnapshot.current] so the UI can show what the client is
     * using right now, but it is deliberately **not** recorded as
     * [EndpointSnapshot.lastSuccessful]: a URL that only came out of the database was never
     * validated by the resolver, and the snapshot must not claim otherwise. Use
     * [restoreValidated] to restore an endpoint that a previous run did validate.
     */
    @Synchronized
    fun configure(settings: EndpointSettings, currentUrl: String? = null) {
        val normalizedCurrent = state.current ?: currentUrl?.let(::parseUrlOrNull)
        state = state.copy(
            mode = settings.mode,
            recordName = settings.activeRecordName.ifEmpty { null },
            current = normalizedCurrent,
            // A validated endpoint stays validated while it is still the current one.
            currentIsValidated = state.currentIsValidated && normalizedCurrent != null,
            nextRefreshAtMillis = if (settings.mode.usesDnsRecord && settings.periodicRefreshEnabled) {
                state.nextRefreshAtMillis ?: EndpointRefreshPolicy.nextRefreshAtMillis(
                    clock(),
                    settings.refreshIntervalSeconds,
                    0,
                )
            } else {
                null
            },
        )
    }

    /**
     * Restores an endpoint that a previous run resolved and validated.
     *
     * Both [EndpointSnapshot.current] and [EndpointSnapshot.lastSuccessful] are set, and the
     * timestamp is preserved so the UI can tell the user how old the fallback address is.
     */
    @Synchronized
    fun restoreValidated(endpoint: ResolvedEndpoint, resolvedAtMillis: Long) {
        state = state.copy(
            current = endpoint,
            lastSuccessful = endpoint,
            lastResolvedAtMillis = resolvedAtMillis,
            downgradedToHttp = endpoint.scheme == EndpointScheme.HTTP,
            currentIsValidated = true,
        )
    }

    /** Marks a new attempt and returns its generation token. */
    @Synchronized
    fun beginResolving(): Long {
        generation += 1
        state = state.copy(status = EndpointResolutionStatus.RESOLVING)
        return generation
    }

    /** True when [token] is still the newest attempt. */
    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation

    /** Applies a completed attempt and reports what changed. */
    @Synchronized
    fun applyResult(
        token: Long,
        result: EndpointResolutionResult,
        settings: EndpointSettings,
    ): EndpointUpdate {
        if (token != generation) {
            return EndpointUpdate.IgnoredStale(state)
        }

        val interval = settings.refreshIntervalSeconds
        val now = clock()
        val endpoint = result.endpoint

        if (result.success && endpoint != null) {
            val previousUrl = state.current?.url
            state = state.copy(
                status = EndpointResolutionStatus.SUCCESS,
                mode = settings.mode,
                recordName = settings.activeRecordName.ifEmpty { null },
                current = endpoint,
                lastSuccessful = endpoint,
                lastResolvedAtMillis = now,
                consecutiveFailures = 0,
                lastError = null,
                downgradedToHttp = endpoint.scheme == EndpointScheme.HTTP,
                currentIsValidated = true,
                recordTtlSeconds = result.recordTtlSeconds,
                nextRefreshAtMillis = if (settings.mode.usesDnsRecord && settings.periodicRefreshEnabled) {
                    EndpointRefreshPolicy.nextRefreshAtMillis(now, interval, 0)
                } else {
                    null
                },
            )
            return when {
                previousUrl == null -> EndpointUpdate.Resolved(endpoint, state)
                previousUrl == endpoint.url -> EndpointUpdate.Unchanged(state)
                else -> EndpointUpdate.Changed(previousUrl, endpoint, state)
            }
        }

        val error = result.error ?: EndpointResolutionError(
            reason = EndpointRejectionReason.NO_RECORDS_FOUND,
            message = "Resolution failed without an error description",
        )
        val failures = state.consecutiveFailures + 1
        state = state.copy(
            status = EndpointResolutionStatus.FAILED,
            mode = settings.mode,
            recordName = settings.activeRecordName.ifEmpty { null },
            // current and lastSuccessful are intentionally left untouched: the client keeps
            // connecting to the last validated address instead of losing it.
            lastError = error.message,
            consecutiveFailures = failures,
            nextRefreshAtMillis = if (settings.mode.usesDnsRecord && settings.periodicRefreshEnabled) {
                EndpointRefreshPolicy.nextRefreshAtMillis(
                    now,
                    interval,
                    failures,
                )
            } else {
                null
            },
        )
        return EndpointUpdate.Failed(error, state)
    }

    /** Records when the next refresh is planned, used by the foreground scheduler and the UI. */
    @Synchronized
    fun scheduleNextRefresh(atMillis: Long?) {
        state = state.copy(nextRefreshAtMillis = atMillis)
    }

    /** Clears the error after the user acknowledged it. Does not touch the endpoint itself. */
    @Synchronized
    fun clearError() {
        state = state.copy(lastError = null)
    }

    /** Resets the snapshot, for example when the user switches back to the fixed URL mode. */
    @Synchronized
    fun reset() {
        generation += 1
        state = EndpointSnapshot()
    }

    private fun parseUrlOrNull(url: String): ResolvedEndpoint? =
        EndpointUrlFactory.parseUrl(url)?.toEndpoint()
}
