package io.homeassistant.companion.android.common.data.endpoint

import java.net.InetSocketAddress

/**
 * Asks the **authoritative nameservers of the zone that owns the record** directly.
 *
 * Every other resolver in this package eventually reads a cache: the device resolver and the public
 * resolvers are all recursive resolvers, and a recursive resolver answers from its cache until the
 * record's TTL expires. That is why a record that just changed still reports the old value for
 * minutes, and why the provider matters: a record hosted at one provider is only guaranteed to be
 * current at *that* provider's authoritative servers until every other cache has expired.
 *
 * The authoritative servers hold the current value the moment it is published, so asking them is the
 * only way to see a change immediately. They are found through `NS`, which is exactly what a
 * recursive resolver would do, except that the answer is then used to talk to the source instead of
 * to a cache.
 *
 * The `NS` lookup and the address of the nameservers themselves go through [bootstrap]: both are
 * long-lived and change rarely, so reading them from a cache is correct and cheap.
 */
internal class AuthoritativeDnsClient(
    private val bootstrap: DnsClient,
    private val timeoutMs: Int = RawUdpDnsClient.DEFAULT_TIMEOUT_MS,
) : DnsClient {

    override fun query(name: String, type: DnsRecordType): DnsQueryResult {
        val target = name.trim().trimEnd('.')
        if (!target.contains('.')) {
            return DnsQueryResult(
                status = DnsQueryStatus.MALFORMED,
                provider = PROVIDER,
                error = "not a DNS name that belongs to a zone: $name",
            )
        }

        val zone = findZone(target) ?: return DnsQueryResult(
            status = DnsQueryStatus.NETWORK_ERROR,
            provider = PROVIDER,
            error = "no authoritative nameserver could be found for $target",
        )

        val servers = resolveServers(zone)
        if (servers.isEmpty()) {
            return DnsQueryResult(
                status = DnsQueryStatus.NETWORK_ERROR,
                provider = PROVIDER,
                error = "no authoritative nameserver of $zone could be resolved to an address",
            )
        }

        val client = RawUdpDnsClient(
            serverProvider = DnsServerProvider { servers },
            timeoutMs = timeoutMs,
        )
        return client.query(target, type).copy(provider = "$PROVIDER($zone)")
    }

    /**
     * Walks the name from the left until a parent answers with `NS`.
     *
     * `_ha.example.com` has no `NS` of its own while `example.com` does, so the zone that actually
     * owns the record is the shortest parent that publishes nameservers. The last label is never
     * tried on its own: a TLD is not the zone a user publishes a record in.
     */
    private fun findZone(name: String): String? {
        val labels = name.split('.')
        for (start in 0..labels.size - 2) {
            val candidate = labels.subList(start, labels.size).joinToString(".")
            val result = bootstrap.query(candidate, DnsRecordType.NS)
            if (result.isSuccess && result.answers.any { it.type == DnsRecordType.NS }) {
                return candidate
            }
        }
        return null
    }

    /** Turns the `NS` targets of [zone] into addresses that can be queried over UDP. */
    private fun resolveServers(zone: String): List<InetSocketAddress> {
        val hosts = bootstrap.query(zone, DnsRecordType.NS).answers
            .filter { it.type == DnsRecordType.NS }
            .map { it.value.trim().trimEnd('.') }
            .filter { it.isNotEmpty() }
            .distinct()

        val servers = LinkedHashSet<InetSocketAddress>()
        hosts.forEach { host ->
            if (EndpointUrlFactory.isIpLiteral(host)) {
                addServer(servers, host)
                return@forEach
            }
            listOf(DnsRecordType.A, DnsRecordType.AAAA).forEach { type ->
                val result = bootstrap.query(host, type)
                if (!result.isSuccess) return@forEach
                result.answers.forEach { answer ->
                    val value = answer.value.trim().removeSurrounding("[", "]")
                    val usable = when (type) {
                        DnsRecordType.A -> EndpointUrlFactory.isValidIpv4Literal(value)
                        DnsRecordType.AAAA -> EndpointUrlFactory.isValidIpv6Literal(value)
                        else -> false
                    }
                    if (usable) addServer(servers, value)
                }
            }
        }
        return servers.toList()
    }

    private fun addServer(servers: MutableSet<InetSocketAddress>, address: String) {
        runCatching { InetSocketAddress(address, DNS_PORT) }.onSuccess { servers += it }
    }

    companion object {
        const val PROVIDER = "authoritative"
        private const val DNS_PORT = 53
    }
}
