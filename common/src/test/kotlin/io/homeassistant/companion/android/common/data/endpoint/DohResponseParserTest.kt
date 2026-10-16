package io.homeassistant.companion.android.common.data.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parsing of the DNS over HTTPS JSON payload.
 *
 * TXT payloads are the tricky part: the value arrives as a quoted string whose content is itself
 * quoted and escaped, and one record may be split into several character-strings.
 */
class DohResponseParserTest {

    @Test
    fun `parses a simple txt answer`() {
        val body = """
            {
              "Status": 0,
              "Answer": [
                { "name": "ha.example.com", "type": 16, "TTL": 300, "data": "\"203.0.113.10:8123\"" }
              ]
            }
        """.trimIndent()

        val result = DohResponseParser.parse(body, "test")

        assertTrue(result.isSuccess)
        assertEquals("203.0.113.10:8123", result.answers.single().value)
        assertEquals(DnsRecordType.TXT, result.answers.single().type)
        assertEquals(300L, result.answers.single().ttlSeconds)
    }

    @Test
    fun `parses a txt record split into several character strings`() {
        val body = """
            { "Status": 0, "Answer": [ { "type": 16, "data": "\"host=ha.example.com\" \" port=8123\"" } ] }
        """.trimIndent()

        val value = DohResponseParser.parse(body).answers.single().value

        // The space in the JSON presentation form separates the two character-strings and is not
        // part of the value, so the pieces are concatenated. The one space that remains is the one
        // that is literally inside the second character-string.
        assertEquals("host=ha.example.com port=8123", value)
        // The joined value must still be usable by the TXT interpreter.
        val interpreted = TxtRecordInterpreter.interpret(listOf(value), 8123)
        assertEquals("ha.example.com", interpreted.candidates.single().host)
        assertEquals(8123, interpreted.candidates.single().port)
    }

    @Test
    fun `the json path joins a value split inside a host name the way the wire format does`() {
        // A record split inside the address must survive the JSON presentation form intact.
        // DnsMessageCodecTest proves the same input in wire form decodes to 117.183.5.29:13481, so
        // the two transports agree and a lookup cannot depend on which one answered it.
        assertEquals("117.183.5.29:13481", DohResponseParser.normalizeTxtValue("\"117.183.5.\" \"29:13481\""))
        assertEquals("117.183.5.29:13481", DohResponseParser.normalizeTxtValue("\"117\" \".183.5\" \".29:13481\""))
    }

    @Test
    fun `unescapes quotes and backslashes`() {
        assertEquals("a\"b", DohResponseParser.normalizeTxtValue("\"a\\\"b\""))
        assertEquals("a\\b", DohResponseParser.normalizeTxtValue("\"a\\\\b\""))
        assertEquals("plain", DohResponseParser.normalizeTxtValue("\"plain\""))
        assertEquals("plain", DohResponseParser.normalizeTxtValue("plain"))
    }

    @Test
    fun `parses an srv answer`() {
        val body = """
            { "Status": 0, "Answer": [ { "type": 33, "data": "10 60 8123 ha.example.com." } ] }
        """.trimIndent()

        val result = DohResponseParser.parse(body)

        assertTrue(result.isSuccess)
        assertEquals(DnsRecordType.SRV, result.answers.single().type)
        assertEquals("10 60 8123 ha.example.com.", result.answers.single().value)
    }

    @Test
    fun `parses a and aaaa answers`() {
        val body = """
            {
              "Status": 0,
              "Answer": [
                { "type": 1, "data": "203.0.113.10" },
                { "type": 28, "data": "2001:db8::1" }
              ]
            }
        """.trimIndent()

        val result = DohResponseParser.parse(body)

        assertEquals(2, result.answers.size)
        assertEquals(DnsRecordType.A, result.answers[0].type)
        assertEquals(DnsRecordType.AAAA, result.answers[1].type)
    }

    @Test
    fun `an empty answer section is reported as empty`() {
        val result = DohResponseParser.parse("""{ "Status": 0 }""")

        assertEquals(DnsQueryStatus.EMPTY, result.status)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `nxdomain is reported`() {
        val result = DohResponseParser.parse("""{ "Status": 3 }""")
        assertEquals(DnsQueryStatus.NXDOMAIN, result.status)
    }

    @Test
    fun `server failure is reported`() {
        val result = DohResponseParser.parse("""{ "Status": 2 }""")
        assertEquals(DnsQueryStatus.SERVER_FAILURE, result.status)
    }

    @Test
    fun `malformed json is reported instead of throwing`() {
        val result = DohResponseParser.parse("this is not json")

        assertEquals(DnsQueryStatus.MALFORMED, result.status)
        assertNotNull(result.error)
    }

    @Test
    fun `answers of unrelated types are ignored`() {
        val body = """
            { "Status": 0, "Answer": [ { "type": 5, "data": "alias.example.com." } ] }
        """.trimIndent()

        assertEquals(DnsQueryStatus.EMPTY, DohResponseParser.parse(body).status)
    }

    @Test
    fun `unknown fields do not break parsing`() {
        val body = """
            {
              "Status": 0,
              "TC": false,
              "RD": true,
              "RA": true,
              "AD": false,
              "CD": false,
              "Question": [ { "name": "ha.example.com", "type": 16 } ],
              "Answer": [ { "name": "ha.example.com", "type": 16, "TTL": 60, "data": "\"203.0.113.10\"" } ],
              "Comment": "extra"
            }
        """.trimIndent()

        val result = DohResponseParser.parse(body)

        assertTrue(result.isSuccess)
        assertEquals("203.0.113.10", result.answers.single().value)
    }

    @Test
    fun `an empty record name is rejected by the client before a request is made`() {
        val result = DohDnsClient(endpoints = emptyList()).query("   ", DnsRecordType.TXT)

        assertEquals(DnsQueryStatus.MALFORMED, result.status)
        assertNull(result.provider)
    }

    @Test
    fun `a client without providers reports a network error instead of crashing`() {
        val result = DohDnsClient(endpoints = emptyList()).query("ha.example.com", DnsRecordType.TXT)

        assertEquals(DnsQueryStatus.NETWORK_ERROR, result.status)
        assertNotNull(result.error)
    }
}
