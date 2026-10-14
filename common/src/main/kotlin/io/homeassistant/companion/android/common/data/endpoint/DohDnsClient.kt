package io.homeassistant.companion.android.common.data.endpoint

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Outcome of one DNS query. */
enum class DnsQueryStatus {
    /** The resolver answered and returned at least one record of the requested type. */
    NOERROR,

    /** The resolver answered but the record set is empty. */
    EMPTY,

    /** The name does not exist. */
    NXDOMAIN,

    /** The resolver reported a server failure. A different provider may still answer. */
    SERVER_FAILURE,

    /** The request never produced an answer, for example because the device is offline. */
    NETWORK_ERROR,

    /** The response could not be parsed. */
    MALFORMED,
}

/** Result of a DNS query. */
data class DnsQueryResult(
    val answers: List<DnsAnswer> = emptyList(),
    val status: DnsQueryStatus = DnsQueryStatus.NETWORK_ERROR,
    /** Resolver that produced the answer, for diagnostics. */
    val provider: String? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = status == DnsQueryStatus.NOERROR
}

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
 * DNS over HTTPS client speaking the JSON API used by Cloudflare and Google.
 *
 * ## Why DNS over HTTPS and not the platform resolver
 *
 * Android's `InetAddress`/`Network` APIs and OkHttp's `Dns` interface only resolve `A`/`AAAA`
 * records. They cannot query `SRV` or `TXT`, and the specification explicitly forbids assuming
 * otherwise. `android.net.DnsResolver` (API 29+) can query arbitrary types but requires manual
 * DNS wire format parsing and is unavailable on older devices. DoH is therefore used as the
 * transport, with a second provider as a fallback.
 *
 * Nothing is assumed about the response: every record is validated by [TxtRecordInterpreter] or
 * [SrvRecordInterpreter] before it can influence a URL.
 */
class DohDnsClient(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : DnsClient {

    override fun query(name: String, type: DnsRecordType): DnsQueryResult {
        val normalizedName = name.trim().trimEnd('.')
        if (normalizedName.isEmpty()) {
            return DnsQueryResult(
                status = DnsQueryStatus.MALFORMED,
                error = "Empty DNS record name",
            )
        }

        var lastResult: DnsQueryResult? = null
        endpoints.forEach { endpoint ->
            val result = try {
                queryOnce(endpoint, normalizedName, type)
            } catch (e: IOException) {
                DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR, provider = endpoint, error = e.message)
            } catch (e: RuntimeException) {
                DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR, provider = endpoint, error = e.message)
            }

            when (result.status) {
                DnsQueryStatus.NOERROR -> return result
                DnsQueryStatus.EMPTY,
                DnsQueryStatus.NXDOMAIN,
                DnsQueryStatus.MALFORMED,
                -> return result

                DnsQueryStatus.SERVER_FAILURE,
                DnsQueryStatus.NETWORK_ERROR,
                -> lastResult = result
            }
        }

        return lastResult ?: DnsQueryResult(
            status = DnsQueryStatus.NETWORK_ERROR,
            error = "No DNS over HTTPS provider configured",
        )
    }

    private fun queryOnce(endpoint: String, name: String, type: DnsRecordType): DnsQueryResult {
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8.name())
        val url = URL("$endpoint?name=$encodedName&type=${type.name}")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/dns-json")
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return DnsQueryResult(
                    status = DnsQueryStatus.NETWORK_ERROR,
                    provider = endpoint,
                    error = "DNS over HTTPS request failed with HTTP $responseCode",
                )
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return DohResponseParser.parse(body, endpoint)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
        const val DEFAULT_READ_TIMEOUT_MS = 8_000

        /** Resolvers queried in order until one produces an authoritative answer. */
        val DEFAULT_ENDPOINTS: List<String> = listOf(
            "https://cloudflare-dns.com/dns-query",
            "https://dns.google/resolve",
        )
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
            3 -> DnsQueryStatus.NXDOMAIN
            else -> DnsQueryStatus.SERVER_FAILURE
        }

        if (status != DnsQueryStatus.NOERROR) {
            return DnsQueryResult(status = status, provider = provider)
        }

        val answers = response.answer.orEmpty().mapNotNull { answer ->
            val type = DnsRecordType.fromCode(answer.type) ?: return@mapNotNull null
            val raw = answer.data ?: return@mapNotNull null
            val value = if (type == DnsRecordType.TXT) normalizeTxtValue(raw) else raw.trim()
            if (value.isEmpty()) return@mapNotNull null
            DnsAnswer(type = type, value = value, name = answer.name, ttlSeconds = answer.ttl)
        }

        if (answers.isEmpty()) {
            return DnsQueryResult(status = DnsQueryStatus.EMPTY, provider = provider)
        }
        return DnsQueryResult(answers = answers, status = DnsQueryStatus.NOERROR, provider = provider)
    }

    /**
     * Normalises a TXT payload: strips the enclosing quotes, unescapes `\"` and `\\`, and joins a
     * record that DNS split into several character-strings with a single space.
     */
    fun normalizeTxtValue(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains('"')) return unescape(trimmed)

        val chunks = QUOTED_CHUNK_REGEX.findAll(trimmed).map { unescape(it.groupValues[1]) }.toList()
        if (chunks.isEmpty()) return unescape(trimmed)
        return chunks.joinToString(" ").trim()
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
