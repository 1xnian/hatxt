package io.homeassistant.companion.android.common.data.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers every TXT shape that [TxtRecordInterpreter] claims to support, plus the rejection paths.
 * Section 4 and section 10 of the specification list these cases explicitly.
 */
class TxtRecordInterpreterTest {

    private val defaultPort = 8123

    private fun interpret(vararg records: String?, scheme: EndpointScheme = EndpointScheme.HTTPS) =
        TxtRecordInterpreter.interpret(records.toList(), defaultPort, scheme)

    private fun single(record: String, scheme: EndpointScheme = EndpointScheme.HTTPS): EndpointCandidate {
        val result = interpret(record, scheme = scheme)
        assertTrue(result.isUsable, "expected '$record' to be usable, rejected=${result.rejected}")
        assertEquals(1, result.candidates.size)
        return result.candidates.first()
    }

    @Test
    fun `full https URL keeps scheme port and path`() {
        val candidate = single("https://ha.example.com:8123/ha")
        assertEquals(EndpointScheme.HTTPS, candidate.scheme)
        assertEquals("ha.example.com", candidate.host)
        assertEquals(8123, candidate.port)
        assertEquals("ha", candidate.path)
    }

    @Test
    fun `full http URL is honoured as written`() {
        val candidate = single("http://203.0.113.10:8123")
        assertEquals(EndpointScheme.HTTP, candidate.scheme)
        assertEquals("203.0.113.10", candidate.host)
        assertEquals(8123, candidate.port)
    }

    @Test
    fun `URL without an explicit port uses the scheme default`() {
        val candidate = single("https://ha.example.com")
        assertEquals(443, candidate.port)
    }

    @Test
    fun `bare host and port pair prefers https`() {
        val candidate = single("203.0.113.10:8123")
        assertEquals(EndpointScheme.HTTPS, candidate.scheme)
        assertEquals("203.0.113.10", candidate.host)
        assertEquals(8123, candidate.port)
    }

    @Test
    fun `bare host and port pair follows the configured preference`() {
        val candidate = single("203.0.113.10:8123", scheme = EndpointScheme.HTTP)
        assertEquals(EndpointScheme.HTTP, candidate.scheme)
    }

    @Test
    fun `bare ip without a port falls back to the default port`() {
        val candidate = single("203.0.113.10")
        assertEquals(defaultPort, candidate.port)
        assertEquals("203.0.113.10", candidate.host)
    }

    @Test
    fun `bare hostname without a port falls back to the default port`() {
        val candidate = single("ha.example.com")
        assertEquals("ha.example.com", candidate.host)
        assertEquals(defaultPort, candidate.port)
    }

    @Test
    fun `hostname records keep the name so it can be resolved to A and AAAA later`() {
        val candidate = single("ha.example.com")
        assertFalse(EndpointUrlFactory.isIpLiteral(candidate.host))
        assertTrue(EndpointUrlFactory.isValidHostname(candidate.host))
    }

    @Test
    fun `key value record with ip and port`() {
        val candidate = single("ip=203.0.113.10;port=8123")
        assertEquals("203.0.113.10", candidate.host)
        assertEquals(8123, candidate.port)
    }

    @Test
    fun `key value record with host and port`() {
        val candidate = single("host=ha.example.com;port=8123")
        assertEquals("ha.example.com", candidate.host)
        assertEquals(8123, candidate.port)
    }

    @Test
    fun `key value record accepts comma and whitespace separators`() {
        val comma = single("ip=203.0.113.10,port=8123")
        assertEquals(8123, comma.port)

        val whitespace = single("ip=203.0.113.10 port=8123")
        assertEquals("203.0.113.10", whitespace.host)
        assertEquals(8123, whitespace.port)
    }

    @Test
    fun `url key is unwrapped and can be combined with an explicit port override`() {
        val candidate = single("url=https://ha.example.com:8123")
        assertEquals("ha.example.com", candidate.host)
        assertEquals(8123, candidate.port)

        val overridden = single("url=https://ha.example.com:9443;port=8123")
        assertEquals(8123, overridden.port)
    }

    @Test
    fun `scheme key overrides the preference`() {
        val candidate = single("host=ha.example.com;port=8123;scheme=https")
        assertEquals(EndpointScheme.HTTPS, candidate.scheme)
    }

    @Test
    fun `unknown keys are ignored so extra metadata does not break the record`() {
        val candidate = single("host=ha.example.com;port=8123;ttl=60;comment=hello")
        assertEquals("ha.example.com", candidate.host)
        assertEquals(8123, candidate.port)
    }

    @Test
    fun `ipv6 literal is accepted in brackets and bare`() {
        val bracketed = single("[2001:db8::1]:8123")
        assertEquals("2001:db8::1", bracketed.host)
        assertEquals(8123, bracketed.port)
        assertEquals("[2001:db8::1]:8123", EndpointUrlFactory.buildAuthority(bracketed.host, bracketed.port))

        val keyValue = single("ip=2001:db8::1;port=8123")
        assertEquals("2001:db8::1", keyValue.host)
    }

    @Test
    fun `empty records are reported and never become candidates`() {
        val result = TxtRecordInterpreter.interpret(listOf(null, "", "   "), defaultPort)
        assertFalse(result.isUsable)
        assertEquals(3, result.rejected.size)
        assertTrue(result.rejected.all { it.reason == EndpointRejectionReason.EMPTY_RECORD })
    }

    @Test
    fun `record without a host is rejected`() {
        val result = interpret("port=8123")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.MISSING_HOST, result.rejected.single().reason)
    }

    @Test
    fun `record with an out of range port is rejected`() {
        assertEquals(
            EndpointRejectionReason.INVALID_PORT,
            interpret("ip=203.0.113.10;port=0").rejected.single().reason,
        )
        assertEquals(
            EndpointRejectionReason.INVALID_PORT,
            interpret("ip=203.0.113.10;port=70000").rejected.single().reason,
        )
        assertEquals(
            EndpointRejectionReason.INVALID_PORT,
            interpret("203.0.113.10:0").rejected.single().reason,
        )
    }

    @Test
    fun `malformed ip address is rejected instead of being treated as a host name`() {
        val result = interpret("999.1.1.1:8123")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.INVALID_IP_ADDRESS, result.rejected.single().reason)

        val keyValue = interpret("ip=192.168.1.999;port=8123")
        assertEquals(EndpointRejectionReason.INVALID_IP_ADDRESS, keyValue.rejected.single().reason)
    }

    @Test
    fun `ip with leading zeros is rejected`() {
        val result = interpret("192.168.001.001:8123")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.INVALID_IP_ADDRESS, result.rejected.single().reason)
    }

    @Test
    fun `unsupported scheme is rejected with the scheme name`() {
        val result = interpret("ftp://ha.example.com:8123")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.UNSUPPORTED_SCHEME, result.rejected.single().reason)
        assertEquals("ftp", result.rejected.single().detail)
    }

    @Test
    fun `a field bag without a host key is rejected with a clear reason`() {
        // A typical SPF record is a field bag that carries no address at all.
        val result = interpret("v=spf1 -all")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.MISSING_HOST, result.rejected.single().reason)
    }

    @Test
    fun `a field bag with only empty values is reported as unparseable`() {
        val result = interpret("a=;b=")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.UNPARSEABLE_RECORD, result.rejected.single().reason)
    }

    @Test
    fun `a record that is neither a host nor a field bag is rejected as a host name`() {
        val result = interpret("@@@ ###")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.INVALID_HOSTNAME, result.rejected.single().reason)
    }

    @Test
    fun `multiple records produce multiple ordered candidates`() {
        val result = interpret(
            "203.0.113.10:8123",
            "https://ha.example.com:8443",
            "ip=203.0.113.20;port=8123",
        )
        assertEquals(3, result.candidates.size)
        assertEquals("203.0.113.10", result.candidates[0].host)
        assertEquals("ha.example.com", result.candidates[1].host)
        assertEquals("203.0.113.20", result.candidates[2].host)
    }

    @Test
    fun `duplicate records are collapsed and reported`() {
        val result = interpret("203.0.113.10:8123", "203.0.113.10:8123")
        assertEquals(1, result.candidates.size)
        assertEquals(EndpointRejectionReason.DUPLICATE_RECORD, result.rejected.single().reason)
    }

    @Test
    fun `duplicates are detected regardless of case of the host name`() {
        val result = interpret("HA.example.com:8123", "ha.example.com:8123")
        assertEquals(1, result.candidates.size)
    }

    @Test
    fun `valid and invalid records can be mixed`() {
        val result = interpret(
            "not a record",
            "203.0.113.10:8123",
            "999.1.1.1:8123",
            "",
        )
        assertEquals(1, result.candidates.size)
        assertEquals("203.0.113.10", result.candidates.single().host)
        assertEquals(3, result.rejected.size)
    }

    @Test
    fun `records containing credentials are rejected`() {
        val result = interpret("https://user:secret@ha.example.com:8123")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.INVALID_URL, result.rejected.single().reason)
    }

    @Test
    fun `multiple host values in one record produce one candidate each`() {
        val result = interpret("ip=203.0.113.10;ip=203.0.113.11;port=8123")
        assertEquals(2, result.candidates.size)
        assertEquals(8123, result.candidates[0].port)
        assertEquals(8123, result.candidates[1].port)
    }

    @Test
    fun `path from a key value record is normalised`() {
        val candidate = single("host=ha.example.com;port=8123;path=/ha/")
        assertEquals("ha", candidate.path)
        assertNotNull(EndpointUrlFactory.normalizePath(candidate.path))
    }

    // ---------------------------------------------------------------------------------------------
    // Every shape a real deployment is expected to publish, including the ones reported as failing
    // against the app. `117.183.5.29:13481` is the value that was actually found in DNS.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every documented shape produces exactly one usable candidate`() {
        val shapes = listOf(
            "http://117.183.5.29:13481",
            "https://117.183.5.29:13481",
            "tcp://117.183.5.29:13481",
            "117.183.5.29:13481",
            "ip=117.183.5.29;port=13481",
        )

        shapes.forEach { shape ->
            val result = interpret(shape)
            assertTrue(result.isUsable, "'$shape' must be usable, rejected=${result.rejected}")
            assertEquals(1, result.candidates.size, "'$shape' must yield exactly one candidate")
            val candidate = result.candidates.first()
            assertEquals("117.183.5.29", candidate.host, "'$shape'")
            assertEquals(13481, candidate.port, "'$shape'")
        }
    }

    @Test
    fun `an explicit http url is honoured as written`() {
        val candidate = single("http://117.183.5.29:13481")
        assertEquals(EndpointScheme.HTTP, candidate.scheme)
        assertEquals(EndpointTransport.HTTP, candidate.transport)
    }

    @Test
    fun `tcp declares a bare endpoint and never assumes a scheme`() {
        val candidate = single("tcp://117.183.5.29:13481")

        // No scheme: the transport has to be discovered by probing, it must not be guessed as http.
        assertNull(candidate.scheme)
        assertEquals(EndpointTransport.TCP, candidate.transport)
        assertTrue(candidate.isBareTcpEndpoint)
    }

    @Test
    fun `tcp without a port uses the default port`() {
        val candidate = single("tcp://117.183.5.29")
        assertEquals(defaultPort, candidate.port)
        assertNull(candidate.scheme)
    }

    @Test
    fun `tcp keeps a path and is case insensitive`() {
        val candidate = single("TCP://ha.example.com:8123/ha")
        assertEquals("ha", candidate.path)
        assertEquals(EndpointTransport.TCP, candidate.transport)
        assertNull(candidate.scheme)
    }

    @Test
    fun `tcp with an out of range port is reported as a port problem`() {
        val result = interpret("tcp://117.183.5.29:99999")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.INVALID_PORT, result.rejected.single().reason)
    }

    @Test
    fun `tcp without a host is rejected`() {
        val result = interpret("tcp:///ha")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.MISSING_HOST, result.rejected.single().reason)
    }

    @Test
    fun `a value the transport joined from several character strings is read as one value`() {
        // Both transports hand over the plain concatenation of the character-strings, so the
        // interpreter receives one finished string. `117.183.5.` + `29:13481` arrives as
        // `117.183.5.29:13481` and parses normally.
        val candidate = single("117.183.5.29:13481")
        assertEquals("117.183.5.29", candidate.host)
        assertEquals(13481, candidate.port)
    }

    @Test
    fun `a record with an embedded space is rejected instead of being rewritten`() {
        // A space is a real character of the value (RFC 1035 section 3.3.14). Deleting it would turn
        // this into 117.183.529:13481, an address nobody configured, so it must be rejected.
        val result = interpret("117.183.5 29:13481")
        assertFalse(result.isUsable)
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun `a field bag whose port has an embedded space is rejected`() {
        val result = interpret("ip=117.183.5.29;port=13 481")
        assertFalse(result.isUsable)
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun `separators inside a valid field bag are kept`() {
        // The space between the two pairs is part of the record and separates them; it is never
        // removed, so the bag is parsed as a bag rather than as one unparseable token.
        val candidate = single("url=http://203.0.113.10:8123 port=2026")
        assertEquals(2026, candidate.port)
        assertEquals(EndpointScheme.HTTP, candidate.scheme)
    }

    @Test
    fun `records without a transport report provenance as unspecified`() {
        assertEquals(EndpointTransport.UNSPECIFIED, single("117.183.5.29:13481").transport)
        assertEquals(EndpointTransport.UNSPECIFIED, single("ip=117.183.5.29;port=13481").transport)
    }
}
