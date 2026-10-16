package io.homeassistant.companion.android.common.data.endpoint

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Resolves DNS records.
 *
 * Implementations are **blocking**; callers must invoke them off the main thread. They are
 * synchronous on purpose so the whole resolution pipeline stays testable on a plain JVM.
 */
interface DnsClient {
    fun query(name: String, type: DnsRecordType): DnsQueryResult
}

/**
 * DNS over HTTPS client speaking the JSON API (`application/dns-json`) used by every major public
 * resolver.
 *
 * ## Why DNS over HTTPS and not the platform resolver alone
 *
 * Android's `InetAddress`/`Network` APIs and OkHttp's `Dns` interface only resolve `A`/`AAAA`, and
 * `android.net.DnsResolver` only exposes types for `A`, `AAAA` and `HTTPS`. `TXT` and `SRV`
 * therefore need another transport when the device's own resolver cannot be used, see
 * [SystemDnsClient] and [RawUdpDnsClient] for those. DoH is the last resort, and it is the only
 * transport that still works when a network rewrites or blocks plain DNS.
 *
 * ## Provider order matters
 *
 * The well known international resolvers are not reachable on every network. On a mainland-China
 * connection `cloudflare-dns.com` and `dns.google` both fail while domestic resolvers answer in
 * milliseconds, so the domestic ones are asked first and the international ones are kept as a
 * fallback for everywhere else. A resolver that is blocked fails fast, and every attempt is
 * recorded in [DnsQueryResult.attempts] so the reason is visible instead of being reported as a
 * generic failure.
 *
 * Nothing is assumed about the response: every record is validated by [TxtRecordInterpreter] or
 * [SrvRecordInterpreter] before it can influence a URL.
 */
class DohDnsClient(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : DnsClient {

    override fun query(name: String, type: DnsRecordType): DnsQueryResult {
        val normalizedName = name.trim().trimEnd('.')
        if (normalizedName.isEmpty()) {
            return DnsQueryResult(
                status = DnsQueryStatus.MALFORMED,
                error = "Empty DNS record name",
            )
        }

        val attempts = mutableListOf<DnsAttempt>()
        var best: DnsQueryResult? = null
        var bestRank = Int.MIN_VALUE

        endpoints.forEach { endpoint ->
            val started = clock()
            val result = try {
                queryOnce(endpoint, normalizedName, type)
            } catch (e: IOException) {
                DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR, provider = endpoint, error = e.describeForDiagnostics())
            } catch (e: RuntimeException) {
                DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR, provider = endpoint, error = e.describeForDiagnostics())
            }
            val duration = clock() - started
            attempts += DnsAttempt(providerName(endpoint), result.status, duration, result.error)

            if (result.status == DnsQueryStatus.NOERROR) {
                return result.copy(attempts = attempts.toList())
            }

            // EMPTY and NXDOMAIN do not stop the chain: resolvers disagree while a record
            // propagates, and asking a second one costs a round trip that is already budgeted for.
            val rank = rankOf(result.status)
            if (rank > bestRank) {
                bestRank = rank
                best = result.copy(attempts = attempts.toList())
            }
        }

        return (best ?: DnsQueryResult(
            status = DnsQueryStatus.NETWORK_ERROR,
            error = "No DNS over HTTPS provider configured",
        )).copy(attempts = attempts.toList())
    }

    private fun queryOnce(endpoint: String, name: String, type: DnsRecordType): DnsQueryResult {
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8.name())
        val url = URL("$endpoint?name=$encodedName&type=${type.name}")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/dns-json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return DnsQueryResult(
                    status = if (responseCode == 429) DnsQueryStatus.REFUSED else DnsQueryStatus.SERVER_FAILURE,
                    provider = providerName(endpoint),
                    error = "DNS over HTTPS request returned HTTP $responseCode",
                )
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return DohResponseParser.parse(body, providerName(endpoint))
        } finally {
            connection.disconnect()
        }
    }

    /** Same ordering as the fallback chain: report the most actionable outcome. */
    private fun rankOf(status: DnsQueryStatus): Int = when (status) {
        DnsQueryStatus.NOERROR -> 100
        DnsQueryStatus.EMPTY -> 90
        DnsQueryStatus.NXDOMAIN -> 80
        DnsQueryStatus.REFUSED -> 60
        DnsQueryStatus.SERVER_FAILURE -> 50
        DnsQueryStatus.MALFORMED -> 40
        DnsQueryStatus.TIMEOUT -> 30
        DnsQueryStatus.UNSUPPORTED -> 20
        DnsQueryStatus.NETWORK_ERROR -> 10
    }

    companion object {
        const val USER_AGENT = "HomeAssistant-Android/endpoint-resolver"

        /**
         * A blocked resolver usually fails the TLS handshake immediately; a reachable one on a
         * mobile network answers in well under a second. These values keep a dead provider from
         * eating the whole lookup budget.
         */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000
        const val DEFAULT_READ_TIMEOUT_MS = 4_000

        /**
         * Resolvers queried in order until one produces an authoritative answer.
         *
         * Domestic resolvers come first because they are the ones that answer on a mainland-China
         * connection, where the international ones are routinely unreachable. Measured on such a
         * connection: `doh.pub` answers in ~0.3s, `dns.alidns.com` in a few seconds,
         * `cloudflare-dns.com` fails the TLS handshake instantly and `dns.google` times out.
         * The order is part of the fix for "the same record resolves in `nslookup` but not in the
         * app".
         */
        val DEFAULT_ENDPOINTS: List<String> = listOf(
            "https://doh.pub/dns-query",
            "https://dns.alidns.com/resolve",
            "https://cloudflare-dns.com/dns-query",
            "https://dns.google/resolve",
        )

        /** Short host label used in messages, for example `doh:doh.pub`. */
        internal fun providerName(endpoint: String): String {
            val host = endpoint.substringAfter("://").substringBefore('/')
            return "doh:$host"
        }
    }
}

/**
 * Parses the JSON response of the DoH "dns-json" API.
 *
 * Kept separate from the network code so the parsing rules are unit testable, including the
 * quirks of TXT payloads: the value arrives as a quoted string whose content is itself quoted and
 * escaped, and a single record may be split into several character-strings.
 */
object DohResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val QUOTED_CHUNK_REGEX = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    fun parse(body: String, provider: String? = null): DnsQueryResult {
        val response = try {
            json.decodeFromString<DohResponse>(body)
        } catch (e: SerializationException) {
            return DnsQueryResult(
                status = DnsQueryStatus.MALFORMED,
                provider = provider,
                error = e.message,
            )
        }

        val status = when (response.status) {
            0 -> if (response.answer.isNullOrEmpty()) DnsQueryStatus.EMPTY else DnsQueryStatus.NOERROR
            2 -> DnsQueryStatus.SERVER_FAILURE
            3 -> DnsQueryStatus.NXDOMAIN
            5 -> DnsQueryStatus.REFUSED
            1 -> DnsQueryStatus.MALFORMED
            else -> DnsQueryStatus.SERVER_FAILURE
        }

        if (status != DnsQueryStatus.NOERROR) {
            return DnsQueryResult(status = status, provider = provider)
        }

        val canonicalNames = mutableListOf<String>()
        val answers = response.answer.orEmpty().mapNotNull { answer ->
            val type = DnsRecordType.fromCode(answer.type) ?: return@mapNotNull null
            val raw = answer.data ?: return@mapNotNull null
            if (type == DnsRecordType.CNAME) {
                raw.trim().trimEnd('.').takeIf { it.isNotEmpty() }?.let { canonicalNames += it }
                return@mapNotNull null
            }
            val value = if (type == DnsRecordType.TXT) normalizeTxtValue(raw) else raw.trim()
            if (value.isEmpty()) return@mapNotNull null
            DnsAnswer(type = type, value = value, name = answer.name, ttlSeconds = answer.ttl)
        }

        if (answers.isEmpty()) {
            return DnsQueryResult(
                status = DnsQueryStatus.EMPTY,
                provider = provider,
                canonicalNames = canonicalNames,
            )
        }
        return DnsQueryResult(
            answers = answers,
            status = DnsQueryStatus.NOERROR,
            provider = provider,
            canonicalNames = canonicalNames,
        )
    }

    /**
     * Normalises a TXT payload: strips the enclosing quotes, unescapes `\"` and `\\`, and joins a
     * record that DNS split into several character-strings.
     *
     * RFC 1035 section 3.3.14 defines TXT-DATA as a sequence of character-strings whose value is the
     * plain concatenation. The JSON API reports the pieces in zone-file presentation form, where
     * they are quoted and separated by a space that is *not* part of the value, so the pieces are
     * joined with no separator. That makes the JSON path produce exactly the same bytes the
     * wire-format decoder in [DnsMessageCodec] produces, and it is why the TXT interpreter never has
     * to guess about missing whitespace: any space it sees is really in the record.
     */
    fun normalizeTxtValue(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains('"')) return unescape(trimmed)

        val chunks = QUOTED_CHUNK_REGEX.findAll(trimmed).map { unescape(it.groupValues[1]) }.toList()
        if (chunks.isEmpty()) return unescape(trimmed)
        return chunks.joinToString(separator = "").trim()
    }

    private fun unescape(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '\\' && index + 1 < value.length) {
                val next = value[index + 1]
                if (next == '\\' || next == '"') {
                    builder.append(next)
                    index += 2
                    continue
                }
            }
            builder.append(current)
            index += 1
        }
        return builder.toString()
    }

    @Serializable
    private data class DohResponse(
        @SerialName("Status") val status: Int = -1,
        @SerialName("Answer") val answer: List<DohAnswer>? = null,
    )

    @Serializable
    private data class DohAnswer(
        @SerialName("name") val name: String? = null,
        @SerialName("type") val type: Int = -1,
        @SerialName("TTL") val ttl: Long? = null,
        @SerialName("data") val data: String? = null,
    )
}
