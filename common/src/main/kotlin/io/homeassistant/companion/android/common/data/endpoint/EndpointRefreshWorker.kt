package io.homeassistant.companion.android.common.data.endpoint

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.data.servers.ServerManager
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Refreshes the dynamic server addresses while the app is not running.
 *
 * ## Android scheduling limits, stated honestly
 *
 * `WorkManager` refuses a periodic interval below **15 minutes**. A user who selects 30 seconds
 * therefore only gets 30 second refreshes while the app is in the foreground; in the background the
 * system runs this worker at its own discretion, subject to Doze and App Standby. The app never
 * claims otherwise: [EndpointRefreshPolicy.isBackgroundIntervalLimited] tells the settings screen to
 * warn the user, and the pre-connect check guarantees a fresh lookup before any login regardless of
 * when this worker last ran.
 */
class EndpointRefreshWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface EndpointRefreshWorkerEntryPoint {
        fun serverManager(): ServerManager
    }

    override suspend fun doWork(): Result {
        val serverManager = entryPoint().serverManager()
        val servers = serverManager.servers()
        if (servers.isEmpty()) {
            Timber.d("Skipping endpoint refresh, no server is registered")
            return Result.success()
        }

        servers.forEach { server ->
            try {
                val repository = serverManager.endpointRepository(server.id)
                when (val outcome = repository.refreshIfDue()) {
                    is EndpointRefreshOutcome.Applied -> Timber.i(
                        "Background endpoint refresh of server %d finished: %s",
                        server.id,
                        outcome.update::class.simpleName,
                    )

                    EndpointRefreshOutcome.NotDue -> Unit
                    EndpointRefreshOutcome.SkippedConcurrent -> Timber.d("A refresh was already running")
                    EndpointRefreshOutcome.SkippedNotConfigured -> Unit
                    EndpointRefreshOutcome.SkippedOffline -> Timber.d("Device is offline, refresh skipped")
                }
            } catch (e: IllegalStateException) {
                // A server can be removed while the worker is iterating, that is not a failure.
                Timber.w(e, "Could not refresh the endpoint of server %d", server.id)
            }
        }
        return Result.success()
    }

    private fun entryPoint(): EndpointRefreshWorkerEntryPoint =
        EntryPointAccessors.fromApplication(context, EndpointRefreshWorkerEntryPoint::class.java)

    companion object {
        private const val UNIQUE_WORK_NAME = "EndpointRefreshWorker"

        /** Enqueues the periodic work, honouring the interval the user configured. */
        fun start(context: Context, requestedIntervalSeconds: Long) {
            val intervalMinutes = EndpointRefreshPolicy.backgroundIntervalMinutes(requestedIntervalSeconds)
            val request = PeriodicWorkRequestBuilder<EndpointRefreshWorker>(
                intervalMinutes,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)

            Timber.i("Scheduled endpoint refresh every %d minute(s)", intervalMinutes)
        }

        /** Cancels the periodic work, used when the user turns the background refresh off. */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
