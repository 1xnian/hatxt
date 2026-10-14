package io.homeassistant.companion.android.common.data.endpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Refresh timing and backoff, section 7 of the specification.
 */
class EndpointRefreshPolicyTest {

    @Test
    fun `the presets offered in the ui match the specification`() {
        assertEquals(
            listOf(30L, 60L, 300L, 600L, 1800L, 3600L),
            EndpointRefreshPolicy.PRESET_INTERVALS_SECONDS,
        )
    }

    @Test
    fun `very short intervals are clamped`() {
        assertEquals(EndpointRefreshPolicy.MIN_INTERVAL_SECONDS, EndpointRefreshPolicy.sanitizeInterval(1))
        assertEquals(EndpointRefreshPolicy.MIN_INTERVAL_SECONDS, EndpointRefreshPolicy.sanitizeInterval(0))
        assertEquals(EndpointRefreshPolicy.MIN_INTERVAL_SECONDS, EndpointRefreshPolicy.sanitizeInterval(-100))
        assertEquals(30L, EndpointRefreshPolicy.sanitizeInterval(30))
    }

    @Test
    fun `very long intervals are clamped to one day`() {
        assertEquals(
            EndpointRefreshPolicy.MAX_INTERVAL_SECONDS,
            EndpointRefreshPolicy.sanitizeInterval(7 * 24 * 3600L),
        )
    }

    @Test
    fun `a successful resolution uses the configured interval`() {
        assertEquals(300L, EndpointRefreshPolicy.effectiveDelaySeconds(300, 0))
        assertEquals(30L, EndpointRefreshPolicy.effectiveDelaySeconds(30, 0))
    }

    @Test
    fun `failures back off exponentially up to the cap`() {
        assertEquals(60L, EndpointRefreshPolicy.effectiveDelaySeconds(30, 1))
        assertEquals(120L, EndpointRefreshPolicy.effectiveDelaySeconds(30, 2))
        assertEquals(240L, EndpointRefreshPolicy.effectiveDelaySeconds(30, 3))
        // 30 * 2^6 = 1920 seconds, above the 30 minute cap.
        assertEquals(EndpointRefreshPolicy.MAX_BACKOFF_SECONDS, EndpointRefreshPolicy.effectiveDelaySeconds(30, 6))
        assertEquals(EndpointRefreshPolicy.MAX_BACKOFF_SECONDS, EndpointRefreshPolicy.effectiveDelaySeconds(30, 50))
    }

    @Test
    fun `backoff never goes below the configured interval`() {
        val base = 600L
        for (failures in 1..10) {
            assertTrue(
                EndpointRefreshPolicy.effectiveDelaySeconds(base, failures) >= base,
                "delay for $failures failures was below the configured interval",
            )
        }
        // One failure with a 10 minute interval doubles to 20 minutes.
        assertEquals(1200L, EndpointRefreshPolicy.effectiveDelaySeconds(base, 1))
    }

    @Test
    fun `next refresh time is derived from the effective delay`() {
        val now = 1_700_000_000_000L
        assertEquals(
            now + 300_000L,
            EndpointRefreshPolicy.nextRefreshAtMillis(now, 300, 0),
        )
        assertEquals(
            now + 600_000L,
            EndpointRefreshPolicy.nextRefreshAtMillis(now, 300, 1),
        )
    }

    @Test
    fun `due detection`() {
        assertFalse(EndpointRefreshPolicy.isDue(1000, null))
        assertFalse(EndpointRefreshPolicy.isDue(1000, 2000))
        assertTrue(EndpointRefreshPolicy.isDue(2000, 2000))
        assertTrue(EndpointRefreshPolicy.isDue(3000, 2000))
    }

    @Test
    fun `background interval respects the workmanager minimum`() {
        assertEquals(
            EndpointRefreshPolicy.MIN_BACKGROUND_INTERVAL_SECONDS,
            EndpointRefreshPolicy.backgroundIntervalSeconds(30),
        )
        assertEquals(
            EndpointRefreshPolicy.MIN_BACKGROUND_INTERVAL_SECONDS,
            EndpointRefreshPolicy.backgroundIntervalSeconds(300),
        )
        assertEquals(1800L, EndpointRefreshPolicy.backgroundIntervalSeconds(1800))
    }

    @Test
    fun `short intervals are flagged as limited in the background`() {
        assertTrue(EndpointRefreshPolicy.isBackgroundIntervalLimited(30))
        assertTrue(EndpointRefreshPolicy.isBackgroundIntervalLimited(300))
        assertFalse(EndpointRefreshPolicy.isBackgroundIntervalLimited(900))
        assertFalse(EndpointRefreshPolicy.isBackgroundIntervalLimited(1800))
    }
}
