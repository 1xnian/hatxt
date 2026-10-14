package io.homeassistant.companion.android.common.data.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Covers SRV parsing and record selection, section 5 and section 10 of the specification.
 */
class SrvRecordInterpreterTest {

    private fun srv(value: String) = DnsAnswer(DnsRecordType.SRV, value)

    private fun parse(vararg values: String) = SrvRecordInterpreter.parse(values.map(::srv))

    @Test
    fun `single record is parsed into priority weight port and target`() {
        val result = parse("10 60 8123 ha.example.com.")
        assertTrue(result.isUsable)
        val record = result.records.single()
        assertEquals(10, record.priority)
        assertEquals(60, record.weight)
        assertEquals(8123, record.port)
        assertEquals("ha.example.com", record.target)
        assertFalse(result.serviceUnavailable)
    }

    @Test
    fun `target is the host and never the query name`() {
        val result = parse("0 0 8123 node1.ha.example.com.")
        val candidate = SrvRecordInterpreter.toCandidate(result.records.single())
        assertEquals("node1.ha.example.com", candidate.host)
        assertFalse(candidate.host.startsWith("_"))
    }

    @Test
    fun `target of dot is treated as service unavailable and no candidate is built`() {
        val result = parse("0 0 0 .")
        assertTrue(result.serviceUnavailable)
        assertFalse(result.isUsable)
        assertTrue(result.records.isEmpty())
        assertEquals(
            EndpointRejectionReason.SRV_SERVICE_UNAVAILABLE,
            result.rejected.single().reason,
        )
    }

    @Test
    fun `service unavailable wins even when another record looks usable`() {
        val result = parse("10 0 8123 ha.example.com.", "0 0 0 .")
        assertTrue(result.serviceUnavailable)
        assertFalse(result.isUsable)
    }

    @Test
    fun `port zero is rejected`() {
        val result = parse("10 0 0 ha.example.com.")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.INVALID_PORT, result.rejected.single().reason)
    }

    @Test
    fun `port above the valid range is rejected`() {
        val result = parse("10 0 70000 ha.example.com.")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.INVALID_PORT, result.rejected.single().reason)
    }

    @Test
    fun `malformed record with too few fields is rejected`() {
        val result = parse("10 60 8123")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.UNPARSEABLE_RECORD, result.rejected.single().reason)
    }

    @Test
    fun `non numeric priority is rejected`() {
        val result = parse("high 60 8123 ha.example.com.")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.UNPARSEABLE_RECORD, result.rejected.single().reason)
    }

    @Test
    fun `invalid target host name is rejected`() {
        val result = parse("10 60 8123 999.1.1.1")
        assertFalse(result.isUsable)
        assertEquals(EndpointRejectionReason.INVALID_IP_ADDRESS, result.rejected.single().reason)
    }

    @Test
    fun `lower priority value wins`() {
        val result = parse(
            "20 0 8123 secondary.ha.example.com.",
            "10 0 8123 primary.ha.example.com.",
        )
        val selected = SrvRecordSelector.selectDeterministically(result.records)
        assertEquals("primary.ha.example.com", selected?.target)
        assertEquals(10, selected?.priority)
    }

    @Test
    fun `same priority with different weights picks the heaviest deterministically`() {
        val result = parse(
            "10 10 8123 small.ha.example.com.",
            "10 90 8123 large.ha.example.com.",
        )
        val selected = SrvRecordSelector.selectDeterministically(result.records)
        assertEquals("large.ha.example.com", selected?.target)
    }

    @Test
    fun `weighted selection distributes according to the weights`() {
        val records = listOf(
            SrvRecord(10, 10, 8123, "light.ha.example.com"),
            SrvRecord(10, 90, 8123, "heavy.ha.example.com"),
        )
        val random = Random(1234)
        val counts = mutableMapOf<String, Int>()
        repeat(20_000) {
            val picked = SrvRecordSelector.selectByWeight(records, random)
            counts[picked!!.target] = (counts[picked.target] ?: 0) + 1
        }
        val heavy = counts["heavy.ha.example.com"] ?: 0
        // Weight 90 of 100 should win roughly nine out of ten times.
        assertTrue(heavy in 17_000..19_000, "heavy was picked $heavy times out of 20000")
    }

    @Test
    fun `weighted selection only considers the lowest priority group`() {
        val records = listOf(
            SrvRecord(0, 0, 8123, "primary.ha.example.com"),
            SrvRecord(50, 100, 8123, "ignored.ha.example.com"),
        )
        repeat(200) {
            val picked = SrvRecordSelector.selectByWeight(records, Random(it))
            assertEquals("primary.ha.example.com", picked?.target)
        }
    }

    @Test
    fun `all weights zero falls back to a uniform choice`() {
        val records = listOf(
            SrvRecord(10, 0, 8123, "a.ha.example.com"),
            SrvRecord(10, 0, 8123, "b.ha.example.com"),
        )
        val picked = mutableSetOf<String>()
        repeat(200) { picked += SrvRecordSelector.selectByWeight(records, Random(it))!!.target }
        assertEquals(setOf("a.ha.example.com", "b.ha.example.com"), picked)
    }

    @Test
    fun `candidate ordering is stable for identical input`() {
        val records = listOf(
            SrvRecord(20, 0, 8123, "c.ha.example.com"),
            SrvRecord(10, 5, 8123, "b.ha.example.com"),
            SrvRecord(10, 50, 8123, "a.ha.example.com"),
        )
        val first = SrvRecordSelector.orderCandidates(records).map { it.target }
        val second = SrvRecordSelector.orderCandidates(records.shuffled(Random(7))).map { it.target }
        assertEquals(first, second)
        assertEquals(listOf("a.ha.example.com", "b.ha.example.com", "c.ha.example.com"), first)
    }

    @Test
    fun `selecting from an empty list returns null`() {
        assertNull(SrvRecordSelector.selectByWeight(emptyList()))
        assertNull(SrvRecordSelector.selectDeterministically(emptyList()))
    }

    @Test
    fun `non srv answers are ignored`() {
        val result = SrvRecordInterpreter.parse(
            listOf(
                DnsAnswer(DnsRecordType.TXT, "203.0.113.10:8123"),
                DnsAnswer(DnsRecordType.SRV, "10 0 8123 ha.example.com."),
            ),
        )
        assertEquals(1, result.records.size)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `multiple candidates can be ordered to allow a fallback`() {
        val result = parse(
            "10 0 8123 first.ha.example.com.",
            "20 0 8123 second.ha.example.com.",
            "30 0 8123 third.ha.example.com.",
        )
        val ordered = SrvRecordSelector.orderCandidates(result.records).map { it.target }
        assertEquals(
            listOf("first.ha.example.com", "second.ha.example.com", "third.ha.example.com"),
            ordered,
        )
    }
}
