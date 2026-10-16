package io.homeassistant.companion.android.common.data.endpoint

import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behaviour of the resolver chain.
 *
 * The chain is what makes a lookup survive a network that blocks one transport, and it is also what
 * keeps the *reason* of a failure visible. Both properties are asserted here with fake resolvers, so
 * the tests do not depend on any network.
 */
class FallbackDnsClientTest {

    private class FakeClient(
        private val result: DnsQueryResult = DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR),
        private val onQuery: ((String, DnsRecordType) -> DnsQueryResult)? = null,
        private val throwable: Exception? = null,
    ) : DnsClient {
        val calls = mutableListOf<Pair<String, DnsRecordType>>()

        override fun query(name: String, type: DnsRecordType): DnsQueryResult {
            calls += name to type
            throwable?.let { throw it }
            return onQuery?.invoke(name, type) ?: result
        }
    }

    private fun entry(name: String, client: DnsClient) = FallbackDnsClient.Entry(name, client)

    private fun txt(value: String) = DnsQueryResult(
        answers = listOf(DnsAnswer(DnsRecordType.TXT, value)),
        status = DnsQueryStatus.NOERROR,
    )

    @Test
    fun `the first resolver that answers wins`() {
        val first = FakeClient(result = DnsQueryResult(status = DnsQueryStatus.NXDOMAIN))
        val second = FakeClient(result = txt("203.0.113.10:8123"))
        val third = FakeClient(result = txt("203.0.113.11:8123"))

        val result = FallbackDnsClient(listOf(entry("first", first), entry("second", second), entry("third", third)))
            .query("ha.example.com", DnsRecordType.TXT)

        assertTrue(result.isSuccess)
        assertEquals("203.0.113.10:8123", result.answers.single().value)
        assertEquals("second", result.provider)
        assertTrue(third.calls.isEmpty(), "the chain must stop at the first usable answer")
    }

    @Test
    fun `an empty answer does not stop the chain`() {
        // Resolvers disagree while a record propagates, so a second opinion is worth the round trip.
        val empty = FakeClient(result = DnsQueryResult(status = DnsQueryStatus.EMPTY))
        val answering = FakeClient(result = txt("203.0.113.10:8123"))

        val result = FallbackDnsClient(listOf(entry("empty", empty), entry("answering", answering)))
            .query("ha.example.com", DnsRecordType.TXT)

        assertTrue(result.isSuccess)
        assertFalse(answering.calls.isEmpty())
    }

    @Test
    fun `an nxdomain does not stop the chain either`() {
        val nxdomain = FakeClient(result = DnsQueryResult(status = DnsQueryStatus.NXDOMAIN))
        val answering = FakeClient(result = txt("203.0.113.10:8123"))

        val result = FallbackDnsClient(listOf(entry("nxdomain", nxdomain), entry("answering", answering)))
            .query("ha.example.com", DnsRecordType.TXT)

        assertTrue(result.isSuccess)
        assertFalse(answering.calls.isEmpty())
    }

    @Test
    fun `every attempt is recorded in order`() {
        val result = FallbackDnsClient(
            listOf(
                entry("system", FakeClient(result = DnsQueryResult(status = DnsQueryStatus.TIMEOUT))),
                entry("udp", FakeClient(result = DnsQueryResult(status = DnsQueryStatus.EMPTY))),
                entry("doh:doh.pub", FakeClient(result = txt("203.0.113.10:8123"))),
            ),
        ).query("ha.example.com", DnsRecordType.TXT)

        assertEquals(listOf("system", "udp", "doh:doh.pub"), result.attempts.map { it.provider })
        assertTrue(result.attemptTrail.contains("system=TIMEOUT"), result.attemptTrail)
    }

    @Test
    fun `the most actionable failure is reported when nothing answered`() {
        val result = FallbackDnsClient(
            listOf(
                entry("system", FakeClient(result = DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR))),
                entry("udp", FakeClient(result = DnsQueryResult(status = DnsQueryStatus.EMPTY))),
                entry("doh", FakeClient(result = DnsQueryResult(status = DnsQueryStatus.TIMEOUT))),
            ),
        ).query("ha.example.com", DnsRecordType.TXT)

        // "the name exists but has no TXT record" is more useful than "nothing answered".
        assertEquals(DnsQueryStatus.EMPTY, result.status)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `a resolver that throws is reported instead of propagating`() {
        val result = FallbackDnsClient(
            listOf(entry("broken", FakeClient(throwable = IOException("connection reset")))),
        ).query("ha.example.com", DnsRecordType.TXT)

        assertEquals(DnsQueryStatus.NETWORK_ERROR, result.status)
        assertTrue(result.attemptTrail.contains("broken=NETWORK_ERROR"), result.attemptTrail)
        assertTrue(result.error?.contains("connection reset") == true, result.error)
    }

    @Test
    fun `a resolver is never started once the budget is spent`() {
        var now = 0L
        val slow = FakeClient(
            onQuery = { _, _ ->
                now += 500
                DnsQueryResult(status = DnsQueryStatus.TIMEOUT, error = "took too long")
            },
        )
        val never = FakeClient(result = txt("203.0.113.10:8123"))

        val result = FallbackDnsClient(
            clients = listOf(entry("slow", slow), entry("never", never)),
            clock = { now },
            totalBudgetMs = 100,
        ).query("ha.example.com", DnsRecordType.TXT)

        assertTrue(never.calls.isEmpty(), "a resolver that cannot finish inside the budget is skipped")
        assertEquals(DnsQueryStatus.TIMEOUT, result.status)
        val skipped = result.attempts.single { it.provider == "never" }
        assertEquals(DnsQueryStatus.TIMEOUT, skipped.status)
        assertNotNull(skipped.error)
    }

    @Test
    fun `an empty name is rejected before any resolver is asked`() {
        val client = FakeClient(result = txt("203.0.113.10:8123"))

        val result = FallbackDnsClient(listOf(entry("system", client))).query("   ", DnsRecordType.TXT)

        assertEquals(DnsQueryStatus.MALFORMED, result.status)
        assertNull(result.provider)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `a chain without resolvers reports a network error`() {
        val result = FallbackDnsClient(emptyList()).query("ha.example.com", DnsRecordType.TXT)

        assertEquals(DnsQueryStatus.NETWORK_ERROR, result.status)
        assertNotNull(result.error)
    }

    @Test
    fun `the name is normalised before the resolvers see it`() {
        val client = FakeClient(result = txt("203.0.113.10:8123"))

        FallbackDnsClient(listOf(entry("system", client))).query("  ha.example.com.  ", DnsRecordType.TXT)

        assertEquals(listOf("ha.example.com" to DnsRecordType.TXT), client.calls)
    }
}
