package io.homeassistant.companion.android.common.data.endpoint

/**
 * Timing rules for periodic refreshes and for failure backoff.
 *
 * The class is pure so the timing behaviour is unit testable without a running Android scheduler.
 *
 * Android limits how often a background task may run. `WorkManager` enforces a hard minimum period
 * of 15 minutes, so a 30 second interval can only be honoured while the app is in the foreground.
 * [backgroundIntervalSeconds] and [isBackgroundIntervalLimited] expose that difference instead of
 * pretending that a background task can run every 30 seconds.
 */
object EndpointRefreshPolicy {

    /** Refresh interval used when the user did not choose one. */
    const val DEFAULT_INTERVAL_SECONDS = 5L * 60L

    /** Intervals offered in the settings UI, in seconds. */
    val PRESET_INTERVALS_SECONDS: List<Long> = listOf(30L, 60L, 5L * 60L, 10L * 60L, 30L * 60L, 60L * 60L)

    /**
     * Hard floor for any configured interval. Anything shorter is clamped so the app cannot be
     * configured into hammering a DNS server.
     */
    const val MIN_INTERVAL_SECONDS = 15L

    /** Upper bound for a configured interval: one day. */
    const val MAX_INTERVAL_SECONDS = 24L * 60L * 60L

    /** `WorkManager` refuses periodic work below this interval. */
    const val MIN_BACKGROUND_INTERVAL_SECONDS = 15L * 60L

    /** Ceiling for the exponential failure backoff. */
    const val MAX_BACKOFF_SECONDS = 30L * 60L

    /**
     * How long a successful resolution is considered fresh enough for a new login.
     *
     * Without this, an app that opens several sessions in a row would fire one DNS lookup per
     * session. With it, two logins within a few seconds share one lookup while a login that happens
     * later always gets a real, current answer. It is deliberately short: the pre-connect check is a
     * correctness requirement, not an optimisation.
     */
    const val PRE_CONNECT_FRESHNESS_SECONDS = 15L

    private const val MAX_BACKOFF_SHIFT = 6

    /** Clamps an interval into the allowed range. */
    fun sanitizeInterval(seconds: Long): Long =
        seconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)

    /**
     * Delay before the next attempt.
     *
     * On success the configured interval is used. After failures the delay doubles per consecutive
     * failure, capped at [MAX_BACKOFF_SECONDS] and never below the configured interval.
     */
    fun effectiveDelaySeconds(baseIntervalSeconds: Long, consecutiveFailures: Int): Long {
        val base = sanitizeInterval(baseIntervalSeconds)
        if (consecutiveFailures <= 0) return base
        val factor = 1L shl consecutiveFailures.coerceAtMost(MAX_BACKOFF_SHIFT)
        return minOf(base * factor, MAX_BACKOFF_SECONDS).coerceAtLeast(base)
    }

    /** Absolute timestamp of the next planned refresh. */
    fun nextRefreshAtMillis(
        nowMillis: Long,
        baseIntervalSeconds: Long,
        consecutiveFailures: Int,
    ): Long = nowMillis + effectiveDelaySeconds(baseIntervalSeconds, consecutiveFailures) * 1000L

    /** Interval that a background `WorkManager` task may actually use. */
    fun backgroundIntervalSeconds(requestedSeconds: Long): Long =
        maxOf(sanitizeInterval(requestedSeconds), MIN_BACKGROUND_INTERVAL_SECONDS)

    /**
     * The same interval expressed in whole minutes, which is the unit `PeriodicWorkRequestBuilder`
     * expects. Never below 15, because the system rejects anything shorter.
     */
    fun backgroundIntervalMinutes(requestedSeconds: Long): Long =
        backgroundIntervalSeconds(requestedSeconds) / 60L

    /**
     * True when the requested interval is shorter than what a background task can honour, i.e. the
     * user has to be told that background refreshes are slower than configured.
     */
    fun isBackgroundIntervalLimited(requestedSeconds: Long): Boolean =
        sanitizeInterval(requestedSeconds) < MIN_BACKGROUND_INTERVAL_SECONDS

    /** True when a scheduled refresh is due. */
    fun isDue(nowMillis: Long, nextRefreshAtMillis: Long?): Boolean {
        val due = nextRefreshAtMillis ?: return false
        return nowMillis >= due
    }
}
