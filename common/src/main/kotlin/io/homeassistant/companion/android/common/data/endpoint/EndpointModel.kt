package io.homeassistant.companion.android.common.data.endpoint

/**
 * How the app determines the server address that it connects to.
 *
 * The mode is user configurable. [FIXED] keeps the legacy behaviour: whatever URL the user typed is
 * used as is and no DNS record is queried.
 */
enum class EndpointResolutionMode {
    /** Use a fixed, user provided URL. No DNS lookup is performed. */
    FIXED,

    /** Resolve the address from a DNS TXT record. */
    TXT,

    /** Resolve the address from a DNS SRV record. */
    SRV,
    ;

    val usesDnsRecord: Boolean get() = this == TXT || this == SRV
}

/** Lifecycle of a single resolution attempt. */
enum class EndpointResolutionStatus {
    /** Nothing has been attempted yet. */
    IDLE,

    /** A DNS lookup / validation is currently running. */
    RESOLVING,

    /** The last attempt produced a validated endpoint. */
    SUCCESS,

    /** The last attempt failed. A previously validated endpoint may still be in use. */
    FAILED,
}

/**
 * Transport used for the resolved endpoint.
 *
 * [HTTPS] is always preferred. [HTTP] is only ever used after the checks in
 * [HttpsFallbackPolicy] allowed it.
 */
enum class EndpointScheme(val urlScheme: String) {
    HTTPS("https"),
    HTTP("http"),
    ;

    val isSecure: Boolean get() = this == HTTPS

    /** The well known port for this scheme, used to decide whether a port must be rendered. */
    val defaultPort: Int get() = if (this == HTTPS) 443 else 80

    companion object {
        fun fromUrlScheme(value: String): EndpointScheme? =
            entries.firstOrNull { it.urlScheme.equals(value, ignoreCase = true) }
    }
}

/** DNS record types the endpoint resolver needs. The numeric codes match the wire format. */
enum class DnsRecordType(val code: Int) {
    A(1),
    TXT(16),
    AAAA(28),
    SRV(33),
    ;

    companion object {
        fun fromCode(code: Int): DnsRecordType? = entries.firstOrNull { it.code == code }
    }
}

/** A single answer returned by a DNS resolver. */
data class DnsAnswer(
    val type: DnsRecordType,
    /** Raw record payload. TXT values are already unquoted/unescaped, SRV values stay raw. */
    val value: String,
    /** Record name the answer belongs to when the resolver reports it. */
    val name: String? = null,
    val ttlSeconds: Long? = null,
)

/** Reason why a candidate or a whole resolution attempt was rejected. */
enum class EndpointRejectionReason {
    EMPTY_RECORD,
    UNPARSEABLE_RECORD,
    MISSING_HOST,
    MISSING_PORT,
    INVALID_PORT,
    INVALID_IP_ADDRESS,
    INVALID_HOSTNAME,
    UNSUPPORTED_SCHEME,
    INVALID_URL,
    DUPLICATE_RECORD,
    SRV_SERVICE_UNAVAILABLE,
    SRV_TARGET_NOT_RESOLVABLE,
    SRV_ALL_CANDIDATES_INVALID,

    /** DNS could not be reached at all (offline, DNS server error, ...). */
    DNS_LOOKUP_FAILED,
    NO_RECORDS_FOUND,

    /** HTTPS could not be confirmed and the plaintext fallback needs the user's consent. */
    PLAINTEXT_CONFIRMATION_REQUIRED,

    /** A TLS service exists but its certificate is not trusted. Verification is never disabled. */
    TLS_CERTIFICATE_NOT_TRUSTED,
}

/** A record that was read from DNS but could not be turned into a usable endpoint. */
data class RejectedRecord(val record: String, val reason: EndpointRejectionReason, val detail: String? = null)

/**
 * Result of interpreting TXT/SRV records into candidate endpoints.
 *
 * @property candidates usable candidates, ordered by preference. Never empty when [isUsable] is true.
 * @property rejected records that were skipped, with the reason. Used for diagnostics in the UI.
 */
data class CandidateParseResult(
    val candidates: List<EndpointCandidate>,
    val rejected: List<RejectedRecord> = emptyList(),
) {
    val isUsable: Boolean get() = candidates.isNotEmpty()
}

/**
 * An endpoint candidate that has been syntactically validated but not yet probed.
 *
 * @property host host that ends up in the URL. May be a hostname or a literal IP.
 * @property port TCP port. Always within 1..65535.
 * @property scheme scheme requested by the record, `null` when the resolver should decide.
 * @property path optional path that came from a full URL, without leading slash.
 * @property sourceRecord the DNS record value this candidate originates from.
 */
data class EndpointCandidate(
    val host: String,
    val port: Int,
    val scheme: EndpointScheme? = null,
    val path: String? = null,
    val sourceRecord: String? = null,
)

/**
 * An endpoint that passed syntax validation, DNS resolution and (if requested) a reachability probe.
 *
 * @property scheme transport to use.
 * @property host host name used inside the URL. For SRV this is the target host, not the query name.
 * @property port TCP port.
 * @property ip the IP the host resolved to. Kept for diagnostics and for IP pinning scenarios.
 * @property path optional path component, stored without a leading slash.
 * @property downgradeReason when [scheme] is [EndpointScheme.HTTP], why HTTPS was not used.
 */
data class ResolvedEndpoint(
    val scheme: EndpointScheme,
    val host: String,
    val port: Int,
    val ip: String? = null,
    val path: String? = null,
    val downgradeReason: String? = null,
) {
    /** Full `scheme://host:port/path` URL. Built and validated by [EndpointUrlFactory]. */
    val url: String get() = EndpointUrlFactory.buildUrl(this)

    /** `host:port` with IPv6 literals bracketed. */
    val authority: String get() = EndpointUrlFactory.buildAuthority(host, port)
}

/**
 * What to do when the pre-connect lookup fails but a previously validated address is known.
 *
 * Neither option ever uses an address that failed validation. They only differ in whether the
 * *last known good* address is used as a fallback, and both keep the user informed about how old
 * that address is.
 */
enum class EndpointStalePolicy {
    /**
     * Never connect with an address that was not validated by the current attempt. The login is
     * refused and the error is shown.
     */
    STRICT,

    /**
     * Continue with the last validated address, clearly marked as stale, together with the time of
     * the last successful resolution. This is the default so a temporary DNS outage does not lock
     * the user out.
     */
    USE_LAST_VALIDATED,
}

/** User configurable settings for the dynamic endpoint resolver. */
data class EndpointSettings(
    val mode: EndpointResolutionMode = EndpointResolutionMode.FIXED,
    /** DNS name that is queried in [EndpointResolutionMode.TXT]. */
    val txtRecordName: String = "",
    /** DNS name that is queried in [EndpointResolutionMode.SRV], e.g. `_8123._tcp.example.com`. */
    val srvRecordName: String = "",
    val refreshIntervalSeconds: Long = EndpointRefreshPolicy.DEFAULT_INTERVAL_SECONDS,
    /** When false the background/periodic refresh is disabled, the pre-connect check stays active. */
    val periodicRefreshEnabled: Boolean = true,
    /** When false the resolver never generates an `https` URL. Everything else prefers HTTPS. */
    val preferHttps: Boolean = true,
    /**
     * Whether an unreachable HTTPS endpoint may be replaced by HTTP. Even when `true` only
     * [FallbackDecision.AUTO_DOWNGRADE] failures are downgraded automatically, see
     * [HttpsFallbackPolicy].
     */
    val allowHttpFallback: Boolean = false,
    /** Behaviour when a lookup fails right before connecting. See [EndpointStalePolicy]. */
    val staleAddressPolicy: EndpointStalePolicy = EndpointStalePolicy.USE_LAST_VALIDATED,
    /** Port used when a record does not carry one. */
    val defaultPort: Int = DEFAULT_HOME_ASSISTANT_PORT,
) {
    /** Name of the record that is actually queried for the current [mode]. */
    val activeRecordName: String
        get() = when (mode) {
            EndpointResolutionMode.TXT -> txtRecordName
            EndpointResolutionMode.SRV -> srvRecordName
            EndpointResolutionMode.FIXED -> ""
        }.trim()

    companion object {
        /** Home Assistant's default HTTP port. Only a fallback, never treated as resolved data. */
        const val DEFAULT_HOME_ASSISTANT_PORT = 8123
    }
}

/**
 * Everything the UI needs to render the "current dynamic address" section.
 *
 * @property current endpoint that is currently used for new connections.
 * @property lastSuccessful endpoint of the most recent successful resolution. Equal to [current]
 *   when the last resolution succeeded.
 * @property isStale true when [current] comes from an earlier successful resolution and the latest
 *   attempt failed, i.e. [lastError] is set while [status] is [EndpointResolutionStatus.FAILED].
 */
data class EndpointSnapshot(
    val mode: EndpointResolutionMode = EndpointResolutionMode.FIXED,
    val status: EndpointResolutionStatus = EndpointResolutionStatus.IDLE,
    val recordName: String? = null,
    val current: ResolvedEndpoint? = null,
    val lastSuccessful: ResolvedEndpoint? = null,
    val lastResolvedAtMillis: Long? = null,
    val nextRefreshAtMillis: Long? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val downgradedToHttp: Boolean = false,
    /**
     * Whether [current] was produced by a successful resolution (or restored from one) instead of
     * being merely read back from the server configuration.
     *
     * The pre-connect gate uses this to decide whether [current] may be used as a fallback: an
     * address the resolver never validated is never treated as "last known good".
     */
    val currentIsValidated: Boolean = false,
) {
    val isStale: Boolean
        get() = status == EndpointResolutionStatus.FAILED && lastSuccessful != null

    /** `true` while a lookup is running. The UI uses this to disable the manual refresh button. */
    val isResolving: Boolean get() = status == EndpointResolutionStatus.RESOLVING
}

/**
 * Outcome of one resolution attempt.
 *
 * A failed attempt carries the previously known endpoint so callers can keep using it, but it is
 * never reported as fresh: [endpoint] is only set together with [success] being true.
 */
data class EndpointResolutionResult(
    val success: Boolean,
    val endpoint: ResolvedEndpoint? = null,
    val error: EndpointResolutionError? = null,
    val rejected: List<RejectedRecord> = emptyList(),
    val durationMs: Long = 0,
    /**
     * Set when the only usable candidate requires the user to accept a plaintext connection.
     * [success] stays `false` in that case, so the request can never silently replace the address
     * that is currently in use.
     */
    val fallbackEvaluation: FallbackEvaluation? = null,
) {
    companion object {
        fun success(
            endpoint: ResolvedEndpoint,
            rejected: List<RejectedRecord> = emptyList(),
            durationMs: Long = 0,
        ) = EndpointResolutionResult(
            success = true,
            endpoint = endpoint,
            error = null,
            rejected = rejected,
            durationMs = durationMs,
        )

        fun failure(
            error: EndpointResolutionError,
            rejected: List<RejectedRecord> = emptyList(),
            durationMs: Long = 0,
            fallbackEvaluation: FallbackEvaluation? = null,
        ) = EndpointResolutionResult(
            success = false,
            endpoint = null,
            error = error,
            rejected = rejected,
            durationMs = durationMs,
            fallbackEvaluation = fallbackEvaluation,
        )
    }
}

/**
 * Error of a failed resolution attempt.
 *
 * @property reason coarse classification, used for retry/backoff decisions.
 * @property message human readable message, safe to show in the UI. Never contains credentials.
 * @property retryable whether retrying the exact same request can plausibly succeed later.
 */
data class EndpointResolutionError(
    val reason: EndpointRejectionReason,
    val message: String,
    val retryable: Boolean = true,
)
