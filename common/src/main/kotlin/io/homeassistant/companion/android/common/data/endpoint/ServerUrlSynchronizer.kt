package io.homeassistant.companion.android.common.data.endpoint

import io.homeassistant.companion.android.common.data.servers.ServerManager
import javax.inject.Provider
import timber.log.Timber

/**
 * Copies a validated endpoint into the server configuration the rest of the app reads.
 *
 * ## Why this is the integration point
 *
 * The client decides where to connect by reading [io.homeassistant.companion.android.database.server.ServerConnectionInfo]
 * from the database. `ServerConnectionStateProviderImpl` turns that row into a `Flow<UrlState>` and
 * every consumer (REST, WebSocket, the frontend) already reacts to changes of that flow through
 * `distinctUntilChanged`. Writing the resolved URL there therefore updates the **real** connection
 * target — no parallel "current URL" variable that could silently diverge from what is actually
 * used.
 *
 * ## Scope
 *
 * Only `externalUrl` is managed. `internalUrl`, `cloudUrl` and the home network detection settings
 * keep their own meaning and are never overwritten, because they express the user's explicit intent
 * about a different network context.
 *
 * Because the cloud URL takes precedence over the external URL when `useCloud` is set, dynamic mode
 * also clears `useCloud`. Otherwise a resolved address would be written but never used, which is
 * exactly the "saved a URL in the settings page but the connection never changed" failure the
 * specification warns about.
 */
internal class ServerUrlSynchronizer(
    private val serverManagerProvider: Provider<ServerManager>,
) {

    /** External URL currently stored for [serverId], or `null` when the server is unknown. */
    suspend fun currentExternalUrl(serverId: Int): String? =
        serverManagerProvider.get().getServer(serverId)?.connection?.externalUrl

    /**
     * Writes [endpoint] into the server configuration.
     *
     * @return true when the stored configuration actually changed, false when it was already up to
     *   date or the server no longer exists.
     */
    suspend fun applyExternalUrl(serverId: Int, endpoint: ResolvedEndpoint): Boolean {
        val manager = serverManagerProvider.get()
        val server = manager.getServer(serverId) ?: run {
            Timber.w("Cannot apply resolved endpoint, no server with id %d", serverId)
            return false
        }

        val connection = server.connection
        if (connection.externalUrl == endpoint.url && !connection.useCloud) {
            return false
        }

        manager.updateServer(
            server.copy(
                connection = connection.copy(
                    externalUrl = endpoint.url,
                    useCloud = false,
                ),
            ),
        )
        return true
    }
}
