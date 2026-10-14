package io.homeassistant.companion.android.common.data.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * State handling, stale result rejection and the "keep the last valid address" guarantee.
 * Sections 3, 8 and 9 of the specification.
 */
class EndpointStateMachineTest {

    private var now = 1_700_000_000_000L

    private fun machine() = EndpointStateMachine { now }

    private fun successEndpoint(url: String = "https://203.0.113.10:8123"): ResolvedEndpoint =
        EndpointUrlFactory.parseUrl(url)!!.toEndpoint()

    private fun settings(
        interval: Long = 300,
        periodic: Boolean = true,
    ) = EndpointSettings(
        mode = EndpointResolutionMode.TXT,
        txtRecordName = "ha.example.com",
        refreshIntervalSeconds = interval,
        periodicRefreshEnabled = periodic,
    )

    private fun failure(message: String = "lookup failed") = EndpointResolutionResult.failure(
        EndpointResolutionError(EndpointRejectionReason.DNS_LOOKUP_FAILED, message),
    )

    @Test
    fun `a first successful resolution is reported as resolved`() {
        val machine = machine()
        val token = machine.beginResolving()

        val update = machine.applyResult(
            token,
            EndpointResolutionResult.success(successEndpoint()),
            settings(),
        )

        assertTrue(update is EndpointUpdate.Resolved)
        assertEquals("https://203.0.113.10:8123", update.snapshot.current?.url)
        assertEquals(EndpointResolutionStatus.SUCCESS, update.snapshot.status)
        assertNull(update.snapshot.lastError)
    }

    @Test
    fun `an unchanged result is reported as unchanged`() {
        val machine = machine()
        machine.applyResult(machine.beginResolving(), EndpointResolutionResult.success(successEndpoint()), settings())

        val update = machine.applyResult(
            machine.beginResolving(),
            EndpointResolutionResult.success(successEndpoint()),
            settings(),
        )

        assertTrue(update is EndpointUpdate.Unchanged)
    }

    @Test
    fun `a changed result is reported with the previous url`() {
        val machine = machine()
        machine.applyResult(machine.beginResolving(), EndpointResolutionResult.success(successEndpoint()), settings())

        val update = machine.applyResult(
            machine.beginResolving(),
            EndpointResolutionResult.success(successEndpoint("https://203.0.113.11:8123")),
            settings(),
        )

        assertTrue(update is EndpointUpdate.Changed)
        update as EndpointUpdate.Changed
        assertEquals("https://203.0.113.10:8123", update.previousUrl)
        assertEquals("https://203.0.113.11:8123", update.endpoint.url)
    }

    @Test
    fun `a failure keeps the last valid address and marks it stale`() {
        val machine = machine()
        machine.applyResult(machine.beginResolving(), EndpointResolutionResult.success(successEndpoint()), settings())

        val update = machine.applyResult(machine.beginResolving(), failure(), settings())

        assertTrue(update is EndpointUpdate.Failed)
        // The address is not cleared, the client can keep connecting.
        assertEquals("https://203.0.113.10:8123", update.snapshot.current?.url)
        assertEquals("https://203.0.113.10:8123", update.snapshot.lastSuccessful?.url)
        assertEquals(EndpointResolutionStatus.FAILED, update.snapshot.status)
        assertEquals("lookup failed", update.snapshot.lastError)
        assertTrue(update.snapshot.isStale)
    }

    @Test
    fun `an unvalidated result can never replace the current endpoint`() {
        val machine = machine()
        machine.applyResult(machine.beginResolving(), EndpointResolutionResult.success(successEndpoint()), settings())

        // A failure carries no endpoint, so nothing can be overwritten.
        val result = EndpointResolutionResult(
            success = false,
            endpoint = null,
            error = EndpointResolutionError(EndpointRejectionReason.INVALID_URL, "bad record"),
        )
        val update = machine.applyResult(machine.beginResolving(), result, settings())

        assertTrue(update is EndpointUpdate.Failed)
        assertEquals("https://203.0.113.10:8123", update.snapshot.current?.url)
    }

    @Test
    fun `an outdated result never overwrites a newer one`() {
        val machine = machine()
        val firstToken = machine.beginResolving()
        val secondToken = machine.beginResolving()

        // The first attempt finishes late, after a newer one has started.
        val stale = machine.applyResult(
            firstToken,
            EndpointResolutionResult.success(successEndpoint("https://203.0.113.99:8123")),
            settings(),
        )
        assertTrue(stale is EndpointUpdate.IgnoredStale)

        val fresh = machine.applyResult(
            secondToken,
            EndpointResolutionResult.success(successEndpoint("https://203.0.113.11:8123")),
            settings(),
        )
        assertTrue(fresh is EndpointUpdate.Resolved)
        assertEquals("https://203.0.113.11:8123", fresh.snapshot.current?.url)
    }

    @Test
    fun `a stale failure cannot mark a freshly resolved endpoint as failed`() {
        val machine = machine()
        val oldToken = machine.beginResolving()
        val newToken = machine.beginResolving()

        machine.applyResult(newToken, EndpointResolutionResult.success(successEndpoint()), settings())
        val stale = machine.applyResult(oldToken, failure(), settings())

        assertTrue(stale is EndpointUpdate.IgnoredStale)
        assertEquals(EndpointResolutionStatus.SUCCESS, stale.snapshot.status)
        assertNull(stale.snapshot.lastError)
    }

    @Test
    fun `consecutive failures increase the backoff counter`() {
        val machine = machine()
        machine.applyResult(machine.beginResolving(), failure(), settings())
        val update = machine.applyResult(machine.beginResolving(), failure(), settings())

        assertEquals(2, update.snapshot.consecutiveFailures)
    }

    @Test
    fun `a success resets the failure counter and schedules the next refresh`() {
        val machine = machine()
        machine.applyResult(machine.beginResolving(), failure(), settings())
        machine.applyResult(machine.beginResolving(), failure(), settings())

        val update = machine.applyResult(
            machine.beginResolving(),
            EndpointResolutionResult.success(successEndpoint()),
            settings(interval = 300),
        )

        assertEquals(0, update.snapshot.consecutiveFailures)
        assertEquals(now + 300_000L, update.snapshot.nextRefreshAtMillis)
    }

    @Test
    fun `failures extend the next refresh using the backoff`() {
        val machine = machine()
        val update = machine.applyResult(machine.beginResolving(), failure(), settings(interval = 300))

        assertEquals(now + 600_000L, update.snapshot.nextRefreshAtMillis)
    }

    @Test
    fun `a fixed url mode never schedules a refresh`() {
        val machine = machine()
        val fixed = EndpointSettings(mode = EndpointResolutionMode.FIXED)

        val update = machine.applyResult(
            machine.beginResolving(),
            EndpointResolutionResult.success(successEndpoint()),
            fixed,
        )

        assertNull(update.snapshot.nextRefreshAtMillis)
    }

    @Test
    fun `disabled periodic refresh suppresses the schedule but keeps a validated endpoint`() {
        val machine = machine()
        val update = machine.applyResult(
            machine.beginResolving(),
            EndpointResolutionResult.success(successEndpoint()),
            settings(periodic = false),
        )

        assertNull(update.snapshot.nextRefreshAtMillis)
        assertEquals("https://203.0.113.10:8123", update.snapshot.current?.url)
    }

    @Test
    fun `configure adopts the stored url and the settings`() {
        val machine = machine()
        machine.configure(settings(), currentUrl = "https://203.0.113.10:8123")

        val snapshot = machine.snapshot()
        assertEquals(EndpointResolutionMode.TXT, snapshot.mode)
        assertEquals("ha.example.com", snapshot.recordName)
        assertEquals("https://203.0.113.10:8123", snapshot.current?.url)
        assertEquals(now + 300_000L, snapshot.nextRefreshAtMillis)
    }

    @Test
    fun `configure tolerates an unparsable stored url`() {
        val machine = machine()
        machine.configure(settings(), currentUrl = "this is not a url")

        assertNull(machine.snapshot().current)
    }

    @Test
    fun `a downgraded result is flagged in the snapshot`() {
        val machine = machine()
        val http = ResolvedEndpoint(
            EndpointScheme.HTTP,
            "203.0.113.10",
            8123,
            downgradeReason = "CONNECTION_REFUSED on HTTPS",
        )

        val update = machine.applyResult(machine.beginResolving(), EndpointResolutionResult.success(http), settings())

        assertTrue(update.snapshot.downgradedToHttp)
        assertEquals("http://203.0.113.10:8123", update.snapshot.current?.url)
    }

    @Test
    fun `reset clears everything`() {
        val machine = machine()
        machine.applyResult(machine.beginResolving(), EndpointResolutionResult.success(successEndpoint()), settings())

        machine.reset()

        val snapshot = machine.snapshot()
        assertNull(snapshot.current)
        assertNull(snapshot.lastSuccessful)
        assertEquals(EndpointResolutionStatus.IDLE, snapshot.status)
    }

    @Test
    fun `error can be acknowledged without touching the endpoint`() {
        val machine = machine()
        machine.applyResult(machine.beginResolving(), EndpointResolutionResult.success(successEndpoint()), settings())
        machine.applyResult(machine.beginResolving(), failure(), settings())

        machine.clearError()

        val snapshot = machine.snapshot()
        assertNull(snapshot.lastError)
        assertEquals("https://203.0.113.10:8123", snapshot.current?.url)
    }

    @Test
    fun `isCurrent follows the newest token`() {
        val machine = machine()
        val first = machine.beginResolving()
        assertTrue(machine.isCurrent(first))

        val second = machine.beginResolving()
        assertFalse(machine.isCurrent(first))
        assertTrue(machine.isCurrent(second))
    }
}
