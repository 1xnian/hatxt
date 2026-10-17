package io.homeassistant.companion.android.common.data.endpoint

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs resolution attempts for one server and keeps the endpoint state in sync.
 *
 * The engine is deliberately the only place that mutates the endpoint state, which is what makes
 * the two concurrency guarantees enforceable:
 *
 * * **Single flight.** Every attempt is serialised through [mutex]. A request that arrives while an
 *   attempt is running is dropped with [EndpointRefreshOutcome.SkippedConcurrent] instead of
 *   starting a second DNS lookup. The manual refresh button, the scheduler, the network recovery
 *   hook and the pre-connect gate can therefore all fire at once without producing parallel
 *   queries.
 * * **Newest wins.** Each attempt takes a generation token from [EndpointStateMachine.beginResolving]
 *   and the result is discarded when a newer attempt started in the meantime, so a slow lookup can
 *   never overwrite the outcome of a faster, later one.
 *
 * A failed attempt leaves the current address untouched and only records the error, which is the
 * behaviour required by the specification: an unvalidated address never replaces a working one.
 */
internal class EndpointResolutionEngine @Suppress("LongParameterList") constructor(
    private val serverId: Int,
    private val settingsStore: EndpointSettingsStore,
    private val synchronizer: ServerUrlSynchronizer,
    private val resolver: EndpointResolver,
    private val connectivity: EndpointConnectivity,
    private val eventRecorder: EndpointEventRecorder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val machine = EndpointStateMachine(clock)
    private val mutex = Mutex()
    private val state = MutableStateFlow(EndpointSnapshot())

    @Volatile
    private var initialized = false

    val snapshotFlow: StateFlow<EndpointSnapshot> get() = state.asStateFlow()

    fun snapshot(): EndpointSnapshot = machine.snapshot()

    /**
     * Runs [block] on [ioDispatcher] regardless of the caller's dispatcher.
     *
     * Every `settingsStore`/`synchronizer` access performs disk or database I/O (SharedPreferences
     * reads/writes, Room writes to the server table). The engine is invoked from many places — the
     * periodic foreground refresher, the `WorkManager` worker, the authentication token refresh and
     * the WebSocket connect/reconnect path — and some of those call sites run on the main thread
     * (e.g. a reconnect launched from a `viewModelScope`/lifecycle coroutine). Because the debug
     * build enables a process-wide StrictMode **thread** policy with `FailFast` on the main thread, a
     * disk read on the main thread kills the process. Routing all of the engine's I/O through
     * [ioDispatcher] here makes the whole endpoint module StrictMode-clean no matter who calls it.
     */
    private suspend inline fun <T> io(block: suspend () -> T): T =
        withContext(ioDispatcher) { block() }

    /** Loads settings and the persisted last validated endpoint. Runs once per process. */
    suspend fun initialize() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            adoptPersistedState()
            initialized = true
        }
    }

    /** Re-reads the persisted state. Used after onboarding changed the configuration. */
    suspend fun reload() {
        mutex.withLock {
            initialized = false
            adoptPersistedState()
            initialized = true
        }
    }

    /** Runs a resolution attempt requested by the user or by a scheduler. */
    suspend fun runResolution(source: EndpointRefreshSource): EndpointRefreshOutcome {
        initialize()
        val settings = io { settingsStore.settings(serverId) }
        if (!settings.mode.usesDnsRecord || settings.activeRecordName.isBlank()) {
            return EndpointRefreshOutcome.SkippedNotConfigured
        }
        // A periodic refresh while offline would only burn battery, the pre-connect gate still runs
        // so the user gets a real error message instead of a silent skip.
        if (source != EndpointRefreshSource.PRE_CONNECT && !connectivity.isNetworkAvailable()) {
            eventRecorder.record(EndpointLifecycleEvent.RESOLUTION_FAILED, "$source skipped: device is offline")
            return EndpointRefreshOutcome.SkippedOffline
        }
        if (!mutex.tryLock()) {
            eventRecorder.record(EndpointLifecycleEvent.REFRESH_SKIPPED_CONCURRENT, source.name)
            return EndpointRefreshOutcome.SkippedConcurrent
        }
        return try {
            execute(settings, source)
        } finally {
            mutex.unlock()
        }
    }

    /** Runs a resolution attempt only when the planned time has passed. */
    suspend fun refreshIfDue(source: EndpointRefreshSource): EndpointRefreshOutcome {
        initialize()
        if (!EndpointRefreshPolicy.isDue(clock(), machine.snapshot().nextRefreshAtMillis)) {
            return EndpointRefreshOutcome.NotDue
        }
        return runResolution(source)
    }

    /**
     * Hard gate in front of a login or a reconnect.
     *
     * A resolution that is younger than [EndpointRefreshPolicy.PRE_CONNECT_FRESHNESS_SECONDS] is
     * reused so tapping "login" twice does not fire two lookups. Every other case performs a real
     * lookup; a background scheduling delay is never mistaken for a completed pre-login check.
     */
    suspend fun preConnect(): ConnectGateResult {
        initialize()
        val settings = io { settingsStore.settings(serverId) }

        if (settings.mode == EndpointResolutionMode.FIXED) {
            return ConnectGateResult(
                decision = ConnectDecision.PROCEED,
                url = io { synchronizer.currentExternalUrl(serverId) },
            )
        }
        if (settings.activeRecordName.isBlank()) {
            return ConnectGateResult(
                decision = ConnectDecision.BLOCKED,
                message = "No ${settings.mode} record name is configured, so no address can be resolved",
            )
        }

        val current = machine.snapshot()
        val ageMillis = current.lastResolvedAtMillis?.let { clock() - it }
        if (current.currentIsValidated && ageMillis != null &&
            ageMillis in 0 until EndpointRefreshPolicy.PRE_CONNECT_FRESHNESS_SECONDS * 1000L
        ) {
            return ConnectGateResult(
                decision = ConnectDecision.PROCEED,
                url = current.current?.url,
                resolvedAtMillis = current.lastResolvedAtMillis,
            )
        }

        return when (val outcome = runResolution(EndpointRefreshSource.PRE_CONNECT)) {
            is EndpointRefreshOutcome.Applied -> when (val update = outcome.update) {
                is EndpointUpdate.Resolved ->
                    EndpointConnectGate.proceed(update.snapshot, addressChanged = true)

                is EndpointUpdate.Changed ->
                    EndpointConnectGate.proceed(update.snapshot, addressChanged = true)

                is EndpointUpdate.Unchanged ->
                    EndpointConnectGate.proceed(update.snapshot, addressChanged = false)

                is EndpointUpdate.Failed -> gateFailure(settings, update.error.message)
                is EndpointUpdate.IgnoredStale -> gateFailure(
                    settings,
                    "A newer resolution attempt superseded this one",
                )
            }

            EndpointRefreshOutcome.SkippedConcurrent -> gateFailure(
                settings,
                "Another address refresh is already running",
            )

            EndpointRefreshOutcome.SkippedOffline -> gateFailure(settings, "The device is offline")
            EndpointRefreshOutcome.NotDue -> gateFailure(settings, "The address is out of date")
            EndpointRefreshOutcome.SkippedNotConfigured -> ConnectGateResult(
                decision = ConnectDecision.BLOCKED,
                message = "The dynamic address is not configured for this server",
            )
        }
    }

    private suspend fun adoptPersistedState() {
        val settings = io { settingsStore.settings(serverId) }
        val currentUrl = io { synchronizer.currentExternalUrl(serverId) }
        machine.configure(settings, currentUrl)

        // Only an endpoint that a previous run actually validated is allowed to become the
        // "last known good" address. A URL read back from the database is not.
        io { settingsStore.lastValidated(serverId) }?.let { persisted ->
            if (persisted.endpoint.url == currentUrl) {
                machine.restoreValidated(persisted.endpoint, persisted.resolvedAtMillis)
            }
        }
        publish()
    }

    private suspend fun execute(
        settings: EndpointSettings,
        source: EndpointRefreshSource,
    ): EndpointRefreshOutcome {
        val token = machine.beginResolving()
        publish()

        val consent = io { settingsStore.hasPlaintextConsent(serverId) }
        val result = withContext(ioDispatcher) {
            resolver.resolve(settings, plaintextConsent = consent)
        }

        if (!machine.isCurrent(token)) {
            eventRecorder.record(
                EndpointLifecycleEvent.STALE_RESULT_DISCARDED,
                "${source.name} finished after a newer attempt had started",
            )
            return EndpointRefreshOutcome.Applied(EndpointUpdate.IgnoredStale(machine.snapshot()))
        }

        val update = machine.applyResult(token, result, settings)
        result.endpoint?.let { validated ->
            io { settingsStore.saveLastValidated(serverId, PersistedValidatedEndpoint(validated, clock())) }
        }
        publish()
        applyAddressChange(update)
        return EndpointRefreshOutcome.Applied(update)
    }

    /**
     * Writes a newly resolved address into the server configuration and records what happened.
     *
     * The five states the specification asks not to mix up are all recorded distinctly:
     * resolution success, address change, configuration update, and (from the connection layer)
     * connection success or failure.
     */
    private suspend fun applyAddressChange(update: EndpointUpdate) {
        when (update) {
            is EndpointUpdate.Resolved -> {
                io { synchronizer.applyExternalUrl(serverId, update.endpoint) }
                eventRecorder.record(
                    EndpointLifecycleEvent.ADDRESS_CHANGED,
                    "no address was in use -> ${update.endpoint.url}",
                )
                eventRecorder.record(EndpointLifecycleEvent.CONFIGURATION_UPDATED, update.endpoint.url)
            }

            is EndpointUpdate.Changed -> {
                io { synchronizer.applyExternalUrl(serverId, update.endpoint) }
                eventRecorder.record(
                    EndpointLifecycleEvent.ADDRESS_CHANGED,
                    "${update.previousUrl} -> ${update.endpoint.url}",
                )
                eventRecorder.record(EndpointLifecycleEvent.CONFIGURATION_UPDATED, update.endpoint.url)
            }

            is EndpointUpdate.Unchanged ->
                eventRecorder.record(EndpointLifecycleEvent.ADDRESS_UNCHANGED, update.snapshot.current?.url)

            // Nothing is written on failure: the previous address stays in the configuration.
            is EndpointUpdate.Failed -> Unit
            is EndpointUpdate.IgnoredStale -> Unit
        }
    }

    /**
     * Builds the answer when the fresh lookup did not produce a validated address.
     *
     * The last **validated** address is the only fallback that is ever offered; the timestamp
     * travels with it so the UI can say how old it is. It is never described as freshly checked.
     */
    private suspend fun gateFailure(settings: EndpointSettings, reason: String): ConnectGateResult {
        val currentSnapshot = machine.snapshot()
        val lastValidated = currentSnapshot.lastSuccessful?.let {
            EndpointConnectGate.ValidatedAddress(it.url, currentSnapshot.lastResolvedAtMillis)
        } ?: io { settingsStore.lastValidated(serverId) }?.let {
            EndpointConnectGate.ValidatedAddress(it.endpoint.url, it.resolvedAtMillis)
        }

        return EndpointConnectGate.failure(
            policy = settings.staleAddressPolicy,
            reason = reason,
            lastValidated = lastValidated,
            nowMillis = clock(),
        )
    }

    private fun publish() {
        state.value = machine.snapshot()
    }
}
