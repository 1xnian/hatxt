package io.homeassistant.companion.android.common.data.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests of the pre-connect gate.
 *
 * These cover section 9 of the specification: the login must be re-validated every time, a failed
 * lookup must never be passed off as a fresh address, and refusing the connection is the default
 * when a strict policy is selected.
 */
class EndpointConnectGateTest {

    private val validated = EndpointConnectGate.ValidatedAddress(
        url = "https://ha.example.com:8123",
        resolvedAtMillis = NOW - 10 * 60 * 1000,
    )

    @Test
    fun `a fresh address proceeds without any warning`() {
        val snapshot = EndpointSnapshot(
            current = endpoint(),
            lastResolvedAtMillis = NOW,
        )

        val result = EndpointConnectGate.proceed(snapshot, addressChanged = true)

        assertEquals(ConnectDecision.PROCEED_WITH_NEW_ADDRESS, result.decision)
        assertEquals("https://ha.example.com:8123", result.url)
        assertTrue(result.mayConnect)
        assertFalse(result.isStale)
        assertNull(result.message)
    }

    @Test
    fun `an unchanged address proceeds quietly`() {
        val result = EndpointConnectGate.proceed(
            EndpointSnapshot(current = endpoint(), lastResolvedAtMillis = NOW),
            addressChanged = false,
        )

        assertEquals(ConnectDecision.PROCEED, result.decision)
    }

    @Test
    fun `a downgraded address warns the user that the connection is not encrypted`() {
        val http = ResolvedEndpoint(
            scheme = EndpointScheme.HTTP,
            host = "ha.example.com",
            port = 8123,
            downgradeReason = "CONNECTION_REFUSED on the HTTPS port",
        )

        val result = EndpointConnectGate.proceed(
            EndpointSnapshot(current = http, lastResolvedAtMillis = NOW),
            addressChanged = true,
        )

        assertTrue(result.message!!.contains("not encrypted"))
    }

    @Test
    fun `a strict policy refuses to connect after a failed lookup`() {
        val result = EndpointConnectGate.failure(
            policy = EndpointStalePolicy.STRICT,
            reason = "The DNS lookup failed",
            lastValidated = validated,
            nowMillis = NOW,
        )

        assertEquals(ConnectDecision.BLOCKED, result.decision)
        assertFalse(result.mayConnect)
        assertNull(result.url, "a blocked login must not carry an address to connect to")
    }

    @Test
    fun `the default policy reuses the last validated address and marks it stale`() {
        val result = EndpointConnectGate.failure(
            policy = EndpointStalePolicy.USE_LAST_VALIDATED,
            reason = "The DNS lookup failed",
            lastValidated = validated,
            nowMillis = NOW,
        )

        assertEquals(ConnectDecision.PROCEED_WITH_STALE_ADDRESS, result.decision)
        assertTrue(result.mayConnect)
        assertTrue(result.isStale)
        assertEquals(validated.url, result.url)
        assertEquals(validated.resolvedAtMillis, result.resolvedAtMillis)
    }

    @Test
    fun `the stale message says how old the address is and never claims it is fresh`() {
        val result = EndpointConnectGate.failure(
            policy = EndpointStalePolicy.USE_LAST_VALIDATED,
            reason = "The DNS lookup failed",
            lastValidated = validated,
            nowMillis = NOW,
        )

        val message = result.message!!
        assertTrue(message.contains("10 minutes"), "actual message: $message")
        assertTrue(message.contains("may be out of date"), "actual message: $message")
        assertTrue(message.contains("last validated"), "actual message: $message")
    }

    @Test
    fun `without any validated address the login is refused even with the lenient policy`() {
        val result = EndpointConnectGate.failure(
            policy = EndpointStalePolicy.USE_LAST_VALIDATED,
            reason = "The DNS lookup failed",
            lastValidated = null,
            nowMillis = NOW,
        )

        val message = result.message.orEmpty()
        assertEquals(ConnectDecision.BLOCKED, result.decision)
        assertTrue(
            message.contains("no address has ever been validated"),
            "actual message: $message",
        )
    }

    @Test
    fun `an unknown validation time is reported as less than a minute old`() {
        val result = EndpointConnectGate.failure(
            policy = EndpointStalePolicy.USE_LAST_VALIDATED,
            reason = "The DNS lookup failed",
            lastValidated = EndpointConnectGate.ValidatedAddress("https://ha.example.com:8123", null),
            nowMillis = NOW,
        )

        assertTrue(result.message!!.contains("less than a minute"))
    }

    @Test
    fun `ages are described coarsely`() {
        assertEquals("less than a minute", EndpointConnectGate.describeAge(0))
        assertEquals("less than a minute", EndpointConnectGate.describeAge(59_000))
        assertEquals("1 minute", EndpointConnectGate.describeAge(60_000))
        assertEquals("5 minutes", EndpointConnectGate.describeAge(5 * 60_000L))
        assertEquals("1 hour", EndpointConnectGate.describeAge(60 * 60_000L))
        assertEquals("3 hours", EndpointConnectGate.describeAge(3 * 60 * 60_000L))
    }

    @Test
    fun `a clock that moved backwards does not produce a negative age`() {
        assertEquals("less than a minute", EndpointConnectGate.describeAge(-5_000))
    }

    private fun endpoint() = ResolvedEndpoint(
        scheme = EndpointScheme.HTTPS,
        host = "ha.example.com",
        port = 8123,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
