package io.homeassistant.companion.android.common.data.authentication.impl

import io.homeassistant.companion.android.common.data.LocalStorage
import io.homeassistant.companion.android.common.data.authentication.AuthenticationRepository
import io.homeassistant.companion.android.common.data.authentication.AuthorizationException
import io.homeassistant.companion.android.common.data.authentication.SessionState
import io.homeassistant.companion.android.common.data.authentication.impl.AuthenticationService.Companion.SEGMENT_AUTH_TOKEN
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.firstUrlOrNull
import io.homeassistant.companion.android.common.util.MapAnySerializer
import io.homeassistant.companion.android.common.util.kotlinJsonMapper
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

class AuthenticationRepositoryImpl internal constructor(
    private val authenticationService: AuthenticationService,
    private val serverManager: ServerManager,
    private val serverId: Int,
    private val localStorage: LocalStorage,
    private val installId: String,
) : AuthenticationRepository {

    companion object {
        private const val PREF_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val PREF_BIOMETRIC_HOME_BYPASS_ENABLED = "biometric_home_bypass_enabled"
    }

    private suspend fun server(): Server {
        return checkNotNull(serverManager.getServer(serverId)) { "No server found for id $serverId" }
    }

    private suspend fun connectionStateProvider() = serverManager.connectionStateProvider(serverId)

    override suspend fun retrieveExternalAuthentication(forceRefresh: Boolean): String {
        ensureValidSession(forceRefresh)
        val server = server()
        return kotlinJsonMapper.encodeToString(
            MapAnySerializer,
            mapOf(
                "access_token" to server.session.accessToken,
                "expires_in" to server.session.expiresIn(),
            ),
        )
    }

    override suspend fun retrieveAccessToken(): String {
        ensureValidSession(false)
        return server().session.accessToken!!
    }

    override suspend fun revokeSession() {
        val server = server()
        val url = connectionStateProvider().urlFlow().firstUrlOrNull {
            "No URL available to revoke session"
        }?.toHttpUrlOrNull()
        if (!server.session.isComplete() || url == null) {
            Timber.e("Unable to revoke session.")
            return
        }
        if (server.version?.isAtLeast(2022, 9, 0) == true) {
            authenticationService.revokeToken(
                url.newBuilder().addPathSegments("auth/revoke").build(),
                server.session.refreshToken!!,
            )
        } else {
            authenticationService.revokeTokenLegacy(
                url.newBuilder().addPathSegments(SEGMENT_AUTH_TOKEN).build(),
                server.session.refreshToken!!,
                AuthenticationService.REVOKE_ACTION,
            )
        }
        serverManager.updateServer(
            server.copy(
                session = server.session.copy(
                    refreshToken = null,
                ),
            ),
        )
    }

    override suspend fun deletePreferences() {
        localStorage.remove("${serverId}_$PREF_BIOMETRIC_ENABLED")
        localStorage.remove("${serverId}_$PREF_BIOMETRIC_HOME_BYPASS_ENABLED")
    }

    override suspend fun getSessionState(): SessionState {
        val server = server()
        return if (server.session.isComplete() &&
            server.session.installId == installId &&
            server.connection.hasAtLeastOneUrl
        ) {
            SessionState.CONNECTED
        } else {
            SessionState.ANONYMOUS
        }
    }

    override suspend fun buildBearerToken(): String {
        ensureValidSession()
        return "Bearer " + server().session.accessToken
    }

    private suspend fun ensureValidSession(forceRefresh: Boolean = false) {
        val server = server()

        // Establishing or refreshing a session is a login, so the dynamic address is re-resolved
        // first. It has to happen before the URL is read below, otherwise the refresh request would
        // still go to the previous address.
        if (server.session.isExpired() || forceRefresh) {
            refreshDynamicAddressBeforeLogin()
        }

        val url = connectionStateProvider().urlFlow().firstUrlOrNull()?.toHttpUrlOrNull()
        if (!server.session.isComplete() || server.session.installId != installId || url == null) {
            Timber.e("Unable to ensure valid session.")
            throw AuthorizationException()
        }

        if (server.session.isExpired() || forceRefresh) {
            refreshSessionWithToken(url, server.session.refreshToken!!)
        }
    }

    /**
     * Re-runs the TXT/SRV resolution before a session is established.
     *
     * A failure here is never fatal on its own: the connection layer is the component that decides
     * whether the resulting address may be used, see
     * [io.homeassistant.companion.android.common.data.websocket.impl.WebSocketCoreImpl]. This method
     * only makes sure the address the request is about to use has just been re-checked.
     */
    private suspend fun refreshDynamicAddressBeforeLogin() {
        try {
            val gate = serverManager.endpointRepository(serverId).ensureFreshBeforeConnect()
            if (gate.isStale) {
                Timber.w("Using an address that could not be re-validated: %s", gate.message)
            } else if (!gate.mayConnect) {
                Timber.e("No validated address is available for the session: %s", gate.message)
            }
        } catch (e: IllegalStateException) {
            Timber.w(e, "Dynamic address check unavailable before login")
        }
    }

    private suspend fun refreshSessionWithToken(baseUrl: HttpUrl, refreshToken: String) {
        val server = server()
        return authenticationService.refreshToken(
            baseUrl.newBuilder().addPathSegments(SEGMENT_AUTH_TOKEN).build(),
            AuthenticationService.GRANT_TYPE_REFRESH,
            refreshToken,
            AuthenticationService.CLIENT_ID,
        ).let {
            if (it.isSuccessful) {
                val refreshedToken = it.body() ?: throw AuthorizationException()
                serverManager.updateServer(
                    server.copy(
                        session = ServerSessionInfo(
                            accessToken = refreshedToken.accessToken,
                            refreshToken = refreshToken,
                            tokenExpiration = System.currentTimeMillis() / 1000 + refreshedToken.expiresIn,
                            tokenType = refreshedToken.tokenType,
                            installId = installId,
                        ),
                    ),
                )
                return@let
            } else if (it.code() == 400 &&
                it.errorBody()?.string()?.contains("invalid_grant") == true
            ) {
                revokeSession()
            }
            throw AuthorizationException("Failed to refresh token", it.code(), it.errorBody())
        }
    }

    override suspend fun setLockEnabled(enabled: Boolean) =
        localStorage.putBoolean("${serverId}_$PREF_BIOMETRIC_ENABLED", enabled)

    override suspend fun setLockHomeBypassEnabled(enabled: Boolean) =
        localStorage.putBoolean("${serverId}_$PREF_BIOMETRIC_HOME_BYPASS_ENABLED", enabled)

    override suspend fun isLockEnabledRaw(): Boolean = localStorage.getBoolean("${serverId}_$PREF_BIOMETRIC_ENABLED")

    override suspend fun isLockHomeBypassEnabled(): Boolean =
        localStorage.getBoolean("${serverId}_$PREF_BIOMETRIC_HOME_BYPASS_ENABLED")

    override suspend fun isLockEnabled(): Boolean {
        val raw = isLockEnabledRaw()
        val bypass = isLockHomeBypassEnabled()
        return if (raw && bypass) {
            !connectionStateProvider().isInternal(requiresUrl = false)
        } else {
            raw
        }
    }
}
