package io.homeassistant.companion.android.common.data.endpoint

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.data.LocalStorage
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.di.qualifiers.NamedEndpointStorage
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Minimal connectivity check used to avoid pointless DNS queries while the device is offline.
 *
 * Kept as an interface so the resolution engine can be unit tested without Android.
 */
fun interface EndpointConnectivity {
    fun isNetworkAvailable(): Boolean

    companion object {
        /** Always-online implementation, used where connectivity cannot be observed. */
        val AlwaysOnline = EndpointConnectivity { true }
    }
}

/** [EndpointConnectivity] backed by [ConnectivityManager]. */
internal class AndroidEndpointConnectivity(
    @ApplicationContext private val context: Context,
) : EndpointConnectivity {

    override fun isNetworkAvailable(): Boolean {
        val manager = context.getSystemService<ConnectivityManager>() ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/**
 * Logs endpoint lifecycle events.
 *
 * Only non-sensitive data is written: record names, URLs of the server the user configured and the
 * coarse failure classification. Credentials, tokens and query strings never reach the log.
 */
internal class TimberEndpointEventRecorder(
    private val serverId: Int,
) : EndpointEventRecorder {

    override fun record(event: EndpointLifecycleEvent, detail: String?) {
        Timber.i("[endpoint][server %d] %s%s", serverId, event.name, detail?.let { ": $it" }.orEmpty())
    }
}

/** Per-server creation of [EndpointRepository], mirroring the other `*Factory` types in the app. */
internal class EndpointRepositoryFactory @Inject constructor(
    @NamedEndpointStorage private val localStorage: LocalStorage,
    private val dnsClient: DnsClient,
    private val bypassDnsFactory: CacheBypassDnsFactory,
    private val probe: EndpointProbe,
    private val connectivity: EndpointConnectivity,
    // A Provider avoids a construction cycle: the repository writes the resolved URL back into the
    // server configuration through the ServerManager.
    private val serverManagerProvider: Provider<ServerManager>,
) {

    fun settingsStore(): EndpointSettingsStore = LocalStorageEndpointSettingsStore(localStorage)

    fun create(serverId: Int): EndpointRepositoryImpl {
        val store = settingsStore()
        val recorder = TimberEndpointEventRecorder(serverId)
        return EndpointRepositoryImpl(
            serverId = serverId,
            engine = EndpointResolutionEngine(
                serverId = serverId,
                settingsStore = store,
                synchronizer = ServerUrlSynchronizer(serverManagerProvider),
                resolver = EndpointResolver(
                    dnsClient = dnsClient,
                    probe = probe,
                    eventRecorder = recorder,
                    bypassClientFactory = bypassDnsFactory::create,
                ),
                connectivity = connectivity,
                eventRecorder = recorder,
            ),
            settingsStore = store,
        )
    }
}

/**
 * Implementation of [EndpointRepository].
 *
 * It is a thin facade: all resolution logic lives in [EndpointResolutionEngine] so this class only
 * has to translate the public API onto the engine and expose the observable snapshot.
 */
internal class EndpointRepositoryImpl(
    private val serverId: Int,
    private val engine: EndpointResolutionEngine,
    private val settingsStore: EndpointSettingsStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EndpointRepository {

    override val snapshot: Flow<EndpointSnapshot> get() = engine.snapshotFlow

    override suspend fun settings(): EndpointSettings = withContext(ioDispatcher) {
        settingsStore.settings(serverId)
    }

    override suspend fun updateSettings(settings: EndpointSettings) {
        withContext(ioDispatcher) {
            settingsStore.saveSettings(serverId, settings)
        }
        // Re-arm the schedule immediately so a changed interval takes effect without a restart.
        engine.reload()
    }

    override fun invalidate() = engine.invalidate()

    override suspend fun setPlaintextConsent(granted: Boolean) {
        withContext(ioDispatcher) {
            settingsStore.setPlaintextConsent(serverId, granted)
        }
    }

    override suspend fun refreshNow(): EndpointRefreshOutcome =
        engine.runResolution(EndpointRefreshSource.MANUAL)

    override suspend fun refreshIfDue(): EndpointRefreshOutcome =
        engine.refreshIfDue(EndpointRefreshSource.PERIODIC)

    override suspend fun ensureFreshBeforeConnect(): ConnectGateResult = engine.preConnect()

    override suspend fun reload() = engine.reload()
}
