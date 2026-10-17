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
    CNAME(5),
    TXT(16),
    AAAA(28),
    SRV(33),
    ;

    companion object {
        fun fromCode(code: Int): DnsRecordType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Transport a record declares, kept next to the scheme because the two are not the same thing.
 *
 * A record may declare `tcp://host:port`, which says *that* a TCP endpoint exists but not whether
 * it speaks TLS. Such an endpoint is never turned into an `http` URL by assumption: it is probed,
 * and a plaintext result still needs the user's consent. [UNSPECIFIED] covers the records that
 * carry no transport at all (`host:port`, `ip=...;port=...`), where the user's `preferHttps`
 * setting supplies the scheme and the probe confirms it.
 */
enum class EndpointTransport(val urlScheme: String) {
    /** The record carried no transport information. */
    UNSPECIFIED(""),

    HTTP("http"),
    HTTPS("https"),

    /** A bare TCP endpoint (`tcp://`). The transport has to be discovered, never assumed. */
    TCP("tcp"),
    ;

    /** Scheme explicitly requested by the record, or `null` when nothing was requested. */
    val explicitScheme: EndpointScheme?
        get() = EndpointScheme.fromUrlScheme(urlScheme)

    companion object {
        /** Maps a URL scheme prefix to the transport it declares. */
        fun fromUrlScheme(value: String): EndpointTransport? =
            entries.firstOrNull { it.urlScheme.isNotEmpty() && it.urlScheme.equals(value, ignoreCase = true) }
    }
}

/** Outcome of one DNS query. */
enum class DnsQueryStatus {
    /** The resolver answered and returned at least one record. */
    NOERROR,

    /** The resolver answered but the answer section is empty. */
    EMPTY,

    /** The name does not exist. */
    NXDOMAIN,

    /** The resolver reported a server failure. */
    SERVER_FAILURE,

    /** The resolver explicitly refused to answer. */
    REFUSED,

    /** No answer arrived before the timeout. */
    TIMEOUT,

    /** The request never produced an answer, for example because the device is offline. */
    NETWORK_ERROR,

    /** The transport is not available on this device or build. */
    UNSUPPORTED,

    /** The response could not be parsed. */
    MALFORMED,
    ;

    /**
     * True when another resolver, or the same resolver a moment later, would produce the same
     * answer. Only these outcomes stop the fallback chain.
     */
    val isDefinitive: Boolean get() = this == NOERROR || this == EMPTY || this == NXDOMAIN
}

/** One resolver attempt, kept so the UI can say which stage failed instead of "lookup failed". */
data class DnsAttempt(
    /** Transport or provider that was asked, for example `system`, `udp`, `doh:doh.pub`. */
    val provider: String,
    val status: DnsQueryStatus,
    val durationMs: Long = 0,
    /** Non-sensitive reason, for example an HTTP status or the name of an I/O error. */
    val error: String? = null,
) {
    /** Compact single line rendering used in messages and logs. */
    fun describe(): String = buildString {
        append(provider)
        append('=')
        append(status.name)
        if (durationMs > 0) {
            append('(')
            append(durationMs)
            append("ms)")
        }
        error?.takeIf { it.isNotBlank() }?.let {
            append(": ")
            append(it)
        }
    }
}

/**
 * Result of a DNS query.
 *
 * @property attempts every resolver that was tried, in order, with its own outcome. This is what
 *   keeps the UI from reporting every problem as one generic failure.
 */
data class DnsQueryResult(
    val answers: List<DnsAnswer> = emptyList(),
    val status: DnsQueryStatus = DnsQueryStatus.NETWORK_ERROR,
    /** Resolver that produced the answer, for diagnostics. */
    val provider: String? = null,
    val error: String? = null,
    val attempts: List<DnsAttempt> = emptyList(),
    /** Targets of any `CNAME` the resolver returned alongside the answer. */
    val canonicalNames: List<String> = emptyList(),
    /**
     * Lowest TTL among the answers, in seconds: how long this answer may still be served from a
     * cache. `null` when the resolver did not report one.
     *
     * It is what turns "the port did not change" from a guess into a statement: a long TTL means a
     * changed record simply cannot be visible yet.
     */
    val ttlSeconds: Long? = null,
) {
    val isSuccess: Boolean get() = status == DnsQueryStatus.NOERROR

    /** `provider=STATUS(reason); provider=STATUS; ...`, safe to show and to log. */
    val attemptTrail: String get() = attempts.joinToString("; ") { it.describe() }
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

/**
 * Reason why a candidate or a whole resolution attempt was rejected.
 *
 * The DNS failures are deliberately not collapsed into one value: "the record has no address",
 * "the name does not exist", "the resolver refused to answer" and "no resolver could be reached"
 * are four different problems with four different fixes, and a user staring at a generic
 * "lookup failed" message cannot tell them apart. Every reason carries its own message, see
 * [EndpointResolutionError].
 */
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

    /** Generic DNS failure, kept for callers that cannot classify further. */
    DNS_LOOKUP_FAILED,

    /** The name itself does not exist (`NXDOMAIN`). Almost always a typo or a deleted record. */
    DNS_NAME_NOT_FOUND,

    /** The name exists but carries no record of the requested type, for example no `TXT`. */
    DNS_NO_RECORD_OF_TYPE,

    /** The resolver answered `SERVFAIL`/`REFUSED`/`FORMERR`: the zone or the resolver is broken. */
    DNS_RESOLVER_ERROR,

    /** No resolver could be reached at all: offline, blocked, or every transport timed out. */
    DNS_UNREACHABLE,

    /** A resolver answered with something that is not a usable DNS message. */
    DNS_RESPONSE_MALFORMED,

    NO_RECORDS_FOUND,

    /** Every record that was read could be parsed, but no usable candidate came out of it. */
    TXT_ALL_RECORDS_INVALID,

    /**
     * The record asked for a transport this app cannot speak, for example `udp://`. Nothing is
     * guessed from it.
     */
    UNSUPPORTED_TRANSPORT,

    /** HTTPS could not be confirmed and the plaintext fallback needs the user's consent. */
    PLAINTEXT_CONFIRMATION_REQUIRED,

    /** A TLS service exists but its certificate is not trusted. Verification is never disabled. */
    TLS_CERTIFICATE_NOT_TRUSTED,

    /** Fixed-host mode is enabled but no host was configured. */
    FIXED_HOST_NOT_CONFIGURED,

    /** The configured fixed host is not a usable host name or IP literal. */
    INVALID_FIXED_HOST,
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
 * @property scheme transport to use for the generated URL. `null` means the record did not ask for
 *   one *and* the transport has to be discovered, which is the case for `tcp://` records. The
 *   resolver then probes HTTPS and never falls back to plaintext without consent.
 * @property path optional path that came from a full URL, without leading slash.
 * @property sourceRecord the DNS record value this candidate originates from.
 * @property transport what the record actually declared, kept for diagnostics and for wording the
 *   UI. `tcp://` records report [EndpointTransport.TCP] and are probed instead of assumed.
 */
data class EndpointCandidate(
    val host: String,
    val port: Int,
    val scheme: EndpointScheme? = null,
    val path: String? = null,
    val sourceRecord: String? = null,
    val transport: EndpointTransport = EndpointTransport.UNSPECIFIED,
) {
    /**
     * True when the record declared a bare TCP endpoint. The generated URL is never an `http` one
     * purely because of this flag: HTTPS is probed first and plaintext needs explicit consent.
     */
    val isBareTcpEndpoint: Boolean get() = transport == EndpointTransport.TCP && scheme == null
}

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

    /**
     * When true the host of every generated URL is [fixedHost] and the DNS record only supplies the
     * port.
     *
     * This is what makes "fixed domain + dynamic port" work. A STUN/relay setup such as lucky
     * publishes the address it punched through as `ip:port`, and that IP changes, while the name the
     * client can actually reach (a domain behind a TLS terminator) stays the same. Keeping the host
     * fixed and taking only the port from the record is what lets both halves be true at once.
     */
    val useFixedHost: Boolean = false,

    /**
     * Host name (or IP literal) used for every generated URL while [useFixedHost] is true.
     *
     * Kept separate from [txtRecordName]/[srvRecordName] on purpose: the record that carries the
     * port and the name the client connects to are two different things in this setup.
     */
    val fixedHost: String = "",

    /**
     * When true a **manual** refresh asks a public resolver before the device's own.
     *
     * The device resolver answers from its own cache until the record's TTL expires, and a cached
     * answer stops the resolver chain, so a record that just changed keeps answering with the old
     * value no matter how often the user refreshes. Querying a different resolver first gets a view
     * that is not that cache.
     *
     * Only manual refreshes do this: the periodic refresh stays on the cheap local resolver, and the
     * pre-connect gate must not add a round trip to every login.
     */
    val bypassSystemDnsCache: Boolean = false,
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
     * TTL the resolver reported for the record on the last successful lookup, in seconds.
     *
     * A changed record cannot become visible before this expires, so it is the number that explains
     * why a refresh keeps returning the same value.
     */
    val recordTtlSeconds: Long? = null,
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
    /**
     * TTL the answering resolver reported for the record, in seconds. Shown so a stale value can be
     * recognised as "this answer is still cached for N more seconds" instead of "the port did not
     * change".
     */
    val recordTtlSeconds: Long? = null,
    /**
     * The plaintext endpoint the user could accept when [EndpointRejectionReason.PLAINTEXT_CONFIRMATION_REQUIRED]
     * is the error. It already answered over plaintext, so offering it is not a guess. The UI shows
     * exactly this address instead of asking the user to work it out and retype it.
     */
    val pendingPlaintextEndpoint: ResolvedEndpoint? = null,
) {
    companion object {
        fun success(
            endpoint: ResolvedEndpoint,
            rejected: List<RejectedRecord> = emptyList(),
            durationMs: Long = 0,
            recordTtlSeconds: Long? = null,
        ) = EndpointResolutionResult(
            success = true,
            endpoint = endpoint,
            error = null,
            rejected = rejected,
            durationMs = durationMs,
            recordTtlSeconds = recordTtlSeconds,
        )

        fun failure(
            error: EndpointResolutionError,
            rejected: List<RejectedRecord> = emptyList(),
            durationMs: Long = 0,
            fallbackEvaluation: FallbackEvaluation? = null,
            pendingPlaintextEndpoint: ResolvedEndpoint? = null,
        ) = EndpointResolutionResult(
            success = false,
            endpoint = null,
            error = error,
            rejected = rejected,
            durationMs = durationMs,
            fallbackEvaluation = fallbackEvaluation,
            pendingPlaintextEndpoint = pendingPlaintextEndpoint,
        )
    }
}

/**
 * Error of a failed resolution attempt.
 *
 * @property reason coarse classification, used for retry/backoff decisions and by the UI to pick a
 *   message. Every reason has its own message, so a broken record name and an unreachable resolver
 *   are never reported as the same thing.
 * @property message human readable message, safe to show in the UI. Never contains credentials.
 * @property retryable whether retrying the exact same request can plausibly succeed later.
 * @property diagnostics non-sensitive, developer oriented detail: which resolver was tried, with
 *   what outcome, and which records were rejected because of what. Safe to log.
 */
data class EndpointResolutionError(
    val reason: EndpointRejectionReason,
    val message: String,
    val retryable: Boolean = true,
    val diagnostics: String? = null,
)

/**
 * Reason a DNS outcome maps to.
 *
 * Kept next to the status so every transport reports the same reason for the same condition.
 */
internal fun DnsQueryStatus.rejectionReason(): EndpointRejectionReason = when (this) {
    DnsQueryStatus.NXDOMAIN -> EndpointRejectionReason.DNS_NAME_NOT_FOUND
    DnsQueryStatus.EMPTY -> EndpointRejectionReason.DNS_NO_RECORD_OF_TYPE
    DnsQueryStatus.SERVER_FAILURE,
    DnsQueryStatus.REFUSED,
    -> EndpointRejectionReason.DNS_RESOLVER_ERROR

    DnsQueryStatus.TIMEOUT,
    DnsQueryStatus.NETWORK_ERROR,
    DnsQueryStatus.UNSUPPORTED,
    -> EndpointRejectionReason.DNS_UNREACHABLE

    DnsQueryStatus.MALFORMED -> EndpointRejectionReason.DNS_RESPONSE_MALFORMED
    DnsQueryStatus.NOERROR -> EndpointRejectionReason.NO_RECORDS_FOUND
}

/**
 * Message shown for a failed DNS outcome.
 *
 * Each case names the stage that broke and what to do about it. A single "lookup failed" message is
 * exactly what made the original problem undiagnosable.
 */
internal fun DnsQueryStatus.failureMessage(
    mode: EndpointResolutionMode,
    recordName: String,
): String = when (this) {
    DnsQueryStatus.NXDOMAIN ->
        "The DNS name $recordName does not exist (NXDOMAIN). Check the record name for a typo."

    DnsQueryStatus.EMPTY ->
        "The DNS name $recordName exists but has no ${mode.name} record."

    DnsQueryStatus.SERVER_FAILURE ->
        "The DNS resolver returned a server failure for $recordName (SERVFAIL). Retrying may help."

    DnsQueryStatus.REFUSED ->
        "The DNS resolver refused to answer for $recordName (REFUSED). The configured resolvers may not serve this name."

    DnsQueryStatus.TIMEOUT ->
        "The DNS lookup for $recordName timed out before any resolver answered."

    DnsQueryStatus.NETWORK_ERROR ->
        "No DNS resolver could be reached for $recordName. The device may be offline, or DNS may be blocked by the network."

    DnsQueryStatus.UNSUPPORTED ->
        "This device offers no DNS transport that can query ${mode.name} records."

    DnsQueryStatus.MALFORMED ->
        "The DNS answer for $recordName could not be parsed."

    DnsQueryStatus.NOERROR ->
        "The ${mode.name} record of $recordName was read but contained no usable address."
}

/** Whether the same request could plausibly succeed later. A typo or a missing record cannot. */
internal val DnsQueryStatus.isRetryable: Boolean
    get() = when (this) {
        DnsQueryStatus.NXDOMAIN -> false
        DnsQueryStatus.MALFORMED -> false
        DnsQueryStatus.UNSUPPORTED -> false
        DnsQueryStatus.NOERROR -> false
        else -> true
    }
