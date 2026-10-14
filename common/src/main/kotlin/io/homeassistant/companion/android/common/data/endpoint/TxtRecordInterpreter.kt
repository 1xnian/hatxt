package io.homeassistant.companion.android.common.data.endpoint

/**
 * Turns raw DNS TXT record payloads into validated [EndpointCandidate]s.
 *
 * Several on-the-wire shapes are accepted because a TXT record is free form and existing
 * deployments already use more than one of them. Every accepted shape is covered by a unit test in
 * `TxtRecordInterpreterTest`.
 *
 * Supported shapes (case insensitive keywords, surrounding quotes already removed by the DNS
 * client):
 *
 * ```
 * https://ha.example.com:8123          full URL, scheme and port are kept
 * https://ha.example.com/ha            full URL with a path
 * 203.0.113.10:8123                    bare host:port, scheme comes from the preference
 * 203.0.113.10                         bare host, default port is used
 * ha.example.com                       host name that is resolved to A/AAAA later
 * ip=203.0.113.10;port=8123            key/value bag
 * host=ha.example.com;port=8123        key/value bag with a host name
 * url=https://ha.example.com:8123      explicit url key
 * ```
 *
 * Anything else is reported as [RejectedRecord] with a reason instead of silently producing a
 * bogus endpoint.
 */
object TxtRecordInterpreter {

    /** Keys that carry the host part of a record. */
    private val HOST_KEYS = setOf("ip", "host", "hostname", "address", "addr", "target")

    /** Keys that carry the port. */
    private val PORT_KEYS = setOf("port")

    /** Keys that carry the scheme. */
    private val SCHEME_KEYS = setOf("scheme", "protocol")

    /** Keys that carry a full URL. */
    private val URL_KEYS = setOf("url", "uri")

    /** Keys that carry a path appended to the generated URL. */
    private val PATH_KEYS = setOf("path")

    private val HOST_PORT_REGEX = Regex("^(\\[[0-9A-Fa-f:.]+]|[^:/?#@\\s]+):(\\d{1,6})$")

    /**
     * Interprets every record and returns the usable candidates in preference order.
     *
     * @param records raw TXT payloads. `null`/blank entries are rejected as [EndpointRejectionReason.EMPTY_RECORD].
     * @param defaultPort port used when the record does not carry one.
     * @param preferredScheme scheme used when the record does not carry one. Defaults to HTTPS so
     *   that a bare record never silently downgrades the transport, see section 6 of the spec.
     */
    fun interpret(
        records: List<String?>,
        defaultPort: Int = EndpointSettings.DEFAULT_HOME_ASSISTANT_PORT,
        preferredScheme: EndpointScheme = EndpointScheme.HTTPS,
    ): CandidateParseResult {
        val candidates = LinkedHashMap<String, EndpointCandidate>()
        val rejected = mutableListOf<RejectedRecord>()

        records.forEach { raw ->
            val record = raw?.trim().orEmpty()
            if (record.isEmpty()) {
                rejected += RejectedRecord(raw ?: "", EndpointRejectionReason.EMPTY_RECORD)
                return@forEach
            }

            when (val parsed = parseRecord(record, defaultPort, preferredScheme)) {
                is SingleRecordResult.Ok -> parsed.candidates.forEach { candidate ->
                    val key = candidateKey(candidate)
                    if (candidates.containsKey(key)) {
                        rejected += RejectedRecord(record, EndpointRejectionReason.DUPLICATE_RECORD, key)
                    } else {
                        candidates[key] = candidate
                    }
                }

                is SingleRecordResult.Invalid ->
                    rejected += RejectedRecord(record, parsed.reason, parsed.detail)
            }
        }

        return CandidateParseResult(candidates.values.toList(), rejected)
    }

    private fun parseRecord(
        record: String,
        defaultPort: Int,
        preferredScheme: EndpointScheme,
    ): SingleRecordResult {
        // A key=value pair that appears before any scheme separator makes this a field bag, so
        // `url=https://host:port` is handled as a bag and not mistaken for a malformed URL.
        val firstEquals = record.indexOf('=')
        val schemeSeparator = record.indexOf("://")
        val isKeyValueRecord = firstEquals > 0 && (schemeSeparator < 0 || firstEquals < schemeSeparator)

        if (isKeyValueRecord) {
            return parseKeyValueRecord(record, defaultPort, preferredScheme)
        }

        // 1. A scheme makes the record a full URL.
        if (schemeSeparator >= 0) {
            val parsed = EndpointUrlFactory.parseUrl(record)
                ?: return SingleRecordResult.Invalid(
                    EndpointRejectionReason.INVALID_URL,
                    "not a valid http(s) URL",
                )
            return SingleRecordResult.Ok(listOf(parsed.toCandidate(record)))
        }

        // 2. A bare host:port pair.
        HOST_PORT_REGEX.matchEntire(record)?.let { match ->
            val host = EndpointUrlFactory.normalizeHost(match.groupValues[1])
            val port = EndpointUrlFactory.parsePortOrNull(match.groupValues[2])
                ?: return SingleRecordResult.Invalid(
                    EndpointRejectionReason.INVALID_PORT,
                    match.groupValues[2],
                )
            return if (EndpointUrlFactory.isValidHost(host)) {
                SingleRecordResult.Ok(listOf(EndpointCandidate(host, port, preferredScheme, sourceRecord = record)))
            } else {
                SingleRecordResult.Invalid(EndpointUrlFactory.classifyInvalidHost(host), host)
            }
        }

        // 3. A bare host, port is taken from the default.
        val host = EndpointUrlFactory.normalizeHost(record)
        if (!EndpointUrlFactory.isValidHost(host)) {
            return SingleRecordResult.Invalid(EndpointUrlFactory.classifyInvalidHost(host), host)
        }
        return SingleRecordResult.Ok(listOf(EndpointCandidate(host, defaultPort, preferredScheme, sourceRecord = record)))
    }

    private fun parseKeyValueRecord(
        record: String,
        defaultPort: Int,
        preferredScheme: EndpointScheme,
    ): SingleRecordResult {
        val pairs = splitKeyValuePairs(record)
        if (pairs.isEmpty()) {
            return SingleRecordResult.Invalid(EndpointRejectionReason.UNPARSEABLE_RECORD, record)
        }

        var scheme = preferredScheme
        var port = defaultPort
        var portWasExplicit = false
        val path = mutableListOf<String>()
        val urlValues = mutableListOf<String>()
        val hostValues = mutableListOf<String>()

        pairs.forEach { (key, value) ->
            when {
                key in URL_KEYS -> urlValues += value
                key in HOST_KEYS -> hostValues += value
                key in PORT_KEYS -> {
                    val parsed = EndpointUrlFactory.parsePortOrNull(value)
                        ?: return SingleRecordResult.Invalid(EndpointRejectionReason.INVALID_PORT, value)
                    port = parsed
                    portWasExplicit = true
                }

                key in SCHEME_KEYS -> {
                    val parsed = EndpointScheme.fromUrlScheme(value)
                        ?: return SingleRecordResult.Invalid(EndpointRejectionReason.UNSUPPORTED_SCHEME, value)
                    scheme = parsed
                }

                key in PATH_KEYS -> path += value
                // Unknown keys are ignored on purpose: TXT records often carry extra metadata.
            }
        }

        // A url= value wins over host/port keys, but explicit overrides are still applied.
        if (urlValues.isNotEmpty()) {
            val candidates = urlValues.mapNotNull { urlValue ->
                EndpointUrlFactory.parseUrl(urlValue)?.toCandidate(record)
            }
            if (candidates.isEmpty()) {
                return SingleRecordResult.Invalid(EndpointRejectionReason.INVALID_URL, urlValues.first())
            }
            return SingleRecordResult.Ok(
                candidates.map { candidate ->
                    candidate.copy(
                        port = if (portWasExplicit) port else candidate.port,
                        path = candidate.path ?: EndpointUrlFactory.normalizePath(path.joinToString("/")),
                    )
                },
            )
        }

        if (hostValues.isEmpty()) {
            return SingleRecordResult.Invalid(EndpointRejectionReason.MISSING_HOST, record)
        }

        val normalizedPath = EndpointUrlFactory.normalizePath(path.joinToString("/"))
        val candidates = mutableListOf<EndpointCandidate>()
        hostValues.forEach { hostValue ->
            val host = EndpointUrlFactory.normalizeHost(hostValue)
            if (!EndpointUrlFactory.isValidHost(host)) {
                return SingleRecordResult.Invalid(EndpointUrlFactory.classifyInvalidHost(host), host)
            }
            candidates += EndpointCandidate(host, port, scheme, normalizedPath, record)
        }
        return SingleRecordResult.Ok(candidates)
    }

    /**
     * Splits `a=1;b=2`, `a=1,b=2` and `a=1 b=2` into pairs. Whitespace is only treated as a
     * separator when every fragment still contains `=`, so values containing spaces survive.
     */
    private fun splitKeyValuePairs(record: String): List<Pair<String, String>> {
        val segments = record.split(';', ',', '\n')
            .flatMap { splitOnWhitespaceBetweenPairs(it.trim()) }
            .filter { it.isNotEmpty() }

        return segments.mapNotNull { segment ->
            val separator = segment.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = segment.substring(0, separator).trim().lowercase()
            val value = segment.substring(separator + 1).trim().trim('"')
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }
    }

    private fun splitOnWhitespaceBetweenPairs(segment: String): List<String> {
        if (!segment.contains(' ')) return listOf(segment)
        val fragments = segment.split(' ').filter { it.isNotEmpty() }
        return if (fragments.size > 1 && fragments.all { it.contains('=') }) fragments else listOf(segment)
    }

    private fun candidateKey(candidate: EndpointCandidate): String = buildString {
        append(candidate.scheme?.urlScheme ?: "*")
        append('|')
        append(EndpointUrlFactory.normalizeHost(candidate.host).lowercase())
        append('|')
        append(candidate.port)
        append('|')
        append(EndpointUrlFactory.normalizePath(candidate.path) ?: "")
    }

    private fun EndpointUrlFactory.ParsedUrl.toCandidate(sourceRecord: String) = EndpointCandidate(
        host = EndpointUrlFactory.normalizeHost(host),
        port = port,
        scheme = scheme,
        path = path,
        sourceRecord = sourceRecord,
    )

    private sealed interface SingleRecordResult {
        data class Ok(val candidates: List<EndpointCandidate>) : SingleRecordResult
        data class Invalid(
            val reason: EndpointRejectionReason,
            val detail: String? = null,
        ) : SingleRecordResult
    }
}
