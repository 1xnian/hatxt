package io.homeassistant.companion.android.common.data.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [TxtRecordInterpreter.extractPorts], the path used when the host is fixed by the settings
 * and the record only supplies the port.
 *
 * The shapes under test are the ones a STUN/relay rule actually publishes: such a rule reports the
 * address it punched through, so `ip:port` (and `scheme://ip:port`) are the common cases, while a
 * bare port and the explicit `port=` / `port:` forms cover the hand-written setups.
 */
class TxtPortExtractionTest {

    private fun extract(vararg records: String?) = TxtRecordInterpreter.extractPorts(records.toList())

    private fun single(record: String): Int {
        val result = extract(record)
        assertTrue(result.isUsable, "expected '$record' to yield a port, rejected=${result.rejected}")
        assertEquals(1, result.ports.size, "expected exactly one port for '$record'")
        return result.ports.first()
    }

    @Test
    fun `bare port is read as the port`() {
        assertEquals(12345, single("12345"))
    }

    @Test
    fun `port key with equals sign`() {
        assertEquals(12345, single("port=12345"))
    }

    @Test
    fun `port key with colon`() {
        assertEquals(12345, single("port:12345"))
    }

    @Test
    fun `port key with spacing around the separator`() {
        assertEquals(12345, single("port = 12345"))
    }

    @Test
    fun `bag that carries an ip and a port uses the port`() {
        // This is the shape a STUN rule produces: the IP is useless with a fixed host, the port is
        // the part that is reachable through the fixed name.
        assertEquals(12345, single("ip=1.2.3.4;port=12345"))
    }

    @Test
    fun `ip and port pair keeps the port and drops the address`() {
        assertEquals(12345, single("1.2.3.4:12345"))
    }

    @Test
    fun `bracketed ipv6 with a port`() {
        assertEquals(12345, single("[2001:db8::1]:12345"))
    }

    @Test
    fun `https url with an explicit port`() {
        assertEquals(12345, single("https://1.2.3.4:12345"))
    }

    @Test
    fun `https url without a port falls back to the scheme default`() {
        assertEquals(443, single("https://1.2.3.4"))
    }

    @Test
    fun `tcp url is read for its port instead of being rejected`() {
        // `tcp://` is not an HTTP scheme, so parseUrl refuses it; the port is still the port.
        assertEquals(12345, single("tcp://1.2.3.4:12345"))
    }

    @Test
    fun `a record without a port is rejected instead of defaulting`() {
        // In fixed-host mode the port *is* the dynamic part, so substituting 8123 would connect
        // somewhere the user never published.
        val result = extract("1.2.3.4")
        assertFalse(result.isUsable)
        assertTrue(result.ports.isEmpty())
        assertEquals(EndpointRejectionReason.MISSING_PORT, result.rejected.single().reason)
    }

    @Test
    fun `a bare host name carries no port`() {
        val result = extract("ha.example.com")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.MISSING_PORT, result.rejected.single().reason)
    }

    @Test
    fun `a port outside the valid range is rejected`() {
        val result = extract("99999")
        assertFalse(result.isUsable)
    }

    @Test
    fun `blank records are rejected as empty`() {
        val result = extract("", "   ", "12345")
        assertEquals(listOf(12345), result.ports)
        assertEquals(2, result.rejected.size)
        assertTrue(result.rejected.all { it.reason == EndpointRejectionReason.EMPTY_RECORD })
    }

    @Test
    fun `duplicate ports collapse into one`() {
        val result = extract("12345", "port=12345", "1.2.3.4:12345")
        assertEquals(listOf(12345), result.ports)
    }

    @Test
    fun `several distinct ports keep their order`() {
        val result = extract("12345", "23456", "1.2.3.4:34567")
        assertEquals(listOf(12345, 23456, 34567), result.ports)
    }

    @Test
    fun `a usable record among broken ones still yields its port`() {
        val result = extract("ha.example.com", "12345", "no-port-here")
        assertEquals(listOf(12345), result.ports)
        assertEquals(2, result.rejected.size)
    }
}
