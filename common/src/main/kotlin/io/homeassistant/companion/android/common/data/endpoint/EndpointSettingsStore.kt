package io.homeassistant.companion.android.common.data.endpoint

import io.homeassistant.companion.android.common.data.LocalStorage

/**
 * An endpoint that a previous run resolved and validated, together with the moment it was validated.
 *
 * @property endpoint the validated endpoint.
 * @property resolvedAtMillis wall clock time of the successful resolution. Displayed to the user so
 *   a fallback address is never presented as freshly checked.
 */
data class PersistedValidatedEndpoint(
    val endpoint: ResolvedEndpoint,
    val resolvedAtMillis: Long,
)

/**
 * Persistence for the dynamic endpoint feature.
 *
 * Everything is scoped to a server id so multiple servers keep separate configurations. All values
 * are stored through [LocalStorage], which the app already uses for other per-server preferences;
 * no new dependency and no schema migration is required.
 *
 * The store never persists credentials, tokens or paths that contain a secret. Only the fields that
 * are needed to display and reuse a validated address are written.
 */
interface EndpointSettingsStore {

    /** Reads the settings of [serverId], falling back to defaults when nothing is stored yet. */
    suspend fun settings(serverId: Int): EndpointSettings

    suspend fun saveSettings(serverId: Int, settings: EndpointSettings)

    /**
     * Whether the user already accepted an unencrypted connection to this server. Required before
     * an automatic HTTP downgrade, see [HttpsFallbackPolicy].
     */
    suspend fun hasPlaintextConsent(serverId: Int): Boolean

    suspend fun setPlaintextConsent(serverId: Int, granted: Boolean)

    /** Last endpoint that was actually validated, used as the fallback address. */
    suspend fun lastValidated(serverId: Int): PersistedValidatedEndpoint?

    suspend fun saveLastValidated(serverId: Int, validated: PersistedValidatedEndpoint)

    suspend fun clear(serverId: Int)
}

internal class LocalStorageEndpointSettingsStore(
    private val localStorage: LocalStorage,
) : EndpointSettingsStore {

    override suspend fun settings(serverId: Int): EndpointSettings {
        val defaults = EndpointSettings()
        return EndpointSettings(
            mode = localStorage.getString(key(serverId, KEY_MODE))
                ?.let { stored -> EndpointResolutionMode.entries.firstOrNull { it.name == stored } }
                ?: defaults.mode,
            txtRecordName = localStorage.getString(key(serverId, KEY_TXT_NAME)) ?: defaults.txtRecordName,
            srvRecordName = localStorage.getString(key(serverId, KEY_SRV_NAME)) ?: defaults.srvRecordName,
            refreshIntervalSeconds = localStorage.getLong(key(serverId, KEY_REFRESH_INTERVAL))
                ?.let(EndpointRefreshPolicy::sanitizeInterval)
                ?: defaults.refreshIntervalSeconds,
            periodicRefreshEnabled = localStorage.getBooleanOrNull(key(serverId, KEY_PERIODIC_ENABLED))
                ?: defaults.periodicRefreshEnabled,
            preferHttps = localStorage.getBooleanOrNull(key(serverId, KEY_PREFER_HTTPS)) ?: defaults.preferHttps,
            allowHttpFallback = localStorage.getBooleanOrNull(key(serverId, KEY_ALLOW_HTTP_FALLBACK))
                ?: defaults.allowHttpFallback,
            staleAddressPolicy = localStorage.getString(key(serverId, KEY_STALE_POLICY))
                ?.let { stored -> EndpointStalePolicy.entries.firstOrNull { it.name == stored } }
                ?: defaults.staleAddressPolicy,
            defaultPort = localStorage.getInt(key(serverId, KEY_DEFAULT_PORT))
                ?.takeIf(EndpointUrlFactory::isValidPort)
                ?: defaults.defaultPort,
            useFixedHost = localStorage.getBooleanOrNull(key(serverId, KEY_FIXED_HOST_ENABLED))
                ?: defaults.useFixedHost,
            fixedHost = localStorage.getString(key(serverId, KEY_FIXED_HOST))?.trim()
                ?: defaults.fixedHost,
            bypassSystemDnsCache = localStorage.getBooleanOrNull(key(serverId, KEY_BYPASS_SYSTEM_DNS))
                ?: defaults.bypassSystemDnsCache,
        )
    }

    override suspend fun saveSettings(serverId: Int, settings: EndpointSettings) {
        val sanitized = settings.copy(
            refreshIntervalSeconds = EndpointRefreshPolicy.sanitizeInterval(settings.refreshIntervalSeconds),
            defaultPort = settings.defaultPort.takeIf(EndpointUrlFactory::isValidPort)
                ?: EndpointSettings.DEFAULT_HOME_ASSISTANT_PORT,
        )
        localStorage.putString(key(serverId, KEY_MODE), sanitized.mode.name)
        localStorage.putString(key(serverId, KEY_TXT_NAME), sanitized.txtRecordName.trim())
        localStorage.putString(key(serverId, KEY_SRV_NAME), sanitized.srvRecordName.trim())
        localStorage.putLong(key(serverId, KEY_REFRESH_INTERVAL), sanitized.refreshIntervalSeconds)
        localStorage.putBoolean(key(serverId, KEY_PERIODIC_ENABLED), sanitized.periodicRefreshEnabled)
        localStorage.putBoolean(key(serverId, KEY_PREFER_HTTPS), sanitized.preferHttps)
        localStorage.putBoolean(key(serverId, KEY_ALLOW_HTTP_FALLBACK), sanitized.allowHttpFallback)
        localStorage.putString(key(serverId, KEY_STALE_POLICY), sanitized.staleAddressPolicy.name)
        localStorage.putInt(key(serverId, KEY_DEFAULT_PORT), sanitized.defaultPort)
        localStorage.putBoolean(key(serverId, KEY_FIXED_HOST_ENABLED), sanitized.useFixedHost)
        localStorage.putString(key(serverId, KEY_FIXED_HOST), sanitized.fixedHost.trim())
        localStorage.putBoolean(key(serverId, KEY_BYPASS_SYSTEM_DNS), sanitized.bypassSystemDnsCache)
    }

    override suspend fun hasPlaintextConsent(serverId: Int): Boolean =
        localStorage.getBooleanOrNull(key(serverId, KEY_PLAINTEXT_CONSENT)) ?: false

    override suspend fun setPlaintextConsent(serverId: Int, granted: Boolean) =
        localStorage.putBoolean(key(serverId, KEY_PLAINTEXT_CONSENT), granted)

    override suspend fun lastValidated(serverId: Int): PersistedValidatedEndpoint? {
        val url = localStorage.getString(key(serverId, KEY_LAST_VALIDATED_URL)) ?: return null
        val resolvedAt = localStorage.getLong(key(serverId, KEY_LAST_VALIDATED_AT)) ?: return null
        val parsed = EndpointUrlFactory.parseUrl(url) ?: return null
        return PersistedValidatedEndpoint(parsed.toEndpoint(), resolvedAt)
    }

    override suspend fun saveLastValidated(serverId: Int, validated: PersistedValidatedEndpoint) {
        localStorage.putString(key(serverId, KEY_LAST_VALIDATED_URL), validated.endpoint.url)
        localStorage.putLong(key(serverId, KEY_LAST_VALIDATED_AT), validated.resolvedAtMillis)
    }

    override suspend fun clear(serverId: Int) {
        KEY_SUFFIXES.forEach { localStorage.remove(key(serverId, it)) }
    }

    private fun key(serverId: Int, suffix: String) = "endpoint_${serverId}_$suffix"

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_TXT_NAME = "txt_name"
        const val KEY_SRV_NAME = "srv_name"
        const val KEY_REFRESH_INTERVAL = "refresh_interval_seconds"
        const val KEY_PERIODIC_ENABLED = "periodic_enabled"
        const val KEY_PREFER_HTTPS = "prefer_https"
        const val KEY_ALLOW_HTTP_FALLBACK = "allow_http_fallback"
        const val KEY_STALE_POLICY = "stale_policy"
        const val KEY_DEFAULT_PORT = "default_port"
        const val KEY_FIXED_HOST_ENABLED = "fixed_host_enabled"
        const val KEY_FIXED_HOST = "fixed_host"
        const val KEY_BYPASS_SYSTEM_DNS = "bypass_system_dns"
        const val KEY_PLAINTEXT_CONSENT = "plaintext_consent"
        const val KEY_LAST_VALIDATED_URL = "last_validated_url"
        const val KEY_LAST_VALIDATED_AT = "last_validated_at"

        val KEY_SUFFIXES = listOf(
            KEY_MODE,
            KEY_TXT_NAME,
            KEY_SRV_NAME,
            KEY_REFRESH_INTERVAL,
            KEY_PERIODIC_ENABLED,
            KEY_PREFER_HTTPS,
            KEY_ALLOW_HTTP_FALLBACK,
            KEY_STALE_POLICY,
            KEY_DEFAULT_PORT,
            KEY_FIXED_HOST_ENABLED,
            KEY_FIXED_HOST,
            KEY_BYPASS_SYSTEM_DNS,
            KEY_PLAINTEXT_CONSENT,
            KEY_LAST_VALIDATED_URL,
            KEY_LAST_VALIDATED_AT,
        )
    }
}
