package io.homeassistant.companion.android.common.data.endpoint

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Real-network checks. **Disabled by default** so the normal test suite stays offline and
 * deterministic, and enabled when a real lookup has to be demonstrated:
 *
 * ```
 * HA_REAL_DNS_TEST=1 ./gradlew :common:testDebugUnitTest --tests '*RealDnsLookupTest'
 * ```
 *
 * The record name can be pointed somewhere else with `HA_REAL_DNS_TEST_NAME`. Everything printed
 * here is safe to share: a DNS record holds an address, never a credential (records embedding
 * credentials are rejected while parsing).
 *
 * These tests exist because a passing unit test suite does not prove that a real `TXT` query works.
 * They exercise the same [RawUdpDnsClient] and [DnsMessageCodec] the app uses, against the same
 * public resolver the device would use.
 */
@EnabledIfEnvironmentVariable(named = "HA_REAL_DNS_TEST", matches = "1")
class RealDnsLookupTest {

    private val recordName: String = System.getenv("HA_REAL_DNS_TEST_NAME") ?: "hat.22345678.xyz"

    /** Public resolvers that answer on a mainland-China connection. */
    private fun client() = RawUdpDnsClient(
        serverProvider = {
            listOf(
                InetSocketAddress("223.5.5.5", 53),
                InetSocketAddress("119.29.29.29", 53),
            )
        },
        timeoutMs = 5_000,
    )

    @Test
    fun `a real TXT query produces a usable Home Assistant address`() {
        val result = client().query(recordName, DnsRecordType.TXT)
        println("[real-dns] TXT $recordName -> ${result.status} via ${result.provider}")
        println("[real-dns] attempts=[${result.attemptTrail}]")
        result.answers.forEach { println("[real-dns]   answer ${it.type} ${it.name} ttl=${it.ttlSeconds} value=${it.value}") }

        assertTrue(result.isSuccess, "the real TXT query must succeed, got ${result.status} (${result.error})")

        val values = result.answers.filter { it.type == DnsRecordType.TXT }.map { it.value }
        assertFalse(values.isEmpty(), "the record must carry at least one TXT string")

        val parsed = TxtRecordInterpreter.interpret(
            records = values,
            defaultPort = EndpointSettings.DEFAULT_HOME_ASSISTANT_PORT,
            preferredScheme = EndpointScheme.HTTPS,
        )
        println("[real-dns] candidates=${parsed.candidates}")
        println("[real-dns] rejected=${parsed.rejected}")

        // Every published value has to end up either as a candidate or as a rejection with a reason.
        assertTrue(
            parsed.isUsable || parsed.rejected.isNotEmpty(),
            "a record must never be silently dropped",
        )

        parsed.candidates.forEach { candidate ->
            // The resolver probes HTTPS first and only uses plaintext with consent, so the URL that
            // would be generated here is the HTTPS one.
            val endpoint = ResolvedEndpoint(
                scheme = candidate.scheme ?: EndpointScheme.HTTPS,
                host = candidate.host,
                port = candidate.port,
                path = candidate.path,
            )
            println("[real-dns] generated url=${endpoint.url}")
            assertTrue(endpoint.url.startsWith("http://") || endpoint.url.startsWith("https://"), endpoint.url)
            assertNull(EndpointUrlFactory.validate(endpoint), "the generated URL must be valid: ${endpoint.url}")
        }
    }

    @Test
    fun `a real A query decodes a message with an alias`() {
        // www.qq.com is answered with a CNAME chain, which exercises name compression and the alias
        // handling on a real message.
        val result = client().query("www.qq.com", DnsRecordType.A)
        println("[real-dns] A www.qq.com -> ${result.status} via ${result.provider}")
        result.answers.forEach { println("[real-dns]   answer ${it.type} ${it.value}") }
        println("[real-dns] canonical=${result.canonicalNames}")

        assertTrue(result.isSuccess, "the real A query must succeed, got ${result.status} (${result.error})")
        assertTrue(result.answers.any { it.type == DnsRecordType.A }, "at least one A record must be decoded")
        result.answers.filter { it.type == DnsRecordType.A }.forEach { answer ->
            assertTrue(
                EndpointUrlFactory.isValidIpv4Literal(answer.value),
                "a decoded A record must be a valid IPv4 literal, got '${answer.value}'",
            )
        }
    }

    @Test
    fun `the whole chain turns a real record into a reachable Home Assistant address`() {
        // End to end over the real network: a real UDP DNS query, the real interpreter, the real
        // validation and the real fallback policy. Only the probe transport differs from the app:
        // this test uses java.net instead of OkHttp, because OkHttp's TLS stack cannot run on a
        // desktop JVM. The measured behaviour of the live endpoint is the same either way.
        val resolver = EndpointResolver(
            dnsClient = client(),
            probe = JvmEndpointProbe,
        )
        val settings = EndpointSettings(
            mode = EndpointResolutionMode.TXT,
            txtRecordName = recordName,
            allowHttpFallback = true,
            preferHttps = true,
            defaultPort = EndpointSettings.DEFAULT_HOME_ASSISTANT_PORT,
        )

        // Pass 1: no consent yet. HTTPS is expected to fail against a plain HTTP listener.
        val firstPass = resolver.resolve(settings)
        println("[real-dns] pass1 success=${firstPass.success} url=${firstPass.endpoint?.url}")
        println("[real-dns] pass1 pending=${firstPass.pendingPlaintextEndpoint?.url}")
        println("[real-dns] pass1 error=${firstPass.error?.reason} / ${firstPass.error?.message}")
        println("[real-dns] pass1 diagnostics=${firstPass.error?.diagnostics}")

        // Pass 2: the user accepted the plaintext connection the first pass offered.
        val consented = resolver.resolve(settings, plaintextConsent = true)
        println("[real-dns] pass2 success=${consented.success} url=${consented.endpoint?.url}")

        assertTrue(consented.success, "the real record must resolve once plaintext is accepted")
        val url = consented.endpoint?.url
        assertNotNull(url)

        // The address the user ends up with has to be the live Home Assistant front end, so it is
        // requested for real instead of being taken on trust.
        assertTrue(url!!.startsWith("http://"), "the live record carries no TLS, got $url")
        val status = JvmEndpointProbe.httpStatusOf(url)
        println("[real-dns] GET $url -> $status")
        assertEquals(200, status)
    }
}

/**
 * A real [EndpointProbe] built on `java.net`. It opens a real TLS handshake for `https` endpoints and
 * a real HTTP request for `http` ones, and classifies failures with the same policy the app uses.
 */
private object JvmEndpointProbe : EndpointProbe {

    override fun probe(endpoint: ResolvedEndpoint): EndpointProbeFailure {
        if (endpoint.scheme == EndpointScheme.HTTPS) {
            return probeTls(endpoint)
        }
        val status = httpStatusOf(endpoint.url) ?: return EndpointProbeFailure.UNKNOWN_NETWORK_ERROR
        return HttpsFallbackPolicy.classifyHttpStatus(status)
    }

    private fun probeTls(endpoint: ResolvedEndpoint): EndpointProbeFailure {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        try {
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), TIMEOUT_MS)
            socket.startHandshake()
            return EndpointProbeFailure.NONE
        } catch (e: SSLException) {
            return HttpsFallbackPolicy.classifyTlsFailureMessage(e.message)
        } catch (e: IOException) {
            return HttpsFallbackPolicy.classifyTlsFailureMessage(e.message)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Returns the HTTP status code, or `null` when the endpoint did not answer. */
    fun httpStatusOf(url: String): Int? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "HomeAssistant-Android/endpoint-probe")
            connection.responseCode
        } catch (e: IOException) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private const val TIMEOUT_MS = 10_000
}
