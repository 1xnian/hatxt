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
        // TXT and SRV report their own reason: the stage that failed is part of the error.
        assertEquals(EndpointRejectionReason.TXT_ALL_RECORDS_INVALID, result.error?.reason)
    }

    @Test
    fun `a failed dns lookup is reported as such`() {
        val dns = FakeDnsClient(
            mapOf(("ha.example.com" to DnsRecordType.TXT) to DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR)),
        )
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertFalse(result.success)
        assertEquals(EndpointRejectionReason.DNS_UNREACHABLE, result.error?.reason)
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

    // ---------------------------------------------------------------------------------------------
    // The scenario that was reported as broken: a TXT record whose value points at a Home Assistant
    // instance that only serves plain HTTP, e.g. `117.183.5.29:13481`.
    // ---------------------------------------------------------------------------------------------

    private val plaintextHostUrl = "http://117.183.5.29:13481"
    private val httpsHostUrl = "https://117.183.5.29:13481"

    /** A server that answers plain HTTP and nothing else, i.e. exactly what was measured. */
    private fun plaintextOnlyProbe() = FakeProbe(
        mapOf(httpsHostUrl to EndpointProbeFailure.TLS_NOT_A_TLS_PEER),
    )

    @Test
    fun `a plaintext only record is offered to the user instead of being downgraded silently`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("117.183.5.29:13481")))
        val probe = plaintextOnlyProbe()

        val result = EndpointResolver(dns, probe).resolve(txtSettings(allowHttpFallback = true), plaintextConsent = false)

        assertFalse(result.success)
        assertNull(result.endpoint)
        assertEquals(EndpointRejectionReason.PLAINTEXT_CONFIRMATION_REQUIRED, result.error?.reason)
        // The address that answered is carried out, so the UI can offer exactly it.
        assertEquals(plaintextHostUrl, result.pendingPlaintextEndpoint?.url)
        assertTrue(result.error?.message?.contains(plaintextHostUrl) == true, "the message must name the address")
        // Nothing was accepted, and the endpoint was probed over plaintext only to confirm it answers.
        assertTrue(probe.probed.contains(plaintextHostUrl))
        // An offer is not a rejection, so it never reaches lastProbeFailure. The trail has to carry
        // the HTTPS failure anyway, otherwise the most interesting case would read "NONE".
        val diagnostics = result.error?.diagnostics.orEmpty()
        assertTrue(diagnostics.contains("httpsProbeOfOfferedEndpoint=TLS_NOT_A_TLS_PEER"), diagnostics)
    }

    @Test
    fun `the same record connects over plaintext once the user accepts it`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("117.183.5.29:13481")))

        val result = EndpointResolver(dns, plaintextOnlyProbe())
            .resolve(txtSettings(allowHttpFallback = true), plaintextConsent = true)

        assertTrue(result.success)
        assertEquals(plaintextHostUrl, result.endpoint?.url)
        assertEquals(EndpointScheme.HTTP, result.endpoint?.scheme)
        assertNotNull(result.endpoint?.downgradeReason)
    }

    @Test
    fun `a tcp record is never turned into http without consent`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("tcp://117.183.5.29:13481")))

        val result = EndpointResolver(dns, plaintextOnlyProbe())
            .resolve(txtSettings(allowHttpFallback = true), plaintextConsent = false)

        assertFalse(result.success)
        assertNull(result.endpoint)
        assertEquals(EndpointRejectionReason.PLAINTEXT_CONFIRMATION_REQUIRED, result.error?.reason)
        assertEquals(plaintextHostUrl, result.pendingPlaintextEndpoint?.url)
    }

    @Test
    fun `a tcp record that does serve https is used as https`() {
        val dns = FakeDnsClient(mapOf(("ha.example.com" to DnsRecordType.TXT) to txt("tcp://117.183.5.29:13481")))

        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertTrue(result.success)
        assertEquals(httpsHostUrl, result.endpoint?.url)
    }

    // ---------------------------------------------------------------------------------------------
    // One reason per stage, so the UI never has to say "lookup failed" for four different problems.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every dns outcome maps to its own reason`() {
        val cases = mapOf(
            DnsQueryStatus.NXDOMAIN to EndpointRejectionReason.DNS_NAME_NOT_FOUND,
            DnsQueryStatus.EMPTY to EndpointRejectionReason.DNS_NO_RECORD_OF_TYPE,
            DnsQueryStatus.SERVER_FAILURE to EndpointRejectionReason.DNS_RESOLVER_ERROR,
            DnsQueryStatus.REFUSED to EndpointRejectionReason.DNS_RESOLVER_ERROR,
            DnsQueryStatus.TIMEOUT to EndpointRejectionReason.DNS_UNREACHABLE,
            DnsQueryStatus.NETWORK_ERROR to EndpointRejectionReason.DNS_UNREACHABLE,
            DnsQueryStatus.MALFORMED to EndpointRejectionReason.DNS_RESPONSE_MALFORMED,
        )

        val messages = mutableSetOf<String>()
        cases.forEach { (status, expected) ->
            val dns = FakeDnsClient(
                mapOf(("ha.example.com" to DnsRecordType.TXT) to DnsQueryResult(status = status)),
            )
            val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

            assertFalse(result.success, "$status must fail")
            assertEquals(expected, result.error?.reason, "$status")
            result.error?.message?.let { messages += it }
        }

        // Distinct wordings, not seven copies of the same sentence.
        assertEquals(cases.size, messages.size, "each status needs its own message")
    }

    @Test
    fun `a dns failure carries the resolver trail for debugging`() {
        val dns = FakeDnsClient(
            mapOf(
                ("ha.example.com" to DnsRecordType.TXT) to DnsQueryResult(
                    status = DnsQueryStatus.NETWORK_ERROR,
                    attempts = listOf(
                        DnsAttempt("system", DnsQueryStatus.TIMEOUT, 4_000),
                        DnsAttempt("doh:doh.pub", DnsQueryStatus.NETWORK_ERROR, 120, "SocketTimeoutException"),
                    ),
                ),
            ),
        )
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        val diagnostics = result.error?.diagnostics.orEmpty()
        assertTrue(diagnostics.contains("system"), diagnostics)
        assertTrue(diagnostics.contains("doh:doh.pub"), diagnostics)
        assertTrue(diagnostics.contains("attempts=["), diagnostics)
    }

    @Test
    fun `only txt answers are interpreted as records`() {
        // A TXT query whose answer section holds an A record: that value is not a TXT payload and
        // must never be turned into a host.
        val dns = FakeDnsClient(
            mapOf(
                ("ha.example.com" to DnsRecordType.TXT) to DnsQueryResult(
                    answers = listOf(DnsAnswer(DnsRecordType.A, "203.0.113.10")),
                    status = DnsQueryStatus.NOERROR,
                ),
            ),
        )
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertFalse(result.success)
        assertEquals(EndpointRejectionReason.DNS_NO_RECORD_OF_TYPE, result.error?.reason)
    }

    @Test
    fun `a cname alias is followed once`() {
        val dns = FakeDnsClient(
            mapOf(
                ("ha.example.com" to DnsRecordType.TXT) to DnsQueryResult(
                    status = DnsQueryStatus.EMPTY,
                    canonicalNames = listOf("home.example.com"),
                ),
                ("home.example.com" to DnsRecordType.TXT) to txt("203.0.113.10:8123"),
            ),
        )
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertTrue(result.success)
        assertEquals("https://203.0.113.10:8123", result.endpoint?.url)
        assertTrue(dns.queries.contains("home.example.com" to DnsRecordType.TXT))
    }

    @Test
    fun `an alias that resolves to nothing is reported as missing rather than retried forever`() {
        val dns = FakeDnsClient(
            mapOf(
                ("ha.example.com" to DnsRecordType.TXT) to DnsQueryResult(
                    status = DnsQueryStatus.EMPTY,
                    canonicalNames = listOf("home.example.com"),
                ),
            ),
        )
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertFalse(result.success)
        // The alias target is not in the zone at all, and that is what the user is told.
        assertEquals(EndpointRejectionReason.DNS_NAME_NOT_FOUND, result.error?.reason)
        assertEquals(2, dns.queries.size)
    }

    @Test
    fun `an alias target without the record type is reported as a missing record`() {
        val dns = FakeDnsClient(
            mapOf(
                ("ha.example.com" to DnsRecordType.TXT) to DnsQueryResult(
                    status = DnsQueryStatus.EMPTY,
                    canonicalNames = listOf("home.example.com"),
                ),
                ("home.example.com" to DnsRecordType.TXT) to DnsQueryResult(status = DnsQueryStatus.EMPTY),
            ),
        )
        val result = EndpointResolver(dns, FakeProbe()).resolve(txtSettings())

        assertFalse(result.success)
        assertEquals(EndpointRejectionReason.DNS_NO_RECORD_OF_TYPE, result.error?.reason)
        assertEquals(2, dns.queries.size)
    }
}
