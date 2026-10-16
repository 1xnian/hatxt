package io.homeassistant.companion.android.common.data.endpoint

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [DnsClient] backed by the platform resolver, i.e. by the DNS servers **this device is actually
 * configured with**.
 *
 * ## Why this exists
 *
 * Public DNS over HTTPS resolvers are not reachable on every network. Where they are blocked, a
 * DoH-only client reports "TXT lookup failed" even though the name resolves perfectly well for
 * every other app on the same phone. Asking the platform resolver first removes that whole class of
 * failure, and it also honours per-network DNS, VPN DNS and split-horizon setups.
 *
 * ## Why `rawQuery` with a hand-built packet
 *
 * `DnsResolver` only exposes `TYPE_A`, `TYPE_AAAA` and `TYPE_HTTPS` constants, so its type-tagged
 * overload cannot ask for `TXT` or `SRV`. The `rawQuery(Network, byte[], ...)` overload accepts a
 * complete query packet, so [DnsMessageCodec] encodes the query and decodes the answer. The query
 * and the answer therefore travel through the platform resolver unchanged.
 *
 * This class is blocking, like every other [DnsClient]; callers run it off the main thread.
 *
 * Only available on API 29 and above. On older releases [query] reports
 * [DnsQueryStatus.UNSUPPORTED] and the chain moves on to the transports that do work there.
 */
internal class SystemDnsClient(
    private val connectivity: ConnectivityManager?,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : DnsClient {

    override fun query(name: String, type: DnsRecordType): DnsQueryResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return DnsQueryResult(
                status = DnsQueryStatus.UNSUPPORTED,
                provider = PROVIDER,
                error = "the platform resolver cannot query ${type.name} before API 29",
            )
        }

        val query = DnsMessageCodec.buildQuery(name, type)
            ?: return DnsQueryResult(
                status = DnsQueryStatus.MALFORMED,
                provider = PROVIDER,
                error = "the record name cannot be encoded as a DNS name",
            )
        val transactionId = DnsMessageCodec.transactionIdOf(query)
            ?: return DnsQueryResult(status = DnsQueryStatus.MALFORMED, provider = PROVIDER)

        // A null network means the platform cannot tell which network to use, which is the same
        // situation as being offline. Reporting it is more useful than a generic failure later.
        val network = connectivity?.activeNetwork
            ?: return DnsQueryResult(
                status = DnsQueryStatus.NETWORK_ERROR,
                provider = PROVIDER,
                error = "no active network to run the DNS query on",
            )

        return runQuery(network, query, transactionId)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun runQuery(
        network: android.net.Network,
        query: ByteArray,
        transactionId: Int,
    ): DnsQueryResult {
        val started = clock()
        val latch = CountDownLatch(1)
        val cancellation = CancellationSignal()
        val holder = AtomicReference<DnsQueryResult?>(null)

        val callback = object : DnsResolver.Callback<ByteArray> {
            // The platform annotates this parameter @NonNull, so the Kotlin signature has to be
            // non-null too. A resolver that has no payload for the query (any rcode other than
            // NOERROR) reports it as an empty array rather than a null, which interpret() handles.
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                holder.set(interpret(answer, rcode))
                latch.countDown()
            }

            override fun onError(error: DnsResolver.DnsException) {
                holder.set(
                    DnsQueryResult(
                        status = DnsQueryStatus.NETWORK_ERROR,
                        provider = PROVIDER,
                        error = "platform resolver error (code ${error.code})",
                    ),
                )
                latch.countDown()
            }
        }

        try {
            DnsResolver.getInstance().rawQuery(network, query, 0, EXECUTOR, cancellation, callback)
        } catch (e: IllegalArgumentException) {
            // Thrown for a query the platform considers malformed, for example an empty name.
            return DnsQueryResult(
                status = DnsQueryStatus.MALFORMED,
                provider = PROVIDER,
                error = e.describeForDiagnostics(),
            )
        } catch (e: Exception) {
            return DnsQueryResult(
                status = DnsQueryStatus.NETWORK_ERROR,
                provider = PROVIDER,
                error = e.describeForDiagnostics(),
            )
        }

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val duration = clock() - started
        if (!completed) {
            cancellation.cancel()
            return DnsQueryResult(
                status = DnsQueryStatus.TIMEOUT,
                provider = PROVIDER,
                error = "the platform resolver did not answer within ${timeoutMs}ms",
                attempts = listOf(DnsAttempt(PROVIDER, DnsQueryStatus.TIMEOUT, duration)),
            )
        }

        val result = holder.get()
            ?: DnsQueryResult(
                status = DnsQueryStatus.MALFORMED,
                provider = PROVIDER,
                error = "the platform resolver produced no answer",
            )
        return result.copy(
            provider = PROVIDER,
            attempts = listOf(DnsAttempt(PROVIDER, result.status, duration, result.error)),
            // The transaction id is only interesting when it does not match.
            canonicalNames = result.canonicalNames,
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun interpret(answer: ByteArray?, rcode: Int): DnsQueryResult {
        if (answer == null || answer.isEmpty()) {
            val status = when (rcode) {
                DnsResolver.ERROR_PARSE -> DnsQueryStatus.MALFORMED
                else -> DnsQueryStatus.NETWORK_ERROR
            }
            return DnsQueryResult(
                status = status,
                provider = PROVIDER,
                error = "the platform resolver returned no payload (rcode $rcode)",
            )
        }

        val parsed = DnsMessageCodec.parse(answer)
        return DnsQueryResult(
            answers = parsed.answers,
            status = parsed.status,
            provider = PROVIDER,
            error = parsed.error,
            canonicalNames = parsed.canonicalNames,
        )
    }

    companion object {
        const val PROVIDER = "system"

        /**
         * Long enough for a slow mobile network, short enough that the chain can still try a second
         * resolver inside its budget.
         */
        const val DEFAULT_TIMEOUT_MS = 4_000L

        /**
         * Daemon threads so an in-flight query can never keep the process alive.
         */
        private val EXECUTOR: Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ha-endpoint-dns").apply { isDaemon = true }
        }

        /** Builds the client for this device, or `null` where the API is not available. */
        fun create(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SystemDnsClient? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return SystemDnsClient(context.getSystemService<ConnectivityManager>(), timeoutMs)
        }
    }
}
