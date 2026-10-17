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
    /**
     * Builds the chain to use when a resolution asks to bypass the device resolver, see
     * [EndpointSettings.bypassSystemDnsCache]. `null` falls back to [dnsClient], which keeps the
     * resolver usable (and testable) without a second chain.
     *
     * It takes the settings because the right chain depends on how far the user wants to get from a
     * cache: a different public resolver, or the authoritative servers of the zone itself.
     */
    private val bypassClientFactory: ((EndpointSettings) -> DnsClient)? = null,
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
        /**
         * Ask a resolver other than the device's own first. A cached answer that has not expired yet
         * is indistinguishable from a current one, so this is the only way a manual refresh can see a
         * record that changed inside its TTL.
         */
        bypassSystemDnsCache: Boolean = false,
    ): EndpointResolutionResult {
        val started = clock()
        val dns = if (bypassSystemDnsCache) bypassClientFactory?.invoke(settings) ?: dnsClient else dnsClient
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

        // The fixed host is checked before anything is queried. Without this a blank or malformed
        // host would surface as "the record carried no port", sending the user to look at the wrong
        // thing: the record is fine, it is the configured host that is missing.
        if (settings.useFixedHost) {
            validateFixedHost(settings.fixedHost)?.let { error ->
                eventRecorder.record(EndpointLifecycleEvent.RESOLUTION_FAILED, "${error.reason}: ${error.message}")
                return EndpointResolutionResult.failure(error, durationMs = clock() - started)
            }
        }

        val interpretation = when (settings.mode) {
            EndpointResolutionMode.TXT -> interpretTxt(dns, recordName, settings)
            EndpointResolutionMode.SRV -> interpretSrv(dns, recordName, settings)
            EndpointResolutionMode.FIXED -> error("handled above")
        }

        if (!interpretation.isUsable) {
            val failure = interpretation.failureReason(settings.mode, recordName)
            eventRecorder.record(EndpointLifecycleEvent.RESOLUTION_FAILED, "${failure.reason}: ${failure.message}")
            return EndpointResolutionResult.failure(
                EndpointResolutionError(
                    reason = failure.reason,
                    message = failure.message,
                    retryable = failure.retryable,
                    diagnostics = failure.diagnostics,
                ),
                rejected = interpretation.rejected,
                durationMs = clock() - started,
            )
        }

        val rejected = interpretation.rejected.toMutableList()
        val ttl = interpretation.ttlSeconds
        var lastFailure: EndpointProbeFailure = EndpointProbeFailure.NONE
        var certificateProblem = false
        val downgradeOptions = mutableListOf<CandidateOutcome.Downgrade>()
        val consentOptions = mutableListOf<CandidateOutcome.NeedsConfirmation>()

        // Pass 1 prefers an endpoint that works over HTTPS. A candidate that would need a plaintext
        // fallback is only remembered, so another candidate may still provide working HTTPS.
        //
        // The host -> IP lookup deliberately uses the *normal* (device-first) resolver, not the cache
        // bypass chain. Bypass only exists to defeat the cache for the dynamic record (the port); the
        // host is fixed and resolves correctly through the device's own DNS. Routing the host lookup
        // through the DoH-first bypass chain would let a public resolver answer first with an
        // unreachable IP (for example the public side of a DDNS while the phone is on the LAN), which
        // then fails the HTTPS probe and surfaces as "no usable address was found".
        interpretation.candidates.forEach { candidate ->
            when (val outcome = evaluateCandidate(dnsClient, candidate, settings, plaintextConsent, rejected)) {
                is CandidateOutcome.Accepted -> {
                    eventRecorder.record(
                        EndpointLifecycleEvent.RESOLUTION_SUCCEEDED,
                        "${settings.mode} $recordName -> ${outcome.endpoint.url}",
                    )
                    return EndpointResolutionResult.success(
                        outcome.endpoint,
                        rejected,
                        clock() - started,
                        recordTtlSeconds = ttl,
                    )
                }

                is CandidateOutcome.Downgrade -> downgradeOptions += outcome
                is CandidateOutcome.NeedsConfirmation -> {
                    // An untrusted certificate is never traded for plaintext: that would make the
                    // connection worse, not better.
                    if (outcome.evaluation.reason == FallbackReason.CERTIFICATE_NOT_TRUSTED) {
                        certificateProblem = true
                    } else {
                        consentOptions += outcome
                    }
                }

                is CandidateOutcome.Rejected -> lastFailure = outcome.failure
            }
        }

        // Pass 2 runs only when no candidate served HTTPS. Every endpoint that did not is probed
        // over plaintext, and it is only ever used *or offered* when it actually answers: that is
        // what keeps a plaintext fallback from being a guess.
        var pendingConfirmation: FallbackEvaluation? = null
        var pendingPlaintextEndpoint: ResolvedEndpoint? = null
        var pendingHttpsFailure: EndpointProbeFailure? = null

        (downgradeOptions.map { it.endpoint to it.httpsFailure } +
            consentOptions.map { it.endpoint to it.httpsFailure })
            .forEach { (endpoint, httpsFailure) ->
                val plaintextFailure = probe.probe(endpoint)
                if (plaintextFailure != EndpointProbeFailure.NONE) {
                    rejected += RejectedRecord(
                        endpoint.host,
                        EndpointRejectionReason.INVALID_URL,
                        "plaintext probe of ${endpoint.authority} returned $plaintextFailure",
                    )
                    lastFailure = plaintextFailure
                    return@forEach
                }

                val downgraded = validate(
                    endpoint.copy(downgradeReason = "$httpsFailure on the HTTPS port"),
                    rejected,
                ) ?: return@forEach

                if (plaintextConsent) {
                    eventRecorder.record(
                        EndpointLifecycleEvent.RESOLUTION_SUCCEEDED,
                        "${settings.mode} $recordName -> ${downgraded.url} (plaintext fallback)",
                    )
                    return EndpointResolutionResult.success(
                        downgraded,
                        rejected,
                        clock() - started,
                        recordTtlSeconds = ttl,
                    )
                }

                // Plaintext answers, but using it is the user's decision. The address travels with
                // the failure so the UI can offer exactly this endpoint instead of asking the user
                // to work it out and retype it.
                if (pendingPlaintextEndpoint == null) {
                    pendingPlaintextEndpoint = downgraded
                    pendingHttpsFailure = httpsFailure
                    pendingConfirmation = FallbackEvaluation(
                        decision = FallbackDecision.REQUIRE_USER_CONFIRMATION,
                        reason = FallbackReason.PLAINTEXT_CONSENT_MISSING,
                        detail = "$httpsFailure did not confirm HTTPS on ${endpoint.authority}, " +
                            "but the address answers over plaintext",
                    )
                }
            }

        val reason = when {
            certificateProblem -> EndpointRejectionReason.TLS_CERTIFICATE_NOT_TRUSTED
            pendingPlaintextEndpoint != null -> EndpointRejectionReason.PLAINTEXT_CONFIRMATION_REQUIRED
            settings.mode == EndpointResolutionMode.TXT -> EndpointRejectionReason.TXT_ALL_RECORDS_INVALID
            else -> EndpointRejectionReason.SRV_ALL_CANDIDATES_INVALID
        }
        val rejectedSummary = describeRejected(rejected)
        val diagnostics = describeDiagnostics(interpretation, lastFailure, rejectedSummary, pendingHttpsFailure)
        val message = when (reason) {
            EndpointRejectionReason.TLS_CERTIFICATE_NOT_TRUSTED ->
                "The certificate of the resolved endpoint is not trusted; certificate verification is never disabled"

            EndpointRejectionReason.PLAINTEXT_CONFIRMATION_REQUIRED ->
                "$recordName resolves to ${pendingPlaintextEndpoint?.url}, which does not serve HTTPS. " +
                    "Connecting to it would send your credentials unencrypted and needs your consent."

            else -> "The ${settings.mode.name} record of $recordName was read but no usable address was found." +
                buildString {
                    rejectedSummary?.let { append(" $it") }
                    if (diagnostics.isNotBlank()) append(" [$diagnostics]")
                }
        }

        eventRecorder.record(EndpointLifecycleEvent.RESOLUTION_FAILED, "$reason: $message")
        return EndpointResolutionResult.failure(
            EndpointResolutionError(
                reason = reason,
                message = message,
                diagnostics = describeDiagnostics(interpretation, lastFailure, rejectedSummary, pendingHttpsFailure),
            ),
            rejected = rejected,
            durationMs = clock() - started,
            fallbackEvaluation = pendingConfirmation,
            pendingPlaintextEndpoint = pendingPlaintextEndpoint,
        )
    }

    /**
     * One line per rejected record, reason first. Record values are the data the user published
     * themselves and never contain credentials (`parseUrl` rejects `user:password@`), so these are
     * safe to show and to log.
     */
    private fun describeRejected(rejected: List<RejectedRecord>): String? {
        if (rejected.isEmpty()) return null
        return rejected.distinctBy { it.reason }
            .joinToString("; ") { "${it.reason}: ${it.detail ?: it.record}" }
    }

    /** Everything a developer needs to reproduce the failure, without any secret. */
    private fun describeDiagnostics(
        interpretation: Interpretation,
        lastFailure: EndpointProbeFailure,
        rejectedSummary: String?,
        pendingHttpsFailure: EndpointProbeFailure? = null,
    ): String = buildList {
        interpretation.dnsFailure?.let { failure ->
            add("dns=${failure.status.name}${failure.detail?.let { " ($it)" }.orEmpty()}")
            if (failure.trail.isNotBlank()) add("attempts=[${failure.trail}]")
        }
        add("candidates=${interpretation.candidates.size}")
        add("lastProbeFailure=$lastFailure")
        // A plaintext offer is not a rejection, so it never lands in lastProbeFailure. Without this
        // the trail would say "NONE" for the most interesting case, where HTTPS was refused.
        pendingHttpsFailure?.let { add("httpsProbeOfOfferedEndpoint=$it") }
        rejectedSummary?.let { add("rejected=$it") }
    }.joinToString(" | ")

    private fun evaluateCandidate(
        dns: DnsClient,
        candidate: EndpointCandidate,
        settings: EndpointSettings,
        plaintextConsent: Boolean,
        rejected: MutableList<RejectedRecord>,
    ): CandidateOutcome {
        val ip = resolveToIp(dns, candidate.host)
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
        if (httpsFailure != EndpointProbeFailure.NONE) {
            // Record the resolved IP for connectivity failures so the user can tell a wrong port
            // (connection refused) from a fundamentally unreachable address (timeout / no route).
            val connectivity = httpsFailure in setOf(
                EndpointProbeFailure.CONNECT_TIMEOUT,
                EndpointProbeFailure.CONNECTION_REFUSED,
                EndpointProbeFailure.NO_ROUTE_TO_HOST,
                EndpointProbeFailure.DNS_FAILURE,
            )
            if (connectivity) {
                rejected += RejectedRecord(
                    candidate.host,
                    EndpointRejectionReason.ENDPOINT_UNREACHABLE,
                    "probe https://${candidate.host}:${candidate.port} resolved to $ip -> $httpsFailure",
                )
            }
        }
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

            FallbackDecision.REQUIRE_USER_CONFIRMATION ->
                CandidateOutcome.NeedsConfirmation(evaluation, httpEndpoint, httpsFailure)

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

    /**
     * Checks the configured fixed host, so a missing or malformed one is named as the problem
     * instead of the record that is being queried.
     */
    private fun validateFixedHost(fixedHost: String): EndpointResolutionError? {
        val host = EndpointUrlFactory.normalizeHost(fixedHost)
        if (host.isBlank()) {
            return EndpointResolutionError(
                reason = EndpointRejectionReason.FIXED_HOST_NOT_CONFIGURED,
                message = "A fixed host is enabled but no host name is configured. " +
                    "Enter the domain (or IP) the app should connect to.",
                retryable = false,
            )
        }
        if (!EndpointUrlFactory.isValidHost(host)) {
            return EndpointResolutionError(
                reason = EndpointRejectionReason.INVALID_FIXED_HOST,
                message = "\"$host\" is not a valid host name or IP address.",
                retryable = false,
            )
        }
        return null
    }

    /** Resolves a host name to an IP literal using A first, then AAAA. */
    private fun resolveToIp(dns: DnsClient, host: String): String? {
        val normalized = EndpointUrlFactory.normalizeHost(host)
        if (EndpointUrlFactory.isIpLiteral(normalized)) return normalized

        listOf(DnsRecordType.A, DnsRecordType.AAAA).forEach { type ->
            val result = dns.query(normalized, type)
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

    private fun interpretTxt(
        dns: DnsClient,
        recordName: String,
        settings: EndpointSettings,
    ): Interpretation {
        val result = queryFollowingAlias(dns, recordName, DnsRecordType.TXT)
        val failure = DnsFailure.from(result)
        if (!result.isSuccess) {
            return Interpretation(
                candidates = emptyList(),
                rejected = listOf(RejectedRecord(recordName, failure.status.rejectionReason(), failure.detail)),
                dnsFailure = failure,
            )
        }

        // Only TXT payloads describe an address. A resolver may include a CNAME or other types in
        // the same answer section, and interpreting those as a record value would invent a host.
        val records = result.answers.filter { it.type == DnsRecordType.TXT }.map { it.value }
        if (records.isEmpty()) {
            val missing = failure.copy(status = DnsQueryStatus.EMPTY, detail = "the answer carried no TXT record")
            return Interpretation(
                candidates = emptyList(),
                rejected = listOf(
                    RejectedRecord(
                        recordName,
                        EndpointRejectionReason.DNS_NO_RECORD_OF_TYPE,
                        "name exists but has no TXT record",
                    ),
                ),
                dnsFailure = missing,
            )
        }

        val scheme = if (settings.preferHttps) EndpointScheme.HTTPS else EndpointScheme.HTTP

        // Fixed host: the record only supplies the port, and any host it carries is discarded. A
        // STUN/relay rule publishes the address it punched through, which is not the name the client
        // reaches the server by; the port is the part that is actually reachable through the fixed
        // name, so that is the part that is taken.
        if (settings.useFixedHost) {
            val host = EndpointUrlFactory.normalizeHost(settings.fixedHost)
            val ports = TxtRecordInterpreter.extractPorts(records)
            return Interpretation(
                candidates = ports.ports.map { port ->
                    EndpointCandidate(host = host, port = port, scheme = scheme, sourceRecord = recordName)
                },
                rejected = ports.rejected,
                ttlSeconds = result.ttlSeconds,
            )
        }

        val parsed = TxtRecordInterpreter.interpret(
            records = records,
            defaultPort = settings.defaultPort,
            preferredScheme = scheme,
        )
        return Interpretation(parsed.candidates, parsed.rejected, ttlSeconds = result.ttlSeconds)
    }

    private fun interpretSrv(
        dns: DnsClient,
        recordName: String,
        settings: EndpointSettings,
    ): Interpretation {
        val result = queryFollowingAlias(dns, recordName, DnsRecordType.SRV)
        val failure = DnsFailure.from(result)
        if (!result.isSuccess) {
            return Interpretation(
                candidates = emptyList(),
                rejected = listOf(RejectedRecord(recordName, failure.status.rejectionReason(), failure.detail)),
                dnsFailure = failure,
            )
        }

        val parsed = SrvRecordInterpreter.parse(result.answers)
        if (parsed.serviceUnavailable) {
            // RFC 2782: target "." means the service is decidedly not available. No URL is built.
            return Interpretation(emptyList(), parsed.rejected, serviceUnavailable = true)
        }

        val scheme = if (settings.preferHttps) EndpointScheme.HTTPS else EndpointScheme.HTTP
        val ordered = SrvRecordSelector.orderCandidates(parsed.records)

        // Fixed host: only the port is taken from the record and its target is ignored, for the same
        // reason as in the TXT path. Records that publish the same port collapse into one candidate.
        if (settings.useFixedHost) {
            val host = EndpointUrlFactory.normalizeHost(settings.fixedHost)
            return Interpretation(
                candidates = ordered.distinctBy { it.port }.map { record ->
                    EndpointCandidate(
                        host = host,
                        port = record.port,
                        scheme = scheme,
                        sourceRecord = record.sourceRecord,
                    )
                },
                rejected = parsed.rejected,
                ttlSeconds = result.ttlSeconds,
            )
        }

        return Interpretation(
            ordered.map { SrvRecordInterpreter.toCandidate(it, scheme) },
            parsed.rejected,
            ttlSeconds = result.ttlSeconds,
        )
    }

    /**
     * Queries [recordName] and follows a `CNAME` once.
     *
     * Publishing the record on a name that is an alias of the real zone is common, and a resolver
     * that returns only the alias is not an error. The second query is only made when the first one
     * produced an alias and no answer, so nothing is spent in the normal case.
     */
    private fun queryFollowingAlias(
        dns: DnsClient,
        recordName: String,
        type: DnsRecordType,
    ): DnsQueryResult {
        val first = dns.query(recordName, type)
        if (first.status != DnsQueryStatus.EMPTY) return first
        val alias = first.canonicalNames.firstOrNull() ?: return first

        val second = dns.query(alias, type)
        return second.copy(attempts = first.attempts + second.attempts)
    }

    /** DNS level failure, kept separate so the message can name the stage that broke. */
    private data class DnsFailure(
        val status: DnsQueryStatus,
        val detail: String?,
        val trail: String,
    ) {
        companion object {
            fun from(result: DnsQueryResult) = DnsFailure(
                status = result.status,
                detail = result.error,
                trail = result.attemptTrail,
            )
        }
    }

    private data class Interpretation(
        val candidates: List<EndpointCandidate>,
        val rejected: List<RejectedRecord>,
        val dnsFailure: DnsFailure? = null,
        val serviceUnavailable: Boolean = false,
        /** TTL the answering resolver reported, carried through so the UI can state it. */
        val ttlSeconds: Long? = null,
    ) {
        val isUsable: Boolean get() = candidates.isNotEmpty()

        /**
         * Turns the outcome into one precise reason and message.
         *
         * The DNS failures are deliberately kept apart: "the name does not exist", "the name has no
         * TXT record", "the resolver answered with an error" and "no resolver could be reached"
         * have four different fixes, and collapsing them into one message is what made the original
         * failure impossible to diagnose.
         */
        fun failureReason(mode: EndpointResolutionMode, recordName: String): Failure {
            if (serviceUnavailable) {
                return Failure(
                    EndpointRejectionReason.SRV_SERVICE_UNAVAILABLE,
                    "The SRV record of $recordName reports the service as unavailable (target \".\")",
                    retryable = false,
                )
            }

            dnsFailure?.let { failure ->
                val diagnostics = "dns=${failure.status.name}" +
                    failure.detail?.let { " ($it)" }.orEmpty() +
                    (if (failure.trail.isNotBlank()) " | attempts=[${failure.trail}]" else "")
                return Failure(
                    reason = failure.status.rejectionReason(),
                    message = failure.status.failureMessage(mode, recordName),
                    retryable = failure.status.isRetryable,
                    diagnostics = diagnostics,
                )
            }

            return Failure(
                EndpointRejectionReason.TXT_ALL_RECORDS_INVALID,
                "The $mode record of $recordName contained no usable address",
                retryable = false,
            )
        }
    }

    private data class Failure(
        val reason: EndpointRejectionReason,
        val message: String,
        val retryable: Boolean,
        val diagnostics: String? = null,
    )

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
        data class NeedsConfirmation(
            val evaluation: FallbackEvaluation,
            val endpoint: ResolvedEndpoint,
            /**
             * Why HTTPS was refused. Carried along so the second pass can state the reason in the
             * record it keeps and in the diagnostic trail, exactly as the downgrade branch does.
             */
            val httpsFailure: EndpointProbeFailure,
        ) : CandidateOutcome

        data class Rejected(val failure: EndpointProbeFailure) : CandidateOutcome
    }
}
