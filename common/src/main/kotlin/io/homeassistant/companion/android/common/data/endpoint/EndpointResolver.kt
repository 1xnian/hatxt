package io.homeassistant.companion.android.common.data.endpoint

/**
 * Checks whether a candidate endpoint is actually reachable and classifies the failure.
 *
 * Implementations live outside this package because they need the platform HTTP stack. The
 * interface is synchronous like [DnsClient]; callers must run it off the main thread.
 */
interface EndpointProbe {
    /**
     * Connects to [endpoint] and returns [EndpointProbeFailure.NONE] on success.
     *
     * Implementations must **not** disable certificate or host name verification. A certificate
     * problem has to be reported as [EndpointProbeFailure.TLS_CERT_UNTRUSTED] so that the fallback
     * policy can refuse to downgrade.
     */
    fun probe(endpoint: ResolvedEndpoint): EndpointProbeFailure

    /** No-op probe used when verification is not available. */
    object None : EndpointProbe {
        override fun probe(endpoint: ResolvedEndpoint): EndpointProbeFailure = EndpointProbeFailure.NONE
    }
}

/**
 * Resolves the endpoint the client should connect to, based on the user's settings.
 *
 * The pipeline is always the same, and every step is a hard gate that can reject a candidate:
 *
 * ```
 * record name -> DNS lookup -> record interpretation -> host to IP (A/AAAA)
 *             -> URL construction -> reachability probe -> HTTPS/HTTP decision
 * ```
 *
 * A candidate is only ever returned as an [EndpointResolutionResult.endpoint] when **all** gates
 * passed. Everything else is reported through [EndpointResolutionResult.error] and
 * [EndpointResolutionResult.rejected], which is what keeps an unvalidated address from replacing a
 * working one.
 *
 * ### Host name versus IP in the generated URL
 *
 * The URL host is the host name that came from the record. The resolved IP is kept in
 * [ResolvedEndpoint.ip] for diagnostics only and is *not* substituted into the URL. Replacing the
 * host name with an IP would break TLS certificate validation, which the specification explicitly
 * requires to keep working.
 */
class EndpointResolver(
    private val dnsClient: DnsClient,
    private val probe: EndpointProbe = EndpointProbe.None,
    private val eventRecorder: EndpointEventRecorder = EndpointEventRecorder.NoOp,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Resolves a fixed, user provided URL. No DNS record is queried in this mode. */
    fun resolveFixed(url: String): EndpointResolutionResult {
        val started = clock()
        val parsed = EndpointUrlFactory.parseUrl(url)
            ?: return EndpointResolutionResult.failure(
                EndpointResolutionError(
                    reason = EndpointRejectionReason.INVALID_URL,
                    message = "The URL is not a valid http(s) address: $url",
                    retryable = false,
                ),
                durationMs = clock() - started,
            )
        return EndpointResolutionResult.success(parsed.toEndpoint(), durationMs = clock() - started)
    }

    /**
     * Runs the full pipeline for the configured TXT or SRV record.
     *
     * @param plaintextConsent whether the user already accepted an unencrypted connection to this
     *   server. Required before an automatic HTTP downgrade may happen, see [HttpsFallbackPolicy].
     */
    fun resolve(
        settings: EndpointSettings,
        plaintextConsent: Boolean = false,
    ): EndpointResolutionResult {
        val started = clock()
        if (!settings.mode.usesDnsRecord) {
            return EndpointResolutionResult.failure(
                EndpointResolutionError(
                    reason = EndpointRejectionReason.NO_RECORDS_FOUND,
                    message = "Resolution mode ${settings.mode} does not use a DNS record",
                    retryable = false,
                ),
                durationMs = clock() - started,
            )
        }

        val recordName = settings.activeRecordName
        if (recordName.isBlank()) {
            return EndpointResolutionResult.failure(
                EndpointResolutionError(
                    reason = EndpointRejectionReason.NO_RECORDS_FOUND,
                    message = "No ${settings.mode} record name configured",
                    retryable = false,
                ),
                durationMs = clock() - started,
            )
        }

        val interpretation = when (settings.mode) {
            EndpointResolutionMode.TXT -> interpretTxt(recordName, settings)
            EndpointResolutionMode.SRV -> interpretSrv(recordName, settings)
            EndpointResolutionMode.FIXED -> error("handled above")
        }

        if (!interpretation.isUsable) {
            val (reason, message) = interpretation.failureReason(settings.mode, recordName)
            eventRecorder.record(EndpointLifecycleEvent.RESOLUTION_FAILED, "$reason: $message")
            return EndpointResolutionResult.failure(
                EndpointResolutionError(reason, message),
                rejected = interpretation.rejected,
                durationMs = clock() - started,
            )
        }

        val rejected = interpretation.rejected.toMutableList()
        var pendingConfirmation: FallbackEvaluation? = null
        var lastFailure: EndpointProbeFailure = EndpointProbeFailure.NONE
        val downgradeOptions = mutableListOf<CandidateOutcome.Downgrade>()

        // Pass 1 prefers an endpoint that works over HTTPS. A candidate that would need a plaintext
        // fallback is only remembered, so another candidate may still provide working HTTPS.
        interpretation.candidates.forEach { candidate ->
            when (val outcome = evaluateCandidate(candidate, settings, plaintextConsent, rejected)) {
                is CandidateOutcome.Accepted -> {
                    eventRecorder.record(
                        EndpointLifecycleEvent.RESOLUTION_SUCCEEDED,
                        "${settings.mode} $recordName -> ${outcome.endpoint.url}",
                    )
                    return EndpointResolutionResult.success(outcome.endpoint, rejected, clock() - started)
                }

                is CandidateOutcome.Downgrade -> downgradeOptions += outcome
                is CandidateOutcome.NeedsConfirmation -> pendingConfirmation = outcome.evaluation
                is CandidateOutcome.Rejected -> lastFailure = outcome.failure
            }
        }

        // Pass 2 runs only when no candidate served HTTPS, and the policy already allowed the
        // downgrade for at least one of them. The plaintext endpoint still has to be reachable.
        downgradeOptions.forEach { option ->
            val httpFailure = probe.probe(option.endpoint)
            if (httpFailure == EndpointProbeFailure.NONE) {
                val downgraded = validate(
                    option.endpoint.copy(downgradeReason = "${option.httpsFailure} on the HTTPS port"),
                    rejected,
                )
                if (downgraded != null) {
                    eventRecorder.record(
                        EndpointLifecycleEvent.RESOLUTION_SUCCEEDED,
                        "${settings.mode} $recordName -> ${downgraded.url} (plaintext fallback)",
                    )
                    return EndpointResolutionResult.success(downgraded, rejected, clock() - started)
                }
            } else {
                rejected += RejectedRecord(
                    option.endpoint.host,
                    EndpointRejectionReason.INVALID_URL,
                    "HTTP probe returned $httpFailure",
                )
                lastFailure = httpFailure
            }
        }

        val certificateProblem = pendingConfirmation?.reason == FallbackReason.CERTIFICATE_NOT_TRUSTED ||
            lastFailure == EndpointProbeFailure.TLS_CERT_UNTRUSTED
        val reason = when {
            certificateProblem -> EndpointRejectionReason.TLS_CERTIFICATE_NOT_TRUSTED
            pendingConfirmation != null -> EndpointRejectionReason.PLAINTEXT_CONFIRMATION_REQUIRED
            else -> EndpointRejectionReason.SRV_ALL_CANDIDATES_INVALID
        }
        val message = when (reason) {
            EndpointRejectionReason.TLS_CERTIFICATE_NOT_TRUSTED ->
                "The certificate of the resolved endpoint is not trusted; certificate verification is never disabled"

            EndpointRejectionReason.PLAINTEXT_CONFIRMATION_REQUIRED ->
                "HTTPS could not be confirmed for $recordName and the HTTP fallback needs your consent"

            else -> "None of the ${interpretation.candidates.size} candidate(s) of $recordName could be used"
        }

        eventRecorder.record(EndpointLifecycleEvent.RESOLUTION_FAILED, "$reason: $message")
        return EndpointResolutionResult.failure(
            EndpointResolutionError(reason, message),
            rejected = rejected,
            durationMs = clock() - started,
            fallbackEvaluation = pendingConfirmation,
        )
    }

    private fun evaluateCandidate(
        candidate: EndpointCandidate,
        settings: EndpointSettings,
        plaintextConsent: Boolean,
        rejected: MutableList<RejectedRecord>,
    ): CandidateOutcome {
        val ip = resolveToIp(candidate.host)
        if (ip == null) {
            rejected += RejectedRecord(
                candidate.sourceRecord ?: candidate.host,
                EndpointRejectionReason.SRV_TARGET_NOT_RESOLVABLE,
                "${candidate.host} has no A/AAAA record",
            )
            return CandidateOutcome.Rejected(EndpointProbeFailure.DNS_FAILURE)
        }

        val requestedScheme = candidate.scheme
            ?: if (settings.preferHttps) EndpointScheme.HTTPS else EndpointScheme.HTTP

        val httpsEndpoint = ResolvedEndpoint(
            scheme = EndpointScheme.HTTPS,
            host = candidate.host,
            port = candidate.port,
            ip = ip,
            path = candidate.path,
        )
        val httpEndpoint = ResolvedEndpoint(
            scheme = EndpointScheme.HTTP,
            host = candidate.host,
            port = candidate.port,
            ip = ip,
            path = candidate.path,
        )

        // The user explicitly asked for plain HTTP, so nothing has to be downgraded.
        if (requestedScheme == EndpointScheme.HTTP) {
            val validated = validate(httpEndpoint, rejected) ?: return CandidateOutcome.Rejected(
                EndpointProbeFailure.UNKNOWN_NETWORK_ERROR,
            )
            return CandidateOutcome.Accepted(validated)
        }

        val httpsFailure = probe.probe(httpsEndpoint)
        if (httpsFailure == EndpointProbeFailure.NONE) {
            val validated = validate(httpsEndpoint, rejected)
                ?: return CandidateOutcome.Rejected(EndpointProbeFailure.UNKNOWN_NETWORK_ERROR)
            return CandidateOutcome.Accepted(validated)
        }

        val evaluation = HttpsFallbackPolicy.evaluate(
            httpsFailure = httpsFailure,
            allowHttpFallback = settings.allowHttpFallback,
            plaintextConsent = plaintextConsent,
        )
        eventRecorder.record(
            EndpointLifecycleEvent.RESOLUTION_FAILED,
            "HTTPS probe of ${candidate.host}:${candidate.port} returned $httpsFailure -> ${evaluation.decision}",
        )

        return when (evaluation.decision) {
            // Remembered only. The plaintext endpoint is probed in a second pass, after every
            // candidate had a chance to serve HTTPS successfully.
            FallbackDecision.AUTO_DOWNGRADE -> CandidateOutcome.Downgrade(httpEndpoint, httpsFailure)

            FallbackDecision.REQUIRE_USER_CONFIRMATION -> CandidateOutcome.NeedsConfirmation(evaluation)

            FallbackDecision.FORBIDDEN,
            FallbackDecision.NOT_NEEDED,
            -> CandidateOutcome.Rejected(httpsFailure)
        }
    }

    private fun validate(
        endpoint: ResolvedEndpoint,
        rejected: MutableList<RejectedRecord>,
    ): ResolvedEndpoint? {
        val reason = EndpointUrlFactory.validate(endpoint) ?: return endpoint
        rejected += RejectedRecord(endpoint.url, reason)
        return null
    }

    /** Resolves a host name to an IP literal using A first, then AAAA. */
    private fun resolveToIp(host: String): String? {
        val normalized = EndpointUrlFactory.normalizeHost(host)
        if (EndpointUrlFactory.isIpLiteral(normalized)) return normalized

        listOf(DnsRecordType.A, DnsRecordType.AAAA).forEach { type ->
            val result = dnsClient.query(normalized, type)
            if (!result.isSuccess) return@forEach
            result.answers.forEach { answer ->
                val value = answer.value.trim().removeSurrounding("[", "]")
                val valid = when (type) {
                    DnsRecordType.A -> EndpointUrlFactory.isValidIpv4Literal(value)
                    DnsRecordType.AAAA -> EndpointUrlFactory.isValidIpv6Literal(value)
                    else -> false
                }
                if (valid) return value
            }
        }
        return null
    }

    private fun interpretTxt(recordName: String, settings: EndpointSettings): Interpretation {
        val result = dnsClient.query(recordName, DnsRecordType.TXT)
        if (!result.isSuccess) {
            return Interpretation(
                candidates = emptyList(),
                rejected = listOf(
                    RejectedRecord(
                        recordName,
                        EndpointRejectionReason.DNS_LOOKUP_FAILED,
                        result.error ?: result.status.name,
                    ),
                ),
                dnsFailed = true,
            )
        }
        val parsed = TxtRecordInterpreter.interpret(
            records = result.answers.map { it.value },
            defaultPort = settings.defaultPort,
            preferredScheme = if (settings.preferHttps) EndpointScheme.HTTPS else EndpointScheme.HTTP,
        )
        return Interpretation(parsed.candidates, parsed.rejected)
    }

    private fun interpretSrv(recordName: String, settings: EndpointSettings): Interpretation {
        val result = dnsClient.query(recordName, DnsRecordType.SRV)
        if (!result.isSuccess) {
            return Interpretation(
                candidates = emptyList(),
                rejected = listOf(
                    RejectedRecord(
                        recordName,
                        EndpointRejectionReason.DNS_LOOKUP_FAILED,
                        result.error ?: result.status.name,
                    ),
                ),
                dnsFailed = true,
            )
        }

        val parsed = SrvRecordInterpreter.parse(result.answers)
        if (parsed.serviceUnavailable) {
            // RFC 2782: target "." means the service is decidedly not available. No URL is built.
            return Interpretation(emptyList(), parsed.rejected, serviceUnavailable = true)
        }

        val scheme = if (settings.preferHttps) EndpointScheme.HTTPS else EndpointScheme.HTTP
        val ordered = SrvRecordSelector.orderCandidates(parsed.records)
            .map { SrvRecordInterpreter.toCandidate(it, scheme) }
        return Interpretation(ordered, parsed.rejected)
    }

    private data class Interpretation(
        val candidates: List<EndpointCandidate>,
        val rejected: List<RejectedRecord>,
        val dnsFailed: Boolean = false,
        val serviceUnavailable: Boolean = false,
    ) {
        val isUsable: Boolean get() = candidates.isNotEmpty()

        fun failureReason(mode: EndpointResolutionMode, recordName: String): Pair<EndpointRejectionReason, String> =
            when {
                serviceUnavailable -> EndpointRejectionReason.SRV_SERVICE_UNAVAILABLE to
                    "The SRV record of $recordName reports the service as unavailable (target \".\")"

                dnsFailed -> EndpointRejectionReason.DNS_LOOKUP_FAILED to
                    "The $mode lookup for $recordName failed"

                else -> EndpointRejectionReason.SRV_ALL_CANDIDATES_INVALID to
                    "The $mode record of $recordName contained no usable address"
            }
    }

    private sealed interface CandidateOutcome {
        /** Usable as is, no downgrade involved. */
        data class Accepted(val endpoint: ResolvedEndpoint) : CandidateOutcome

        /**
         * HTTPS failed in a way that *proves* this port serves no TLS endpoint, and the user
         * already allowed plaintext. Deferred to the second pass so another candidate can still
         * serve HTTPS first.
         */
        data class Downgrade(
            val endpoint: ResolvedEndpoint,
            val httpsFailure: EndpointProbeFailure,
        ) : CandidateOutcome

        /** The user has to accept a plaintext connection before this candidate can be used. */
        data class NeedsConfirmation(val evaluation: FallbackEvaluation) : CandidateOutcome

        data class Rejected(val failure: EndpointProbeFailure) : CandidateOutcome
    }
}
