package io.homeassistant.companion.android.common.data.endpoint

/**
 * Builds and validates `http(s)` URLs for a resolved endpoint.
 *
 * The factory is deliberately free of Android and OkHttp dependencies so that the URL rules can be
 * unit tested on a plain JVM. Address parsing never touches the network: literals are recognised
 * syntactically, host names are only checked for syntax. Resolving a host name to an IP is the job
 * of [DnsEndpointResolver].
 */
object EndpointUrlFactory {

    /** Lowest usable TCP port. Port 0 is not a connectable port. */
    const val MIN_PORT = 1

    /** Highest usable TCP port. */
    const val MAX_PORT = 65535

    private val SCHEME_PREFIX_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://")
    private val HOST_PORT_REGEX = Regex("^(\\[[0-9A-Fa-f:.]+]|[^:/?#@\\s]+):(\\d{1,6})$")
    private val SRV_LABEL_REGEX = Regex("^_[a-zA-Z0-9][a-zA-Z0-9\\-]{0,62}$")
    private val HOSTNAME_REGEX = Regex(
        "^(?=.{1,253}$)" +
            "([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)" +
            "(\\.([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?))*\\.?$",
    )

    fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

    /**
     * Scheme prefix of a URL-like value, lower case and without `://`, or `null` when there is none.
     *
     * Used to tell "a scheme this app cannot speak" apart from "a URL that is broken", which are two
     * very different messages for the user.
     */
    fun schemePrefixOf(raw: String): String? = SCHEME_PREFIX_REGEX.find(raw.trim())?.groupValues?.get(1)?.lowercase()

    /** Strict dotted-quad check. Leading zeros are rejected to rule out octal/hex ambiguity. */
    fun isValidIpv4Literal(raw: String): Boolean {
        val parts = raw.trim().split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            when {
                part.isEmpty() -> false
                !part.all { it.isDigit() } -> false
                part.length > 1 && part.startsWith("0") -> false
                else -> (part.toIntOrNull() ?: return@all false) in 0..255
            }
        }
    }

    /**
     * Strict IPv6 literal check, supporting `::` compression and a trailing IPv4 form such as
     * `::ffff:192.0.2.1`. Brackets are optional. Zone indices (`fe80::1%eth0`) are rejected because
     * they cannot be represented in a URL host.
     */
    fun isValidIpv6Literal(raw: String): Boolean {
        val value = raw.trim().removeSurrounding("[", "]")
        if (value.isEmpty() || !value.contains(':')) return false
        // A zone index is not a valid URL host component.
        if (value.contains('%')) return false

        val compression = value.indexOf("::")
        if (compression >= 0 && value.indexOf("::", compression + 1) >= 0) return false

        val parts = if (compression >= 0) {
            listOf(value.substring(0, compression), value.substring(compression + 2))
        } else {
            listOf(value)
        }

        var groupCount = 0
        parts.forEachIndexed { partIndex, part ->
            if (part.isEmpty()) return@forEachIndexed
            val groups = part.split(':')
            groups.forEachIndexed { groupIndex, group ->
                if (group.isEmpty()) return false
                val isLastGroupOverall = partIndex == parts.lastIndex && groupIndex == groups.lastIndex
                when {
                    isLastGroupOverall && group.contains('.') && isValidIpv4Literal(group) -> groupCount += 2
                    group.length <= 4 && group.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ->
                        groupCount += 1

                    else -> return false
                }
            }
        }

        return if (compression >= 0) groupCount < 8 else groupCount == 8
    }

    fun isIpLiteral(raw: String): Boolean = isValidIpv4Literal(raw) || isValidIpv6Literal(raw)

    /** RFC 1123 host name, including a trailing dot and single-label names such as `nas`. */
    fun isValidHostname(raw: String): Boolean {
        val value = raw.trim().trimEnd('.')
        return value.isNotEmpty() && HOSTNAME_REGEX.matches(value)
    }

    /** True when the value has the shape of a dotted quad, regardless of whether it is valid. */
    fun looksLikeIpv4(raw: String): Boolean {
        val parts = raw.trim().split('.')
        return parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }
    }

    /**
     * An SRV query name as defined by RFC 2782: `_service._proto.example.com`.
     *
     * At least three labels are required and the first two must start with an underscore. A plain
     * host name is deliberately rejected: typing `ha.example.com` into the SRV field would return
     * an empty answer set and look like a DNS outage instead of a configuration mistake.
     */
    fun isValidSrvQueryName(raw: String): Boolean {
        val labels = raw.trim().trimEnd('.').split('.')
        if (labels.size < 3) return false
        if (!SRV_LABEL_REGEX.matches(labels[0])) return false
        if (!SRV_LABEL_REGEX.matches(labels[1])) return false
        return isValidHostname(labels.drop(2).joinToString("."))
    }

    /**
     * A host is usable when it is a valid IP literal or a valid host name.
     *
     * Values that *look* like an IP but fail strict validation are rejected instead of being
     * silently accepted as a host name. That keeps `999.1.1.1:8123` or `1:2:3` from being used as
     * a connect target.
     */
    fun isValidHost(raw: String): Boolean {
        val value = raw.trim().removeSurrounding("[", "]")
        if (value.isEmpty()) return false
        if (isIpLiteral(value)) return true
        // A malformed dotted quad is a broken IP address, not a host name.
        if (looksLikeIpv4(value)) return false
        // A colon implies an IP literal, and it was not a valid one.
        if (value.contains(':')) return false
        return isValidHostname(value)
    }

    /** `host:port`, bracketing IPv6 literals so the result can be embedded in a URL. */
    fun buildAuthority(host: String, port: Int): String {
        val normalized = normalizeHost(host)
        return if (isValidIpv6Literal(normalized)) "[$normalized]:$port" else "$normalized:$port"
    }

    /** Removes the brackets of an IPv6 literal, if present. */
    fun normalizeHost(host: String): String = host.trim().removeSurrounding("[", "]")

    /**
     * Normalises a path component: strips surrounding slashes and whitespace. Returns `null` when
     * the result is empty, meaning "no path".
     */
    fun normalizePath(path: String?): String? {
        val trimmed = path?.trim()?.trim('/') ?: return null
        return trimmed.ifEmpty { null }
    }

    /**
     * Builds the `scheme://host[:port][/path]` URL.
     *
     * The port is omitted when it equals the scheme default to avoid redundant `:443`/`:80`
     * segments, and the path is normalised so a URL can never contain duplicated slashes. The input
     * is expected to be validated already; use [validate] for an explicit check.
     */
    fun buildUrl(endpoint: ResolvedEndpoint): String {
        val host = normalizeHost(endpoint.host)
        val renderedHost = if (isValidIpv6Literal(host)) "[$host]" else host
        val portPart = if (endpoint.port == endpoint.scheme.defaultPort) "" else ":${endpoint.port}"
        val pathPart = normalizePath(endpoint.path)?.let { "/$it" } ?: ""
        return "${endpoint.scheme.urlScheme}://$renderedHost$portPart$pathPart"
    }

    /** Returns the reason why [endpoint] cannot be used, or `null` when it is valid. */
    fun validate(endpoint: ResolvedEndpoint): EndpointRejectionReason? {
        val host = normalizeHost(endpoint.host)
        return when {
            host.isEmpty() -> EndpointRejectionReason.MISSING_HOST
            !isValidHost(host) -> classifyInvalidHost(host)
            !isValidPort(endpoint.port) -> EndpointRejectionReason.INVALID_PORT
            else -> null
        }
    }

    /**
     * Classifies a host that failed validation so the UI can tell a broken IP address apart from a
     * broken host name.
     */
    fun classifyInvalidHost(raw: String): EndpointRejectionReason {
        val value = raw.trim().removeSurrounding("[", "]")
        return if (value.contains(':') || looksLikeIpv4(value)) {
            EndpointRejectionReason.INVALID_IP_ADDRESS
        } else {
            EndpointRejectionReason.INVALID_HOSTNAME
        }
    }

    /** Parses a full `scheme://host[:port][/path][?query][#fragment]` URL without touching DNS. */
    fun parseUrl(raw: String): ParsedUrl? {
        val value = raw.trim()
        val schemeMatch = SCHEME_PREFIX_REGEX.find(value) ?: return null
        val scheme = EndpointScheme.fromUrlScheme(schemeMatch.groupValues[1]) ?: return null

        val remainder = value.substring(schemeMatch.value.length)
        if (remainder.isEmpty()) return null

        // Reject embedded credentials: a TXT record must never carry user:password@host.
        val authorityAndPath = remainder.substringBefore('#')
        if (authorityAndPath.substringBefore('/').contains('@')) return null

        val authority = authorityAndPath.substringBefore('/')
        val rawPath = authorityAndPath.removePrefix(authority)
        val path = rawPath.substringBefore('?').let { normalizePath(it) }

        if (authority.isEmpty()) return null

        val host: String
        val port: Int
        if (authority.startsWith("[")) {
            val closeIndex = authority.indexOf(']')
            if (closeIndex <= 1) return null
            host = authority.substring(1, closeIndex)
            val afterBracket = authority.substring(closeIndex + 1)
            port = when {
                afterBracket.isEmpty() -> scheme.defaultPort
                afterBracket.startsWith(":") -> parsePortOrNull(afterBracket.substring(1)) ?: return null
                else -> return null
            }
            if (!isValidIpv6Literal(host)) return null
        } else {
            val colonIndex = authority.lastIndexOf(':')
            if (colonIndex >= 0) {
                host = authority.substring(0, colonIndex)
                port = parsePortOrNull(authority.substring(colonIndex + 1)) ?: return null
            } else {
                host = authority
                port = scheme.defaultPort
            }
            if (isValidIpv6Literal(host)) return null // an IPv6 literal must be bracketed inside a URL
        }

        if (!isValidHost(host)) return null
        return ParsedUrl(scheme, host, port, path)
    }

    /** Parses a bare `host:port` pair, where host may be a bracketed IPv6 literal. */
    fun parseHostPort(raw: String): Pair<String, Int>? {
        val match = HOST_PORT_REGEX.matchEntire(raw.trim()) ?: return null
        val host = normalizeHost(match.groupValues[1])
        val port = parsePortOrNull(match.groupValues[2]) ?: return null
        if (!isValidHost(host)) return null
        return host to port
    }

    /** Parses a decimal port and rejects out-of-range values such as `0` or `99999`. */
    fun parsePortOrNull(raw: String): Int? {
        val value = raw.trim()
        if (value.isEmpty() || !value.all { it.isDigit() }) return null
        val port = value.toIntOrNull() ?: return null
        return port.takeIf(::isValidPort)
    }

    /** Result of [parseUrl]. */
    data class ParsedUrl(
        val scheme: EndpointScheme,
        val host: String,
        val port: Int,
        val path: String? = null,
    ) {
        fun toEndpoint(): ResolvedEndpoint = ResolvedEndpoint(scheme, host, port, path = path)
    }
}
