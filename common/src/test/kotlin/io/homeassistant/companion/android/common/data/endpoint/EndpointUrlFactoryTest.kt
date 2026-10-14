package io.homeassistant.companion.android.common.data.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * URL construction and validation rules, section 5 of the specification.
 */
class EndpointUrlFactoryTest {

    @Test
    fun `builds an https url with a non default port`() {
        val endpoint = ResolvedEndpoint(EndpointScheme.HTTPS, "203.0.113.10", 8123)
        assertEquals("https://203.0.113.10:8123", endpoint.url)
    }

    @Test
    fun `builds an http url with a non default port`() {
        val endpoint = ResolvedEndpoint(EndpointScheme.HTTP, "203.0.113.10", 8123)
        assertEquals("http://203.0.113.10:8123", endpoint.url)
    }

    @Test
    fun `omits the port when it equals the scheme default`() {
        assertEquals(
            "https://ha.example.com",
            ResolvedEndpoint(EndpointScheme.HTTPS, "ha.example.com", 443).url,
        )
        assertEquals(
            "http://ha.example.com",
            ResolvedEndpoint(EndpointScheme.HTTP, "ha.example.com", 80).url,
        )
    }

    @Test
    fun `never duplicates the port or the scheme`() {
        val endpoint = ResolvedEndpoint(EndpointScheme.HTTPS, "ha.example.com", 8123)
        val url = endpoint.url
        assertEquals(1, Regex("://").findAll(url).count())
        assertEquals(1, Regex(":8123").findAll(url).count())
        assertFalse(url.endsWith(":"))
    }

    @Test
    fun `ipv6 literals are bracketed exactly once`() {
        val endpoint = ResolvedEndpoint(EndpointScheme.HTTPS, "2001:db8::1", 8123)
        assertEquals("https://[2001:db8::1]:8123", endpoint.url)
    }

    @Test
    fun `ipv6 literal that already carries brackets is normalised`() {
        val endpoint = ResolvedEndpoint(EndpointScheme.HTTPS, "[2001:db8::1]", 8123)
        assertEquals("https://[2001:db8::1]:8123", endpoint.url)
    }

    @Test
    fun `path is normalised and never produces duplicated slashes`() {
        val endpoint = ResolvedEndpoint(EndpointScheme.HTTPS, "ha.example.com", 8123, path = "/ha/")
        assertEquals("https://ha.example.com:8123/ha", endpoint.url)

        val emptyPath = endpoint.copy(path = "/")
        assertEquals("https://ha.example.com:8123", emptyPath.url)
    }

    @Test
    fun `validate rejects an empty host`() {
        val endpoint = ResolvedEndpoint(EndpointScheme.HTTPS, "", 8123)
        assertEquals(EndpointRejectionReason.MISSING_HOST, EndpointUrlFactory.validate(endpoint))
    }

    @Test
    fun `validate rejects an out of range port`() {
        assertEquals(
            EndpointRejectionReason.INVALID_PORT,
            EndpointUrlFactory.validate(ResolvedEndpoint(EndpointScheme.HTTPS, "ha.example.com", 0)),
        )
        assertEquals(
            EndpointRejectionReason.INVALID_PORT,
            EndpointUrlFactory.validate(ResolvedEndpoint(EndpointScheme.HTTPS, "ha.example.com", 65536)),
        )
    }

    @Test
    fun `validate rejects a malformed host`() {
        assertEquals(
            EndpointRejectionReason.INVALID_IP_ADDRESS,
            EndpointUrlFactory.validate(ResolvedEndpoint(EndpointScheme.HTTPS, "999.1.1.1", 8123)),
        )
        assertEquals(
            EndpointRejectionReason.INVALID_HOSTNAME,
            EndpointUrlFactory.validate(ResolvedEndpoint(EndpointScheme.HTTPS, "bad host", 8123)),
        )
    }

    @Test
    fun `validate accepts host names and literals`() {
        assertNull(EndpointUrlFactory.validate(ResolvedEndpoint(EndpointScheme.HTTPS, "ha.example.com", 8123)))
        assertNull(EndpointUrlFactory.validate(ResolvedEndpoint(EndpointScheme.HTTPS, "203.0.113.10", 8123)))
        assertNull(EndpointUrlFactory.validate(ResolvedEndpoint(EndpointScheme.HTTPS, "2001:db8::1", 8123)))
        assertNull(EndpointUrlFactory.validate(ResolvedEndpoint(EndpointScheme.HTTPS, "nas", 8123)))
    }

    @Test
    fun `port parsing rejects zero and values above the range`() {
        assertEquals(8123, EndpointUrlFactory.parsePortOrNull("8123"))
        assertNull(EndpointUrlFactory.parsePortOrNull("0"))
        assertNull(EndpointUrlFactory.parsePortOrNull("65536"))
        assertNull(EndpointUrlFactory.parsePortOrNull("abc"))
        assertNull(EndpointUrlFactory.parsePortOrNull(""))
    }

    @Test
    fun `parseUrl round trips a simple url`() {
        val parsed = EndpointUrlFactory.parseUrl("https://ha.example.com:8123")
        assertEquals(EndpointScheme.HTTPS, parsed?.scheme)
        assertEquals("ha.example.com", parsed?.host)
        assertEquals(8123, parsed?.port)
        assertNull(parsed?.path)
    }

    @Test
    fun `parseUrl extracts the path and ignores the query`() {
        val parsed = EndpointUrlFactory.parseUrl("https://ha.example.com:8123/ha?x=1")
        assertEquals("ha", parsed?.path)
    }

    @Test
    fun `parseUrl applies the scheme default port`() {
        assertEquals(443, EndpointUrlFactory.parseUrl("https://ha.example.com")?.port)
        assertEquals(80, EndpointUrlFactory.parseUrl("http://ha.example.com")?.port)
    }

    @Test
    fun `parseUrl handles a bracketed ipv6 host`() {
        val parsed = EndpointUrlFactory.parseUrl("https://[2001:db8::1]:8123")
        assertEquals("2001:db8::1", parsed?.host)
        assertEquals(8123, parsed?.port)
    }

    @Test
    fun `parseUrl rejects an unbracketed ipv6 host`() {
        assertNull(EndpointUrlFactory.parseUrl("https://2001:db8::1:8123"))
    }

    @Test
    fun `parseUrl rejects unsupported schemes and embedded credentials`() {
        assertNull(EndpointUrlFactory.parseUrl("ftp://ha.example.com:8123"))
        assertNull(EndpointUrlFactory.parseUrl("ws://ha.example.com:8123"))
        assertNull(EndpointUrlFactory.parseUrl("https://user:pass@ha.example.com:8123"))
        assertNull(EndpointUrlFactory.parseUrl("https://"))
    }

    @Test
    fun `ipv4 validation is strict about the range and leading zeros`() {
        assertTrue(EndpointUrlFactory.isValidIpv4Literal("0.0.0.0"))
        assertTrue(EndpointUrlFactory.isValidIpv4Literal("255.255.255.255"))
        assertFalse(EndpointUrlFactory.isValidIpv4Literal("256.0.0.1"))
        assertFalse(EndpointUrlFactory.isValidIpv4Literal("192.168.1"))
        assertFalse(EndpointUrlFactory.isValidIpv4Literal("192.168.1.1.1"))
        assertFalse(EndpointUrlFactory.isValidIpv4Literal("010.0.0.1"))
        assertFalse(EndpointUrlFactory.isValidIpv4Literal("a.b.c.d"))
    }

    @Test
    fun `ipv6 validation accepts compression and rejects bad input`() {
        assertTrue(EndpointUrlFactory.isValidIpv6Literal("::1"))
        assertTrue(EndpointUrlFactory.isValidIpv6Literal("::"))
        assertTrue(EndpointUrlFactory.isValidIpv6Literal("2001:db8::1"))
        assertTrue(EndpointUrlFactory.isValidIpv6Literal("fe80::1"))
        assertTrue(EndpointUrlFactory.isValidIpv6Literal("2001:0db8:0000:0000:0000:0000:0000:0001"))
        assertTrue(EndpointUrlFactory.isValidIpv6Literal("::ffff:192.0.2.1"))

        assertFalse(EndpointUrlFactory.isValidIpv6Literal("2001:db8::1::2"))
        assertFalse(EndpointUrlFactory.isValidIpv6Literal("2001:db8:1:2:3:4:5:6:7"))
        assertFalse(EndpointUrlFactory.isValidIpv6Literal("fe80::1%eth0"))
        assertFalse(EndpointUrlFactory.isValidIpv6Literal("12345::1"))
        assertFalse(EndpointUrlFactory.isValidIpv6Literal("2001:db8::g1"))
        assertFalse(EndpointUrlFactory.isValidIpv6Literal("1.2.3.4"))
    }

    @Test
    fun `hostname validation follows rfc 1123`() {
        assertTrue(EndpointUrlFactory.isValidHostname("ha.example.com"))
        assertTrue(EndpointUrlFactory.isValidHostname("ha.example.com."))
        assertTrue(EndpointUrlFactory.isValidHostname("nas"))
        assertTrue(EndpointUrlFactory.isValidHostname("my-ha"))
        assertFalse(EndpointUrlFactory.isValidHostname("-ha.example.com"))
        assertFalse(EndpointUrlFactory.isValidHostname("ha..example.com"))
        assertFalse(EndpointUrlFactory.isValidHostname(""))
    }

    @Test
    fun `host port parsing supports bracketed ipv6`() {
        assertEquals("ha.example.com" to 8123, EndpointUrlFactory.parseHostPort("ha.example.com:8123"))
        assertEquals("2001:db8::1" to 8123, EndpointUrlFactory.parseHostPort("[2001:db8::1]:8123"))
        assertNull(EndpointUrlFactory.parseHostPort("ha.example.com"))
        assertNull(EndpointUrlFactory.parseHostPort("ha.example.com:0"))
    }

    @Test
    fun `srv query name requires the underscore labels of rfc 2782`() {
        assertTrue(EndpointUrlFactory.isValidSrvQueryName("_8123._tcp.ha.example.com"))
        assertTrue(EndpointUrlFactory.isValidSrvQueryName("_home-assistant._tcp.ha.example.com"))
        assertTrue(EndpointUrlFactory.isValidSrvQueryName("_8123._tcp.ha.example.com."))

        // A plain host name is the most common mistake and must be reported, not resolved.
        assertFalse(EndpointUrlFactory.isValidSrvQueryName("ha.example.com"))
        assertFalse(EndpointUrlFactory.isValidSrvQueryName("_8123.ha.example.com"))
        assertFalse(EndpointUrlFactory.isValidSrvQueryName("8123.tcp.ha.example.com"))
        assertFalse(EndpointUrlFactory.isValidSrvQueryName("_._tcp.ha.example.com"))
        assertFalse(EndpointUrlFactory.isValidSrvQueryName(""))
    }
}
