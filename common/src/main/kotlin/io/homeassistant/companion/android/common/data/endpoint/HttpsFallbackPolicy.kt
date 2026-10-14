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

    /** The peer did not speak TLS, for example a plain HTTP listener. */
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
        get() = this == CONNECTION_REFUSED || this == NO_ROUTE_TO_HOST

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
 * 6. Timeouts are ambiguous and always require user confirmation.
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
     * Message sniffing is fragile across TLS providers, therefore anything that cannot be
     * recognised confidently is reported as [EndpointProbeFailure.UNKNOWN_NETWORK_ERROR], which
     * never leads to an automatic downgrade.
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

        val notTlsMarkers = listOf(
            "wrong_version_number",
            "unsupported or unrecognized ssl message",
            "not an ssl/tls record",
            "record layer failure",
            "unexpected message",
        )
        if (notTlsMarkers.any { text.contains(it) }) return EndpointProbeFailure.TLS_HANDSHAKE_FAILURE

        return EndpointProbeFailure.UNKNOWN_NETWORK_ERROR
    }
}
