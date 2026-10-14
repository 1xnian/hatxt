package io.homeassistant.companion.android.onboarding.manualserver

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Resolves a `txt://<record-name>` input into a Home Assistant HTTP(S) base URL. */
internal object TxtRecordUrlResolver {
    private const val DNS_ENDPOINT = "https://cloudflare-dns.com/dns-query"
    private const val DNS_TXT_TYPE = 16
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * Returns the input unchanged unless it uses the `txt://` scheme.
     * Network calls must run off the main thread.
     */
    fun resolveIfTxt(input: String): String {
        if (!input.startsWith("txt://", ignoreCase = true)) return input

        val recordName = input.removePrefixIgnoreCase("txt://").trim().trimEnd('.')
        require(recordName.isNotBlank() && !recordName.contains('/') && !recordName.contains(':')) {
            "Enter a TXT record name, for example txt://ha.example.com"
        }

        val encodedName = java.net.URLEncoder.encode(recordName, Charsets.UTF_8.name())
        val queryUrl = URL("$DNS_ENDPOINT?name=$encodedName&type=TXT")
        val connection = queryUrl.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/dns-json")
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS

        val payload = try {
            if (connection.responseCode !in 200..299) {
                error("DNS TXT lookup failed (HTTP ${connection.responseCode})")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(payload)
        val answers = json.optJSONArray("Answer") ?: error("No TXT record found for $recordName")
        val values = (0 until answers.length())
            .mapNotNull { answers.optJSONObject(it) }
            .filter { it.optInt("type") == DNS_TXT_TYPE }
            .mapNotNull { it.optString("data").takeIf(String::isNotBlank) }
            .map(::decodeTxtData)

        val value = values.firstOrNull { looksLikeAddress(it) }
            ?: values.firstOrNull()
            ?: error("No TXT answer found for $recordName")
        return toHttpUrl(value)
    }

    private fun decodeTxtData(value: String): String = value
        .removeSurrounding("\"")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .trim()

    private fun looksLikeAddress(value: String): Boolean =
        value.startsWith("http://", true) || value.startsWith("https://", true) ||
            value.startsWith("url=", true) || value.startsWith("ip=", true) ||
            value.startsWith("host=", true) || value.matches(Regex("^[a-zA-Z0-9.-]+:\\d{1,5}$"))

    private fun toHttpUrl(value: String): String {
        val normalized = when {
            value.startsWith("url=", true) -> value.substringAfter('=')
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            else -> {
                val fields = value.split(';', ',', ' ', '\n')
                    .mapNotNull { token ->
                        val separator = token.indexOf('=')
                        if (separator <= 0) {
                            null
                        } else {
                            token.substring(0, separator).trim().lowercase() to
                                token.substring(separator + 1).trim()
                        }
                    }.toMap()
                val host = fields["ip"] ?: fields["host"] ?: fields["address"]
                val port = fields["port"]
                if (host != null && port != null) "$host:$port" else value
            }
        }
        val withScheme = if (normalized.startsWith("http://", true) || normalized.startsWith("https://", true)) {
            normalized
        } else {
            "http://$normalized"
        }
        val parsed = URL(withScheme)
        require(parsed.protocol == "http" || parsed.protocol == "https") { "TXT value must resolve to an HTTP(S) address" }
        require(!parsed.host.isNullOrBlank()) { "TXT value does not contain a valid host" }
        return withScheme.trimEnd('/')
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
