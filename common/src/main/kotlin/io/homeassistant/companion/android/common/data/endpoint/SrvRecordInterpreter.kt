package io.homeassistant.companion.android.common.data.endpoint

/**
 * A single SRV resource record, see RFC 2782.
 *
 * @property priority lower values are tried first.
 * @property weight relative weight inside the same [priority].
 * @property port port of the target service. Never 0 for a usable record.
 * @property target host name of the machine providing the service, with a trailing root dot
 *   removed. The SRV query name itself is *not* the target and must never be used as the URL host.
 * @property sourceRecord raw record text, kept for diagnostics.
 */
data class SrvRecord(
    val priority: Int,
    val weight: Int,
    val port: Int,
    val target: String,
    val sourceRecord: String? = null,
)

/** Result of parsing SRV answers. */
data class SrvParseResult(
    val records: List<SrvRecord>,
    val rejected: List<RejectedRecord> = emptyList(),
    /**
     * True when a record carries the RFC 2782 "service decidedly not available" target `"."`.
     * In that case no URL may be built at all, even if other records look usable.
     */
    val serviceUnavailable: Boolean = false,
) {
    val isUsable: Boolean get() = !serviceUnavailable && records.isNotEmpty()
}

/**
 * Parses the RDATA of SRV records as returned by a DNS resolver:
 *
 * ```
 * _8123._tcp.ha.example.com. 300 IN SRV 10 60 8123 ha.example.com.
 * ```
 *
 * Only the `priority weight port target` part is parsed here, the resolver supplies it.
 */
object SrvRecordInterpreter {

    /** Sentinel target defined by RFC 2782 meaning "the service is decidedly not available". */
    const val TARGET_NOT_AVAILABLE = "."

    fun parse(answers: List<DnsAnswer>): SrvParseResult {
        val records = mutableListOf<SrvRecord>()
        val rejected = mutableListOf<RejectedRecord>()
        var serviceUnavailable = false

        answers.filter { it.type == DnsRecordType.SRV }.forEach { answer ->
            val raw = answer.value.trim()
            val tokens = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.size < 4) {
                rejected += RejectedRecord(raw, EndpointRejectionReason.UNPARSEABLE_RECORD, "expected 4 fields")
                return@forEach
            }

            val priority = tokens[0].toIntOrNull()
            val weight = tokens[1].toIntOrNull()
            val port = tokens[2].toIntOrNull()
            val rawTarget = tokens[3]

            if (rawTarget == TARGET_NOT_AVAILABLE) {
                // RFC 2782: a target of "." means the service is decidedly not available.
                serviceUnavailable = true
                rejected += RejectedRecord(raw, EndpointRejectionReason.SRV_SERVICE_UNAVAILABLE, rawTarget)
                return@forEach
            }

            if (priority == null || priority !in 0..65535) {
                rejected += RejectedRecord(raw, EndpointRejectionReason.UNPARSEABLE_RECORD, "invalid priority")
                return@forEach
            }
            if (weight == null || weight !in 0..65535) {
                rejected += RejectedRecord(raw, EndpointRejectionReason.UNPARSEABLE_RECORD, "invalid weight")
                return@forEach
            }
            if (port == null || !EndpointUrlFactory.isValidPort(port)) {
                rejected += RejectedRecord(raw, EndpointRejectionReason.INVALID_PORT, tokens[2])
                return@forEach
            }

            val target = EndpointUrlFactory.normalizeHost(rawTarget).trimEnd('.')
            if (target.isEmpty() || !EndpointUrlFactory.isValidHost(target)) {
                rejected += RejectedRecord(
                    raw,
                    EndpointUrlFactory.classifyInvalidHost(target),
                    target,
                )
                return@forEach
            }

            records += SrvRecord(priority, weight, port, target, raw)
        }

        return SrvParseResult(records, rejected, serviceUnavailable)
    }

    /**
     * Converts the selected SRV record into an [EndpointCandidate].
     *
     * The SRV query name is never used as the host: the candidate host is always the record target,
     * which is then resolved to A/AAAA by the resolver.
     */
    fun toCandidate(
        record: SrvRecord,
        scheme: EndpointScheme = EndpointScheme.HTTPS,
    ): EndpointCandidate = EndpointCandidate(
        host = EndpointUrlFactory.normalizeHost(record.target),
        port = record.port,
        scheme = scheme,
        sourceRecord = record.sourceRecord,
    )
}

/**
 * Selects which SRV record to use.
 *
 * Two strategies are provided on purpose:
 *
 * * [selectByWeight] implements the RFC 2782 weighted random choice and is what the app uses at
 *   runtime. Load is spread across the records of the lowest priority.
 * * [selectDeterministically] is a stable, reproducible strategy used by unit tests and by
 *   diagnostics. It keeps the behaviour predictable, which the spec explicitly requires when a
 *   simpler strategy is chosen.
 */
object SrvRecordSelector {

    /** RFC 2782 ordering: ascending priority, weights only decide within one priority. */
    fun orderCandidates(records: List<SrvRecord>): List<SrvRecord> =
        records.sortedWith(compareBy({ it.priority }, { -it.weight }, { it.target }))

    /** Lowest priority value present, or `null` for an empty list. */
    fun lowestPriority(records: List<SrvRecord>): Int? = records.minOfOrNull { it.priority }

    /**
     * RFC 2782 weighted random selection inside the lowest priority group.
     *
     * When the total weight of the group is 0 the selection is uniformly random, matching the RFC
     * recommendation ("a random choice ... when all weights are zero").
     */
    fun selectByWeight(records: List<SrvRecord>, random: kotlin.random.Random = kotlin.random.Random): SrvRecord? {
        val priority = lowestPriority(records) ?: return null
        val group = records.filter { it.priority == priority }
        if (group.isEmpty()) return null

        val totalWeight = group.sumOf { it.weight }
        if (totalWeight <= 0) return group[random.nextInt(group.size)]

        val pick = random.nextInt(totalWeight)
        var running = 0
        group.forEach { record ->
            running += record.weight
            if (pick < running) return record
        }
        return group.last()
    }

    /**
     * Stable alternative to [selectByWeight]: lowest priority first, then highest weight, then the
     * lexicographically smallest target. Identical input always yields the same record.
     */
    fun selectDeterministically(records: List<SrvRecord>): SrvRecord? = orderCandidates(records).firstOrNull()
}
