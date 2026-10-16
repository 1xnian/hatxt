package io.homeassistant.companion.android.common.data.endpoint

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Supplies the DNS servers the device is currently using.
 *
 * Behind an interface so the transport can be driven with explicit servers, including from a plain
 * JVM test that performs a real query against a real resolver.
 */
fun interface DnsServerProvider {
    fun servers(): List<InetSocketAddress>
}

/**
 * [DnsClient] that speaks DNS directly over UDP, with the servers the device is configured with.
 *
 * ## Why this transport is needed in addition to the platform resolver
 *
 * `android.net.DnsResolver` only exists from API 29. This app supports API 23, and `TXT`/`SRV`
 * queries have to work there too. `LinkProperties.dnsServers` is public API on every supported
 * release, so the addresses of the resolvers are available everywhere and a plain UDP query reaches
 * the same servers the system resolver would use.
 *
 * ## Correctness details
 *
 * * A response is only accepted when its transaction id matches the query, so a stray or spoofed
 *   datagram cannot be mistaken for an answer.
 * * A response with the `TC` (truncated) bit set is retried once over TCP, which is what RFC 1035
 *   prescribes. A record set larger than the UDP payload would otherwise be silently cut short.
 * * Every server is tried in order and each attempt is recorded, so a network that blocks port 53
 *   produces an explicit "unreachable" verdict instead of a generic failure.
 */
internal class RawUdpDnsClient(
    private val serverProvider: DnsServerProvider,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : DnsClient {

    override fun query(name: String, type: DnsRecordType): DnsQueryResult {
        val query = DnsMessageCodec.buildQuery(name, type)
            ?: return DnsQueryResult(
                status = DnsQueryStatus.MALFORMED,
                provider = PROVIDER,
                error = "the record name cannot be encoded as a DNS name",
            )
        val transactionId = DnsMessageCodec.transactionIdOf(query) ?: return DnsQueryResult(
            status = DnsQueryStatus.MALFORMED,
            provider = PROVIDER,
        )

        val servers = serverProvider.servers()
        if (servers.isEmpty()) {
            return DnsQueryResult(
                status = DnsQueryStatus.NETWORK_ERROR,
                provider = PROVIDER,
                error = "the device reports no DNS server",
            )
        }

        val attempts = mutableListOf<DnsAttempt>()
        var best: DnsQueryResult? = null
        var bestRank = Int.MIN_VALUE

        servers.forEach { server ->
            val started = clock()
            val outcome = try {
                val message = exchange(server, query, transactionId)
                DnsQueryResult(
                    answers = message.answers,
                    status = message.status,
                    error = message.error,
                    canonicalNames = message.canonicalNames,
                )
            } catch (e: SocketTimeoutException) {
                DnsQueryResult(status = DnsQueryStatus.TIMEOUT, error = "no answer within ${timeoutMs}ms")
            } catch (e: IOException) {
                DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR, error = e.describeForDiagnostics())
            } catch (e: Exception) {
                DnsQueryResult(status = DnsQueryStatus.NETWORK_ERROR, error = e.describeForDiagnostics())
            }
            val duration = clock() - started
            attempts += DnsAttempt(describeServer(server), outcome.status, duration, outcome.error)

            if (outcome.status == DnsQueryStatus.NOERROR) {
                return outcome.copy(
                    provider = describeServer(server),
                    attempts = attempts.toList(),
                )
            }

            val rank = rankOf(outcome.status)
            if (rank > bestRank) {
                bestRank = rank
                best = outcome.copy(provider = describeServer(server), attempts = attempts.toList())
            }
        }

        return (best ?: DnsQueryResult(
            status = DnsQueryStatus.NETWORK_ERROR,
            provider = PROVIDER,
            error = "no DNS server answered",
        )).copy(attempts = attempts.toList())
    }

    /** One UDP exchange, retried over TCP when the resolver reports a truncated answer. */
    private fun exchange(
        server: InetSocketAddress,
        query: ByteArray,
        transactionId: Int,
    ): ParsedDnsMessage {
        val udpResponse = exchangeUdp(server, query, transactionId)
        if (!udpResponse.truncated) return udpResponse
        return exchangeTcp(server, query)
    }

    private fun exchangeUdp(
        server: InetSocketAddress,
        query: ByteArray,
        transactionId: Int,
    ): ParsedDnsMessage {
        DatagramSocket().use { socket ->
            socket.send(DatagramPacket(query, query.size, server))
            val buffer = ByteArray(MAX_UDP_RESPONSE)
            val deadline = clock() + timeoutMs

            while (true) {
                val remaining = deadline - clock()
                if (remaining <= 0) throw SocketTimeoutException("DNS lookup timed out")
                socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)

                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val response = packet.data.copyOf(packet.length)
                // Only the answer to *this* question is accepted.
                if (DnsMessageCodec.transactionIdOf(response) != transactionId) continue
                return DnsMessageCodec.parse(response)
            }
        }
    }

    private fun exchangeTcp(
        server: InetSocketAddress,
        query: ByteArray,
    ): ParsedDnsMessage {
        Socket().use { socket ->
            socket.connect(server, timeoutMs)
            socket.soTimeout = timeoutMs

            val output = DataOutputStream(socket.getOutputStream())
            // DNS over TCP prefixes every message with its length, see RFC 1035 §4.2.2.
            output.writeShort(query.size)
            output.write(query)
            output.flush()

            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            if (length <= 0) return ParsedDnsMessage.malformed("empty TCP response")
            val body = ByteArray(length)
            input.readFully(body)
            return DnsMessageCodec.parse(body)
        }
    }

    /** Same ordering as the fallback chain: report the most actionable outcome. */
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
        const val PROVIDER = "udp"

        /** A local resolver answers in a few milliseconds; this is already a generous ceiling. */
        const val DEFAULT_TIMEOUT_MS = 3_000

        /**
         * Conventional DNS payload size. The response buffer is larger than the EDNS0 default so a
         * full TXT answer set fits without being truncated.
         */
        const val MAX_UDP_RESPONSE = 4_096

        /** `1.1.1.1:53`, without leaking anything: DNS server addresses are not sensitive. */
        internal fun describeServer(server: InetSocketAddress): String {
            val host = server.address?.hostAddress ?: server.hostString ?: "unknown"
            return if (host.contains(':')) "[$host]:${server.port}" else "$host:${server.port}"
        }
    }
}

/**
 * [DnsServerProvider] reading the DNS servers of every network the device knows about.
 *
 * The active network is listed first because it is the one that will carry the query. Servers of
 * other networks are kept as a fallback: on a dual-SIM or VPN setup the active network's resolver
 * can be the one that is blocked while another one answers.
 */
internal class AndroidDnsServerProvider(
    private val context: Context,
) : DnsServerProvider {

    override fun servers(): List<InetSocketAddress> {
        val manager = context.getSystemService<ConnectivityManager>() ?: return emptyList()

        val active = manager.activeNetwork
        val ordered = listOfNotNull(
            active,
            *manager.allNetworks.filter { it != active }.toTypedArray(),
        )

        val servers = LinkedHashSet<InetSocketAddress>()
        ordered.forEach { network ->
            manager.getLinkProperties(network)
                ?.dnsServers
                ?.filterNotNull()
                ?.forEach { address -> servers += InetSocketAddress(address, DNS_PORT) }
        }
        return servers.toList()
    }

    private companion object {
        const val DNS_PORT = 53
    }
}
