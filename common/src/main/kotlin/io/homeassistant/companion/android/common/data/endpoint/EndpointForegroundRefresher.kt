package io.homeassistant.companion.android.common.data.endpoint

import io.homeassistant.companion.android.common.data.network.NetworkChangeObserver
import io.homeassistant.companion.android.common.data.servers.ServerManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Refreshes the dynamic server addresses while the app is in the foreground.
 *
 * ## Why a single ticker instead of one timer per server
 *
 * The refresher is an application scoped singleton and runs **one** loop for every server. Starting
 * and stopping it is idempotent, so a configuration change that recreates the UI cannot end up with
 * two competing timers — the failure mode the specification calls out explicitly.
 *
 * The loop wakes up on a fixed tick and asks each repository whether its own schedule is due. That
 * keeps the per-server interval, the failure backoff and the "never more than one lookup at a time"
 * rule in one place, and avoids the drift of N independently scheduled timers.
 *
 * A tick that is not due performs no network access at all.
 *
 * ## Network recovery
 *
 * Losing and regaining connectivity is observed through [NetworkChangeObserver]. When the network
 * comes back the loop stops waiting for the next tick and re-checks immediately, because a lookup
 * that failed while offline must not leave the app without a fresh address until the interval
 * elapses.
 */
@Singleton
class EndpointForegroundRefresher @Inject constructor(
    private val serverManager: ServerManager,
    private val networkChangeObserver: NetworkChangeObserver,
    private val connectivity: EndpointConnectivity,
) {

    // The loop reads SharedPreferences (`settingsStore.settings()`), consults the Room-backed
    // server list and performs DNS/HTTPS lookups. `Dispatchers.Default` is the CPU-bound pool and
    // doing I/O there is both a scheduling mistake and a StrictMode hazard: the debug build installs
    // a thread policy with `detectAll()`, and every thread of the process — not just the main one —
    // is subject to it. A disk read on a Default worker is reported back to the app and, with the
    // FailFast handler installed, kills the process.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Starts the loop. Redundant calls are ignored. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        Timber.d("Starting foreground endpoint refresher")
        job = scope.launch {
            launch { observeNetworkRecovery() }
            while (isActive) {
                refreshDueServers(EndpointRefreshSource.PERIODIC)
                delay(TICK_INTERVAL_MILLIS)
            }
        }
    }

    /** Stops the loop. Called when the app goes to the background. */
    @Synchronized
    fun stop() {
        Timber.d("Stopping foreground endpoint refresher")
        job?.cancel()
        job = null
    }

    /**
     * One immediate, forced pass, used when the app returns to the foreground.
     *
     * Unlike the periodic tick this bypasses each server's `isDue` schedule, so a TXT/SRV record
     * that changed while the app was backgrounded is re-read straight away instead of waiting for the
     * next interval. For a dynamic address (port that can change at any time) this is what makes
     * "close the app and reopen it" actually pick up the new port.
     */
    fun refreshNow() {
        scope.launch {
            val servers = runCatching { serverManager.servers() }.getOrElse { return@launch }
            servers.forEach { server ->
                runCatching {
                    serverManager.endpointRepository(server.id).refreshNow()
                }.onFailure { Timber.w(it, "Forced endpoint refresh of server %d failed", server.id) }
            }
        }
    }

    private suspend fun observeNetworkRecovery() {
        var wasAvailable = true
        networkChangeObserver.observerNetworkChange.collect {
            val available = isAnyNetworkAvailable()
            if (available && !wasAvailable) {
                Timber.i("Network is back, re-checking the dynamic server addresses")
                refreshDueServers(EndpointRefreshSource.NETWORK_RECOVERY)
            }
            wasAvailable = available
        }
    }

    private suspend fun refreshDueServers(source: EndpointRefreshSource) {
        val servers = runCatching { serverManager.servers() }.getOrElse {
            Timber.w(it, "Could not list the servers for the endpoint refresh")
            return
        }
        servers.forEach { server ->
            runCatching {
                val repository = serverManager.endpointRepository(server.id)
                when (val outcome = repository.refreshIfDue()) {
                    is EndpointRefreshOutcome.Applied -> Timber.d(
                        "Foreground endpoint refresh of server %d: %s",
                        server.id,
                        outcome.update::class.simpleName,
                    )

                    else -> Unit
                }
            }.onFailure { Timber.w(it, "Endpoint refresh of server %d failed", server.id) }
        }
    }

    private fun isAnyNetworkAvailable(): Boolean = runCatching {
        connectivity.isNetworkAvailable()
    }.getOrDefault(true)

    private companion object {
        /**
         * How often the loop re-evaluates the per-server schedules. Deliberately much shorter than
         * any supported interval so a 30 second configuration is actually honoured, while a tick
         * that is not due costs nothing but a comparison.
         */
        const val TICK_INTERVAL_MILLIS = 5_000L
    }
}
