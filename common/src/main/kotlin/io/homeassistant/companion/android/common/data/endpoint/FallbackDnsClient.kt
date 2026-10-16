package io.homeassistant.companion.android.common.data.endpoint

import kotlin.coroutines.cancellation.CancellationException

/**
 * Queries several resolvers in order and returns the first usable answer.
 *
 * ## Why a chain instead of one resolver
 *
 * There is no single DNS transport that works everywhere:
 *
 * 1. The DNS servers the **device is configured with** are the ones that already work for every
 *    other app, including on networks that block or hijack public resolvers. They are used first.
 * 2. Public **DNS over HTTPS** resolvers are needed when the local resolver cannot answer `TXT`
 *    or `SRV`, or when the network rewrites DNS answers. Which public resolver is reachable depends
 *    on the network: on a mainland-China connection the well known international ones are not
 *    reachable at all while domestic ones answer in milliseconds.
 *
 * A single hard-coded resolver therefore turns a working network into "TXT lookup failed". The
 * chain additionally preserves *which* step failed, because a name that does not exist and a
 * resolver that cannot be reached need completely different fixes.
 *
 * ## Termination rules
 *
 * * [DnsQueryStatus.NOERROR] stops the chain immediately.
 * * [DnsQueryStatus.EMPTY] and [DnsQueryStatus.NXDOMAIN] do **not** stop it: resolvers disagree
 *   while a record propagates, so a second opinion is worth the round trip.
 * * Everything else is a transport problem and simply moves on.
 * * [totalBudgetMs] caps the whole chain so a blocked resolver cannot stall the UI, and
 *   [clients] are never started once the budget is spent.
 *
 * The result always carries every attempt in [DnsQueryResult.attempts], which is what the UI and
 * the logs use to say what actually happened.
 */
class FallbackDnsClient(
    private val clients: List<Entry>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val totalBudgetMs: Long = DEFAULT_TOTAL_BUDGET_MS,
) : DnsClient {

    /** A resolver plus the name it is reported under. */
    data class Entry(val name: String, val client: DnsClient)

    override fun query(name: String, type: DnsRecordType): DnsQueryResult {
        val normalizedName = name.trim().trimEnd('.')
        if (normalizedName.isEmpty()) {
            return DnsQueryResult(
                status = DnsQueryStatus.MALFORMED,
                error = "Empty DNS record name",
            )
        }
        if (clients.isEmpty()) {
            return DnsQueryResult(
                status = DnsQueryStatus.NETWORK_ERROR,
                error = "No DNS resolver is configured",
            )
        }

        val attempts = mutableListOf<DnsAttempt>()
        var best: DnsQueryResult? = null
        var bestRank = Int.MIN_VALUE
        val deadline = clock() + totalBudgetMs

        clients.forEach { entry ->
            val started = clock()
            val remaining = deadline - started
            if (remaining <= 0) {
                // Never start a resolver that cannot finish inside the budget.
                attempts += DnsAttempt(entry.name, DnsQueryStatus.TIMEOUT, 0, "total lookup budget exhausted")
                return@forEach
            }

            val result = try {
                entry.client.query(normalizedName, type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR, provider = entry.name, error = e.describeForDiagnostics())
            }
            val duration = clock() - started

            if (result.attempts.isEmpty()) {
                attempts += DnsAttempt(entry.name, result.status, duration, result.error)
            } else {
                // The child already knows which of its own providers was tried. Keep that detail and
                // only add the child itself when it produced nothing at all.
                attempts += result.attempts
            }

            if (result.status == DnsQueryStatus.NOERROR) {
                return result.copy(
                    provider = result.provider ?: entry.name,
                    attempts = attempts.toList(),
                )
            }

            val rank = rankOf(result.status)
            if (rank > bestRank) {
                bestRank = rank
                best = result.copy(provider = result.provider ?: entry.name, attempts = attempts.toList())
            }
        }

        return (best ?: DnsQueryResult(
            status = DnsQueryStatus.NETWORK_ERROR,
            error = "No DNS resolver produced an answer",
        )).copy(attempts = attempts.toList())
    }

    /**
     * Higher wins. The order encodes how actionable an outcome is for the user: a name that exists
     * without the requested record is worth reporting over a resolver failure, which is in turn
     * worth reporting over "nothing answered".
     */
    private fun rankOf(status: DnsQueryStatus): Int = when (status) {
        DnsQueryStatus.NOERROR -> 100
        DnsQueryStatus.EMPTY -> 90
        DnsQueryStatus.NXDOMAIN -> 80
        DnsQueryStatus.REFUSED -> 60
        DnsQueryStatus.SERVER_FAILURE -> 50
        DnsQueryStatus.MALFORMED -> 40
        DnsQueryStatus.TIMEOUT -> 30
        DnsQueryStatus.UNSUPPORTED -> 20
        DnsQueryStatus.NETWORK_ERROR -> 10
    }

    companion object {
        /**
         * Overall budget for one record lookup. Long enough for a slow mobile network and two
         * resolvers, short enough that a blocked resolver does not look like a freeze.
         */
        const val DEFAULT_TOTAL_BUDGET_MS = 15_000L
    }
}

/**
 * Short, non-sensitive description of a failure.
 *
 * Only the exception type and its message are used. DNS query payloads never carry credentials, and
 * nothing here is ever logged together with an authentication token.
 */
internal fun Throwable.describeForDiagnostics(): String {
    val message = message?.takeIf { it.isNotBlank() }
    return if (message == null) this::class.java.simpleName else "${this::class.java.simpleName}: $message"
}
