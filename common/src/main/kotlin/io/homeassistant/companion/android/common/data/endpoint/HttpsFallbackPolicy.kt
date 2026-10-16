package io.homeassistant.companion.android.common.data.endpoint

/**
 * Why a probe of a candidate endpoint failed.
 *
 * The classification is the input of [HttpsFallbackPolicy] and decides whether an `https` endpoint
 * may be replaced by `http`. It is intentionally coarse: a wrong "HTTPS is unavailable" verdict
 * would send credentials over plaintext, so ambiguous cases are never auto-downgraded.
 */
enum class EndpointProbeFailure {
    /** The probe succeeded. */
    NONE,

    /** The host name could not be resolved. Downgrading cannot help. */
    DNS_FAILURE,

    /** No answer within the timeout. Ambiguous: could be a slow HTTPS server. */
    CONNECT_TIMEOUT,

    /** Nothing is listening on the port. Strong signal that this port serves no TLS endpoint. */
    CONNECTION_REFUSED,

    /** No route to the host, for example a blocked port. Strong signal. */
    NO_ROUTE_TO_HOST,

    /**
     * The peer answered, but not with TLS: it replied with plaintext or with a protocol error that
     * only a non-TLS listener produces. This *proves* the port serves no TLS endpoint.
     *
     * Kept apart from [TLS_HANDSHAKE_FAILURE] on purpose. A handshake failure can also mean "this
     * server only speaks a TLS version we no longer offer", where downgrading to plaintext would be
     * wrong, while this failure cannot be produced by a TLS server at all.
     */
    TLS_NOT_A_TLS_PEER,

    /** TLS was negotiated but the handshake failed: version mismatch, alert, unexpected message. */
    TLS_HANDSHAKE_FAILURE,

    /** TLS worked but the certificate is untrusted, expired or has a mismatching host name. */
    TLS_CERT_UNTRUSTED,

    /** The server answered 401/403. HTTPS works, the credentials are the problem. */
    AUTHENTICATION_FAILURE,

    /** The server answered another error status such as 404 or 500. HTTPS works. */
    SERVER_HTTP_ERROR,

    /** Anything else, including connection resets. Treated as ambiguous. */
    UNKNOWN_NETWORK_ERROR,
    ;

    /**
     * True only for failures that prove *this port* does not serve HTTPS. These are the only cases
     * that may be downgraded without asking the user again.
     */
    val provesHttpsAbsent: Boolean
        get() = this == CONNECTION_REFUSED || this == NO_ROUTE_TO_HOST || this == TLS_NOT_A_TLS_PEER

    /** True when HTTPS demonstrably works, so a downgrade would be wrong. */
    val provesHttpsWorks: Boolean
        get() = this == AUTHENTICATION_FAILURE || this == SERVER_HTTP_ERROR
}

/** Outcome of [HttpsFallbackPolicy.evaluate]. */
enum class FallbackDecision {
    /** HTTPS succeeded, nothing to do. */
    NOT_NEEDED,

    /** May be replaced by HTTP automatically, the user enabled it and consented to plaintext. */
    AUTO_DOWNGRADE,

    /** HTTP must not be attempted before the user explicitly accepts the risk. */
    REQUIRE_USER_CONFIRMATION,

    /** HTTP must never be attempted. */
    FORBIDDEN,
}

/** Machine readable explanation, mapped to a localized string by the UI. */
enum class FallbackReason {
    HTTPS_OK,
    FALLBACK_DISABLED,
    FALLBACK_ALLOWED_AND_CONSENTED,
    PLAINTEXT_CONSENT_MISSING,
    AMBIGUOUS_FAILURE,
    CERTIFICATE_NOT_TRUSTED,
    HTTPS_WORKS_AUTH_FAILED,
    HTTPS_WORKS_SERVER_ERROR,
    DNS_FAILED,
    UNKNOWN_FAILURE,
}

/** Result of the downgrade evaluation. */
data class FallbackEvaluation(
    val decision: FallbackDecision,
    val reason: FallbackReason,
    /** English technical detail for logs and the diagnostics report. Never contains secrets. */
    val detail: String? = null,
) {
    val maySwitchToHttp: Boolean get() = decision == FallbackDecision.AUTO_DOWNGRADE
}

/**
 * Decides whether an `https` endpoint may be replaced by an `http` endpoint.
 *
 * The rules follow section 6 of the specification:
 *
 * 1. HTTPS is always tried first.
 * 2. Only failures that *prove* this port does not serve HTTPS
 *    ([EndpointProbeFailure.provesHttpsAbsent]) may lead to an automatic downgrade.
 * 3. Because HTTP is unencrypted, an automatic downgrade additionally requires an explicit
 *    plaintext consent for this server. Without it the user is asked first.
 * 4. Authentication failures, other HTTP status codes and DNS failures never downgrade: HTTPS
 *    demonstrably works or the transport is not the problem.
 * 5. An untrusted certificate is never bypassed and never silently downgraded. Certificate
 *    verification is not disabled anywhere in this module.
 * 6. Timeouts and other ambiguous failures require user confirmation.
 * 7. When the user has **explicitly accepted plaintext** for this server, an ambiguous HTTPS
 *    failure no longer has to be proven: the decision is the user's. Rules 4 and 5 still win,
 *    because downgrading a working HTTPS endpoint or one with an untrusted certificate would make
 *    the connection worse rather than better. The caller additionally has to prove that the
 *    plaintext endpoint is actually reachable before it is used, so a broken network still fails
 *    instead of producing a bogus URL.
 */
object HttpsFallbackPolicy {

    fun evaluate(
        httpsFailure: EndpointProbeFailure,
        allowHttpFallback: Boolean,
        plaintextConsent: Boolean,
    ): FallbackEvaluation = when {
        httpsFailure == EndpointProbeFailure.NONE -> FallbackEvaluation(
            FallbackDecision.NOT_NEEDED,
            FallbackReason.HTTPS_OK,
        )

        httpsFailure.provesHttpsWorks -> FallbackEvaluation(
            FallbackDecision.FORBIDDEN,
            if (httpsFailure == EndpointProbeFailure.AUTHENTICATION_FAILURE) {
                FallbackReason.HTTPS_WORKS_AUTH_FAILED
            } else {
                FallbackReason.HTTPS_WORKS_SERVER_ERROR
            },
            "HTTPS endpoint answered with an HTTP status, the transport works",
        )

        httpsFailure == EndpointProbeFailure.DNS_FAILURE -> FallbackEvaluation(
            FallbackDecision.FORBIDDEN,
            FallbackReason.DNS_FAILED,
            "host name could not be resolved, HTTP would not help",
        )

        httpsFailure == EndpointProbeFailure.TLS_CERT_UNTRUSTED -> FallbackEvaluation(
            FallbackDecision.REQUIRE_USER_CONFIRMATION,
            FallbackReason.CERTIFICATE_NOT_TRUSTED,
            "a TLS service exists but its certificate is not trusted; verification is never disabled",
        )

        !allowHttpFallback -> FallbackEvaluation(
            FallbackDecision.FORBIDDEN,
            FallbackReason.FALLBACK_DISABLED,
            "HTTP fallback is disabled in the settings",
        )

        httpsFailure.provesHttpsAbsent && plaintextConsent -> FallbackEvaluation(
            FallbackDecision.AUTO_DOWNGRADE,
            FallbackReason.FALLBACK_ALLOWED_AND_CONSENTED,
            "$httpsFailure on HTTPS, plaintext consent already given for this server",
        )

        httpsFailure.provesHttpsAbsent -> FallbackEvaluation(
            FallbackDecision.REQUIRE_USER_CONFIRMATION,
            FallbackReason.PLAINTEXT_CONSENT_MISSING,
            "$httpsFailure on HTTPS, but the user has not accepted plaintext for this server",
        )

        // An ambiguous failure is never promoted to a downgrade here, not even when the user
        // already accepted plaintext: this policy only classifies evidence. Consent is honoured
        // one layer up, in the resolver's second pass, where the plaintext port is actually probed
        // and the endpoint is used only if it answers. Keeping the two apart is what stops
        // `AUTO_DOWNGRADE` from ever meaning "we guessed".
        httpsFailure == EndpointProbeFailure.CONNECT_TIMEOUT -> FallbackEvaluation(
            FallbackDecision.REQUIRE_USER_CONFIRMATION,
            FallbackReason.AMBIGUOUS_FAILURE,
            "connect timeout is ambiguous, a slow HTTPS server must not be downgraded silently",
        )

        else -> FallbackEvaluation(
            FallbackDecision.REQUIRE_USER_CONFIRMATION,
            FallbackReason.UNKNOWN_FAILURE,
            "$httpsFailure is not a reliable signal that HTTPS is unavailable",
        )
    }

    /** Maps an HTTP status code to a probe failure. `null` means the response was successful. */
    fun classifyHttpStatus(statusCode: Int): EndpointProbeFailure = when {
        statusCode in 200..399 -> EndpointProbeFailure.NONE
        statusCode == 401 || statusCode == 403 -> EndpointProbeFailure.AUTHENTICATION_FAILURE
        else -> EndpointProbeFailure.SERVER_HTTP_ERROR
    }

    /**
     * Best effort classification of a TLS failure from an exception message.
     *
     * Message sniffing is fragile across TLS providers, so the two kinds of "TLS did not work" are
     * kept apart by what the message can actually prove:
     *
     * * [EndpointProbeFailure.TLS_NOT_A_TLS_PEER] means the peer's bytes were not a TLS record at
     *   all. No TLS server can produce that, so the port demonstrably serves no HTTPS.
     * * [EndpointProbeFailure.TLS_HANDSHAKE_FAILURE] means TLS was attempted and failed mid
     *   handshake. That is consistent with a version or cipher mismatch with a perfectly good HTTPS
     *   server, so it is ambiguous and never downgrades on its own.
     *
     * Anything unrecognised becomes [EndpointProbeFailure.UNKNOWN_NETWORK_ERROR], which also never
     * leads to an automatic downgrade.
     */
    fun classifyTlsFailureMessage(message: String?): EndpointProbeFailure {
        val text = message?.lowercase().orEmpty()
        if (text.isEmpty()) return EndpointProbeFailure.UNKNOWN_NETWORK_ERROR

        val certificateMarkers = listOf(
            "trust anchor for certification path not found",
            "certificate",
            "certpathvalidator",
            "no trusted root",
            "hostname",
        )
        if (certificateMarkers.any { text.contains(it) }) return EndpointProbeFailure.TLS_CERT_UNTRUSTED

        // The peer answered with something that is not TLS at all.
        val notTlsPeerMarkers = listOf(
            "unsupported or unrecognized ssl message",
            "not an ssl/tls record",
            "does not look like a tls handshake",
            "not a tls handshake",
            "is not a tls record",
        )
        if (notTlsPeerMarkers.any { text.contains(it) }) return EndpointProbeFailure.TLS_NOT_A_TLS_PEER

        // TLS was attempted but the negotiation did not complete. Ambiguous.
        val handshakeMarkers = listOf(
            "wrong_version_number",
            "wrong version number",
            "record layer failure",
            "unexpected message",
            "handshake_failure",
            "received fatal alert",
            "no cipher suites in common",
            "connection closed by peer",
            "connection reset by peer",
        )
        if (handshakeMarkers.any { text.contains(it) }) return EndpointProbeFailure.TLS_HANDSHAKE_FAILURE

        return EndpointProbeFailure.UNKNOWN_NETWORK_ERROR
    }
}
