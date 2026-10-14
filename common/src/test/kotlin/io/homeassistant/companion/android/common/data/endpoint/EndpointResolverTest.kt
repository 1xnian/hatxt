package io.homeassistant.companion.android.common.data.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End to end coverage of the resolution pipeline, sections 4, 5, 6 and 8 of the specification.
 *
 * The DNS client and the reachability probe are replaced by deterministic fakes so the pipeline can
 * be asserted without any network access.
 */
class EndpointResolverTest {

    private class FakeDnsClient(
        private val answers: Map<Pair<String, DnsRecordType>, DnsQueryResult> = emptyMap(),
    ) : DnsClient {
        val queries = mutableListOf<Pair<String, DnsRecordType>>()

        override fun query(name: String, type: DnsRecordType): DnsQueryResult {
            queries += name to type
            return answers[name to type] ?: DnsQueryResult(status = DnsQueryStatus.NXDOMAIN)
        }
    }

    private class FakeProbe(
        private val failures: Map<String, EndpointProbeFailure> = emptyMap(),
    ) : EndpointProbe {
        val probed = mutableListOf<String>()

        override fun probe(endpoint: ResolvedEndpoint): EndpointProbeFailure {
            probed += endpoint.url
            return failures[endpoint.url] ?: EndpointProbeFailure.NONE
        }
    }

    private fun txt(answer: String) =
        DnsQueryResult(listOf(DnsAnswer(DnsRecordType.TXT, answer)), DnsQueryStatus.NOERROR)

    private fun a(answer: String) =
        DnsQueryResult(listOf(DnsAnswer(DnsRecordType.A, answer)), DnsQueryStatus.NOERROR)

    private fun aaaa(answer: String) =
        DnsQueryResult(listOf(DnsAnswer(DnsRecordType.AAAA, answer)), DnsQueryStatus.NOERROR)

    private fun srv(vararg answers: String) =
        DnsQueryResult(answers.map { DnsAnswer(DnsRecordType.SRV, it) }, DnsQueryStatus.NOERROR)

    private fun txtSettings(
        recordName: String = "ha.example.com",
        allowHttpFallback: Boolean = false,
        preferHttps: Boolean = true,
        defaultPort: Int = 8123,
    ) = EndpointSettings(
        mode = EndpointResolutionMode.TXT,
        txtRecordName = recordName,
        allowHttpFallback = allowHttpFallback,
        preferHttps = preferHttps,
        defaultPort = defaultPort,
    )

    @Test
    fun `fixed mode never queries dns`() {
        val dns = FakeDnsClient()
        val resolver = EndpointResolver(dns)

        val result = resolver.resolveFixed("https://ha.example.com:8123")

        assertTrue(result.success)
        assertEquals("https://ha.example.com:8123", result.endpoint?.url)
        assertTrue(dns.queries.isEmpty())
    }

    @Test
    fun `fixed mode rejects an invalid url`() {
        val result = EndpointResolver(FakeDnsClient()).resolveFixed("not a url")
        assertFalse(result.success)
        assertEquals(EndpointRejectionReason.INVALID_URL, result.error?.reason)
    }

    @Test
    fun `txt record with a literal ip produces a working https endpoint`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("203.0.113.10:8123")))
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertTrue(result.success)
        assertEquals("https://203.0.113.10:8123", result.endpoint?.url)
        assertEquals(EndpointScheme.HTTPS, result.endpoint?.scheme)
        assertEquals("203.0.113.10", result.endpoint?.ip)
    }

    @Test
    fun `txt record with a host name keeps the host name so tls validation keeps working`() {
        val dns = FakeDnsClient(
            mapOf(
                ("ha.example.com" to DnsRecordType.TXT) to txt("ha.example.com:8123"),
                ("ha.example.com" to DnsRecordType.A) to a("203.0.113.10"),
            ),
        )
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertTrue(result.success)
        // The URL host stays the name, the IP is only used for diagnostics.
        assertEquals("https://ha.example.com:8123", result.endpoint?.url)
        assertEquals("ha.example.com", result.endpoint?.host)
        assertEquals("203.0.113.10", result.endpoint?.ip)
    }

    @Test
    fun `host name resolution falls back to aaaa when no a record exists`() {
        val dns = FakeDnsClient(
            mapOf(
                ("ha.example.com" to DnsRecordType.TXT) to txt("ha.example.com:8123"),
                ("ha.example.com" to DnsRecordType.AAAA) to aaaa("2001:db8::10"),
            ),
        )
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertTrue(result.success)
        assertEquals("2001:db8::10", result.endpoint?.ip)
        assertEquals("https://ha.example.com:8123", result.endpoint?.url)
    }

    @Test
    fun `an unresolvable host name yields no endpoint`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("missing.example.com:8123")))
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertFalse(result.success)
        assertNull(result.endpoint)
        assertEquals(EndpointRejectionReason.SRV_ALL_CANDIDATES_INVALID, result.error?.reason)
    }

    @Test
    fun `a failed dns lookup is reported as such`() {
        val dns = FakeDnsClient(
            mapOf(("ha.example.com" to DnsRecordType.TXT) to DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR)),
        )
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertFalse(result.success)
        assertEquals(EndpointRejectionReason.DNS_LOOKUP_FAILED, result.error?.reason)
    }

    @Test
    fun `a candidate that fails on https is skipped in favour of the next one`() {
        val dns = FakeDnsClient(
            mapOf(
                ("ha.example.com" to DnsRecordType.TXT) to DnsQueryResult(
                    listOf(
                        DnsAnswer(DnsRecordType.TXT, "203.0.113.10:8123"),
                        DnsAnswer(DnsRecordType.TXT, "203.0.113.11:8123"),
                    ),
                    DnsQueryStatus.NOERROR,
                ),
            ),
        )
        val probe = FakeProbe(mapOf("https://203.0.113.10:8123" to EndpointProbeFailure.CONNECT_TIMEOUT))
        val result = EndpointResolver(dns, probe).resolve(txtSettings())

        assertTrue(result.success)
        assertEquals("https://203.0.113.11:8123", result.endpoint?.url)
        assertTrue(probe.probed.contains("https://203.0.113.10:8123"))
        // A timeout is ambiguous and must never lead to a plaintext attempt.
        assertFalse(probe.probed.any { it.startsWith("http://") })
    }

    @Test
    fun `an empty record name is rejected before any lookup`() {
        val dns = FakeDnsClient()
        val result = EndpointResolver(dns).resolve(txtSettings(recordName = "  "))

        assertFalse(result.success)
        assertTrue(dns.queries.isEmpty())
    }

    @Test
    fun `srv record uses the target host and the srv port`() {
        val dns = FakeDnsClient(
            mapOf(
                ("_8123._tcp.ha.example.com" to DnsRecordType.SRV) to srv("10 60 8123 node1.ha.example.com."),
                ("node1.ha.example.com" to DnsRecordType.A) to a("203.0.113.10"),
            ),
        )
        val settings = EndpointSettings(
            mode = EndpointResolutionMode.SRV,
            srvRecordName = "_8123._tcp.ha.example.com",
        )

        val result = EndpointResolver(dns, FakeProbe()).resolve(settings)

        assertTrue(result.success)
        assertEquals("https://node1.ha.example.com:8123", result.endpoint?.url)
        assertEquals("203.0.113.10", result.endpoint?.ip)
    }

    @Test
    fun `srv target of dot reports the service as unavailable without building a url`() {
        val dns = FakeDnsClient(
            mapOf(("_8123._tcp.ha.example.com" to DnsRecordType.SRV) to srv("0 0 0 .")),
        )
        val settings = EndpointSettings(
            mode = EndpointResolutionMode.SRV,
            srvRecordName = "_8123._tcp.ha.example.com",
        )

        val result = EndpointResolver(dns, FakeProbe()).resolve(settings)

        assertFalse(result.success)
        assertNull(result.endpoint)
        assertEquals(EndpointRejectionReason.SRV_SERVICE_UNAVAILABLE, result.error?.reason)
    }

    @Test
    fun `srv target that cannot be resolved to an ip is rejected`() {
        val dns = FakeDnsClient(
            mapOf(("_8123._tcp.ha.example.com" to DnsRecordType.SRV) to srv("10 0 8123 ghost.ha.example.com.")),
        )
        val settings = EndpointSettings(
            mode = EndpointResolutionMode.SRV,
            srvRecordName = "_8123._tcp.ha.example.com",
        )

        val result = EndpointResolver(dns, FakeProbe()).resolve(settings)

        assertFalse(result.success)
        assertEquals(EndpointRejectionReason.SRV_ALL_CANDIDATES_INVALID, result.error?.reason)
    }

    @Test
    fun `srv picks the lowest priority target first`() {
        val dns = FakeDnsClient(
            mapOf(
                ("_8123._tcp.ha.example.com" to DnsRecordType.SRV) to srv(
                    "20 0 8123 backup.ha.example.com.",
                    "10 0 8123 primary.ha.example.com.",
                ),
                ("primary.ha.example.com" to DnsRecordType.A) to a("203.0.113.10"),
                ("backup.ha.example.com" to DnsRecordType.A) to a("203.0.113.11"),
            ),
        )
        val settings = EndpointSettings(
            mode = EndpointResolutionMode.SRV,
            srvRecordName = "_8123._tcp.ha.example.com",
        )

        val result = EndpointResolver(dns, FakeProbe()).resolve(settings)

        assertTrue(result.success)
        assertEquals("https://primary.ha.example.com:8123", result.endpoint?.url)
    }

    @Test
    fun `srv switches to the next candidate when the first one is unreachable`() {
        val dns = FakeDnsClient(
            mapOf(
                ("_8123._tcp.ha.example.com" to DnsRecordType.SRV) to srv(
                    "10 0 8123 primary.ha.example.com.",
                    "20 0 8123 backup.ha.example.com.",
                ),
                ("primary.ha.example.com" to DnsRecordType.A) to a("203.0.113.10"),
                ("backup.ha.example.com" to DnsRecordType.A) to a("203.0.113.11"),
            ),
        )
        val settings = EndpointSettings(
            mode = EndpointResolutionMode.SRV,
            srvRecordName = "_8123._tcp.ha.example.com",
            allowHttpFallback = true,
        )
        val probe = FakeProbe(mapOf("https://primary.ha.example.com:8123" to EndpointProbeFailure.NO_ROUTE_TO_HOST))

        val result = EndpointResolver(dns, probe).resolve(settings, plaintextConsent = true)

        assertTrue(result.success)
        assertEquals("https://backup.ha.example.com:8123", result.endpoint?.url)
    }

    @Test
    fun `https refused downgrades to http when enabled and consented`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("203.0.113.10:8123")))
        val probe = FakeProbe(mapOf("https://203.0.113.10:8123" to EndpointProbeFailure.CONNECTION_REFUSED))

        val result = EndpointResolver(dns, probe).resolve(txtSettings(allowHttpFallback = true), plaintextConsent = true)

        assertTrue(result.success)
        assertEquals("http://203.0.113.10:8123", result.endpoint?.url)
        assertEquals(EndpointScheme.HTTP, result.endpoint?.scheme)
        assertNotNull(result.endpoint?.downgradeReason)
    }

    @Test
    fun `https refused does not downgrade without plaintext consent`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("203.0.113.10:8123")))
        val probe = FakeProbe(mapOf("https://203.0.113.10:8123" to EndpointProbeFailure.CONNECTION_REFUSED))

        val result = EndpointResolver(dns, probe).resolve(txtSettings(allowHttpFallback = true), plaintextConsent = false)

        assertFalse(result.success)
        assertNull(result.endpoint)
        assertEquals(EndpointRejectionReason.PLAINTEXT_CONFIRMATION_REQUIRED, result.error?.reason)
        assertNotNull(result.fallbackEvaluation)
        assertEquals(FallbackDecision.REQUIRE_USER_CONFIRMATION, result.fallbackEvaluation?.decision)
    }

    @Test
    fun `https refused does not downgrade when the feature is disabled`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("203.0.113.10:8123")))
        val probe = FakeProbe(mapOf("https://203.0.113.10:8123" to EndpointProbeFailure.CONNECTION_REFUSED))

        val result = EndpointResolver(dns, probe).resolve(txtSettings(allowHttpFallback = false), plaintextConsent = true)

        assertFalse(result.success)
        assertFalse(probe.probed.any { it.startsWith("http://") })
    }

    @Test
    fun `an authentication failure on https never downgrades`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("203.0.113.10:8123")))
        val probe = FakeProbe(mapOf("https://203.0.113.10:8123" to EndpointProbeFailure.AUTHENTICATION_FAILURE))

        val result = EndpointResolver(dns, probe).resolve(txtSettings(allowHttpFallback = true), plaintextConsent = true)

        assertFalse(result.success)
        assertFalse(probe.probed.any { it.startsWith("http://") }, "a 401 must not be read as missing https")
    }

    @Test
    fun `an untrusted certificate reports the dedicated reason and never downgrades`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("203.0.113.10:8123")))
        val probe = FakeProbe(mapOf("https://203.0.113.10:8123" to EndpointProbeFailure.TLS_CERT_UNTRUSTED))

        val result = EndpointResolver(dns, probe).resolve(txtSettings(allowHttpFallback = true), plaintextConsent = true)

        assertFalse(result.success)
        assertEquals(EndpointRejectionReason.TLS_CERTIFICATE_NOT_TRUSTED, result.error?.reason)
        assertFalse(probe.probed.any { it.startsWith("http://") })
    }

    @Test
    fun `a downgrade is only applied when the http endpoint is reachable too`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("203.0.113.10:8123")))
        val probe = FakeProbe(
            mapOf(
                "https://203.0.113.10:8123" to EndpointProbeFailure.CONNECTION_REFUSED,
                "http://203.0.113.10:8123" to EndpointProbeFailure.CONNECTION_REFUSED,
            ),
        )

        val result = EndpointResolver(dns, probe).resolve(txtSettings(allowHttpFallback = true), plaintextConsent = true)

        assertFalse(result.success)
        assertNull(result.endpoint)
    }

    @Test
    fun `a record that explicitly asks for http is used as is`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("http://203.0.113.10:8123")))
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertTrue(result.success)
        assertEquals("http://203.0.113.10:8123", result.endpoint?.url)
    }

    @Test
    fun `rejected records are surfaced for diagnostics`() {
        val dns = FakeDnsClient(
            mapOf(
                ("ha.example.com" to DnsRecordType.TXT) to DnsQueryResult(
                    listOf(
                        DnsAnswer(DnsRecordType.TXT, "999.1.1.1:8123"),
                        DnsAnswer(DnsRecordType.TXT, "203.0.113.10:8123"),
                    ),
                    DnsQueryStatus.NOERROR,
                ),
            ),
        )
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertTrue(result.success)
        assertTrue(result.rejected.any { it.reason == EndpointRejectionReason.INVALID_IP_ADDRESS })
    }
}
