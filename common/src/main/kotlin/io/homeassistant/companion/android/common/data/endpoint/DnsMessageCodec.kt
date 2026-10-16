package io.homeassistant.companion.android.common.data.endpoint

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal DNS wire-format codec (RFC 1035), used by the transports that talk to DNS directly.
 *
 * ## Why the wire format at all
 *
 * The platform DNS APIs cannot express `TXT` or `SRV` queries:
 *
 * * `InetAddress` / OkHttp's `Dns` only resolve `A` and `AAAA`.
 * * `android.net.DnsResolver` (API 29+) exposes `TYPE_A`, `TYPE_AAAA` and `TYPE_HTTPS` constants
 *   only. Its type-tagged `rawQuery` overload therefore cannot be used for `TXT`/`SRV` either.
 * * `DnsResolver.rawQuery(network, byte[], ...)` does accept a **pre-built query packet**, so a
 *   caller that can encode its own query and decode the response gets full type support *and* keeps
 *   using the DNS servers the device is actually configured with.
 *
 * That is exactly what this codec provides. It is deliberately free of Android dependencies so the
 * encoding and decoding rules can be tested on a plain JVM against captured responses.
 *
 * ## What the decoder guarantees
 *
 * * **Name compression** (RFC 1035 §4.1.4) is followed, including pointers into the middle of a
 *   label sequence, with a jump budget so a self-referential message cannot hang the caller.
 * * **TXT records** are reassembled from their character-strings. RFC 1035 allows a single logical
 *   value to be split across several strings, and the strings are concatenated **without** a
 *   separator, which is how such a value has to be put back together.
 * * **SRV records** are rendered as `priority weight port target`, the shape
 *   [SrvRecordInterpreter] expects.
 * * Anything malformed is reported as [DnsQueryStatus.MALFORMED] with a reason instead of throwing.
 */
object DnsMessageCodec {

    /** DNS class `IN`, the only class used for these queries. */
    const val CLASS_IN = 1

    const val HEADER_SIZE = 12

    /** Bits of the header flags word. */
    private const val FLAG_RESPONSE = 0x8000
    private const val FLAG_RECURSION_DESIRED = 0x0100
    private const val FLAG_TRUNCATED = 0x0200

    /** Mask and value identifying a length byte that is really a compression pointer. */
    private const val LABEL_POINTER_MASK = 0xC0
    private const val LABEL_POINTER_PREFIX = 0xC0
    private const val LABEL_POINTER_OFFSET_MASK = 0x3F

    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_NAME_LENGTH = 255

    /**
     * Upper bound for compression pointer hops. A legitimate answer needs at most a handful, so a
     * small budget rejects pointer loops without affecting real messages.
     */
    private const val MAX_POINTER_JUMPS = 32

    private val transactionIds = AtomicInteger(0)

    /** Next transaction id. Wraps inside the 16 bit field and never returns a negative value. */
    fun newTransactionId(): Int = transactionIds.incrementAndGet() and 0xFFFF

    /**
     * Encodes a standard recursive query.
     *
     * @return the packet, or `null` when [name] cannot be represented as a DNS name.
     */
    fun buildQuery(name: String, type: DnsRecordType, transactionId: Int = newTransactionId()): ByteArray? {
        val encodedName = encodeName(name) ?: return null
        val out = ByteArrayOutputStream(HEADER_SIZE + encodedName.size + 4)
        out.writeShort(transactionId and 0xFFFF)
        out.writeShort(FLAG_RECURSION_DESIRED)
        out.writeShort(1) // QDCOUNT
        out.writeShort(0) // ANCOUNT
        out.writeShort(0) // NSCOUNT
        out.writeShort(0) // ARCOUNT
        out.write(encodedName)
        out.writeShort(type.code)
        out.writeShort(CLASS_IN)
        return out.toByteArray()
    }

    /** Transaction id of a DNS message, or `null` when [message] is too short to carry one. */
    fun transactionIdOf(message: ByteArray): Int? {
        if (message.size < 2) return null
        return readU16(message, 0)
    }

    /**
     * Encodes a name as a sequence of length-prefixed labels terminated by a zero byte.
     *
     * Internationalised names are converted to their A-label (punycode) form first, which is what
     * the DNS actually stores.
     */
    fun encodeName(name: String): ByteArray? {
        val normalized = name.trim().trimEnd('.').trim()
        if (normalized.isEmpty()) return null

        val ascii = try {
            IDN.toASCII(normalized, IDN.ALLOW_UNASSIGNED)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (ascii.length > MAX_NAME_LENGTH) return null

        val out = ByteArrayOutputStream(ascii.length + 2)
        ascii.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > MAX_LABEL_LENGTH) return null
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * Decodes a DNS response.
     *
     * Only the answer section is interpreted. Authority and additional sections are skipped: the
     * resolver resolves `A`/`AAAA` for a target host explicitly, so glue records are not needed.
     */
    fun parse(message: ByteArray): ParsedDnsMessage {
        if (message.size < HEADER_SIZE) {
            return ParsedDnsMessage.malformed("response is shorter than a DNS header")
        }

        val transactionId = readU16(message, 0)
        val flags = readU16(message, 2)

        if (flags and FLAG_RESPONSE == 0) {
            return ParsedDnsMessage.malformed("message is a DNS query, not a response", transactionId)
        }

        val truncated = flags and FLAG_TRUNCATED != 0
        val status = statusOf(flags and 0x000F)
        val questionCount = readU16(message, 4)
        val answerCount = readU16(message, 6)

        var offset = HEADER_SIZE
        repeat(questionCount) {
            val question = readName(message, offset)
                ?: return ParsedDnsMessage.malformed("malformed question section", transactionId)
            offset = question.endOffset + 4
            if (offset > message.size) {
                return ParsedDnsMessage.malformed("question section runs past the message", transactionId)
            }
        }

        if (status != DnsQueryStatus.NOERROR) {
            return ParsedDnsMessage(
                transactionId = transactionId,
                status = status,
                answers = emptyList(),
                truncated = truncated,
            )
        }

        val answers = mutableListOf<DnsAnswer>()
        val canonicalNames = mutableListOf<String>()

        repeat(answerCount) {
            val name = readName(message, offset)
                ?: return ParsedDnsMessage.malformed("malformed answer name", transactionId)
            offset = name.endOffset
            if (offset + 10 > message.size) {
                return ParsedDnsMessage.malformed("answer header runs past the message", transactionId)
            }

            val type = DnsRecordType.fromCode(readU16(message, offset))
            val ttl = readU32(message, offset + 4)
            val dataLength = readU16(message, offset + 8)
            val dataOffset = offset + 10
            if (dataOffset + dataLength > message.size) {
                return ParsedDnsMessage.malformed("answer data runs past the message", transactionId)
            }
            offset = dataOffset + dataLength

            when (type) {
                DnsRecordType.A -> readIpv4(message, dataOffset, dataLength)
                    ?.let { answers += DnsAnswer(type, it, name.value, ttl) }

                DnsRecordType.AAAA -> readIpv6(message, dataOffset, dataLength)
                    ?.let { answers += DnsAnswer(type, it, name.value, ttl) }

                DnsRecordType.TXT -> readTxt(message, dataOffset, dataLength)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { answers += DnsAnswer(type, it, name.value, ttl) }

                DnsRecordType.SRV -> readSrv(message, dataOffset, dataLength)
                    ?.let { answers += DnsAnswer(type, it, name.value, ttl) }

                DnsRecordType.CNAME -> readName(message, dataOffset)
                    ?.value
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { canonicalNames += it }

                null -> Unit // Unknown types are skipped, they cannot influence a URL.
            }
        }

        return ParsedDnsMessage(
            transactionId = transactionId,
            status = if (answers.isEmpty()) DnsQueryStatus.EMPTY else DnsQueryStatus.NOERROR,
            answers = answers,
            canonicalNames = canonicalNames,
            truncated = truncated,
        )
    }

    /** Maps an RCODE to a status. Anything unrecognised is a server failure, never a success. */
    private fun statusOf(rcode: Int): DnsQueryStatus = when (rcode) {
        0 -> DnsQueryStatus.NOERROR
        1 -> DnsQueryStatus.MALFORMED // FORMERR
        2 -> DnsQueryStatus.SERVER_FAILURE // SERVFAIL
        3 -> DnsQueryStatus.NXDOMAIN
        5 -> DnsQueryStatus.REFUSED
        else -> DnsQueryStatus.SERVER_FAILURE
    }

    private class ReadName(val value: String, val endOffset: Int)

    /**
     * Reads a possibly compressed name starting at [start].
     *
     * @return the name and the offset just past the name in the original stream, or `null` when the
     *   encoding is broken.
     */
    private fun readName(message: ByteArray, start: Int): ReadName? {
        val labels = mutableListOf<String>()
        var offset = start
        var endOffset = -1
        var jumps = 0

        while (true) {
            if (offset >= message.size) return null
            val length = message[offset].toInt() and 0xFF

            when {
                length == 0 -> {
                    val end = if (endOffset >= 0) endOffset else offset + 1
                    return ReadName(labels.joinToString("."), end)
                }

                length and LABEL_POINTER_MASK == LABEL_POINTER_PREFIX -> {
                    if (offset + 1 >= message.size) return null
                    val pointer = ((length and LABEL_POINTER_OFFSET_MASK) shl 8) or
                        (message[offset + 1].toInt() and 0xFF)
                    if (endOffset < 0) endOffset = offset + 2
                    // A pointer must go backwards and must not point at itself.
                    if (pointer >= message.size || pointer >= offset) return null
                    jumps += 1
                    if (jumps > MAX_POINTER_JUMPS) return null
                    offset = pointer
                }

                length and LABEL_POINTER_MASK != 0 -> return null // reserved label type

                else -> {
                    if (offset + 1 + length > message.size) return null
                    labels += String(message, offset + 1, length, Charsets.ISO_8859_1)
                    offset += 1 + length
                }
            }
        }
    }

    /**
     * Reassembles a TXT record.
     *
     * RDATA is a sequence of `<length, bytes>` character-strings. They are concatenated **without a
     * separator** because that is how a single value split across strings has to be put back
     * together. (The DoH JSON API instead joins the pieces with a space; the TXT interpreter
     * tolerates both shapes.)
     */
    private fun readTxt(message: ByteArray, offset: Int, length: Int): String? {
        val end = offset + length
        if (end > message.size) return null

        val payload = ByteArrayOutputStream(length)
        var cursor = offset
        while (cursor < end) {
            val chunkLength = message[cursor].toInt() and 0xFF
            cursor += 1
            if (cursor + chunkLength > end) return null
            payload.write(message, cursor, chunkLength)
            cursor += chunkLength
        }
        return payload.toByteArray().toString(Charsets.UTF_8)
    }

    /** Renders SRV RDATA as `priority weight port target`, the shape the SRV interpreter parses. */
    private fun readSrv(message: ByteArray, offset: Int, length: Int): String? {
        if (length < 7 || offset + 6 > message.size) return null
        val priority = readU16(message, offset)
        val weight = readU16(message, offset + 2)
        val port = readU16(message, offset + 4)
        val target = readName(message, offset + 6)?.value.orEmpty()
        // RFC 2782 uses a single "." for "service decidedly not available".
        return "$priority $weight $port ${target.ifEmpty { "." }}"
    }

    private fun readIpv4(message: ByteArray, offset: Int, length: Int): String? {
        if (length != 4 || offset + 4 > message.size) return null
        return (0 until 4).joinToString(".") { (message[offset + it].toInt() and 0xFF).toString() }
    }

    private fun readIpv6(message: ByteArray, offset: Int, length: Int): String? {
        if (length != 16 || offset + 16 > message.size) return null
        return (0 until 8).joinToString(":") { group ->
            val high = message[offset + group * 2].toInt() and 0xFF
            val low = message[offset + group * 2 + 1].toInt() and 0xFF
            ((high shl 8) or low).toString(16).padStart(4, '0')
        }
    }

    private fun readU16(message: ByteArray, offset: Int): Int =
        ((message[offset].toInt() and 0xFF) shl 8) or (message[offset + 1].toInt() and 0xFF)

    private fun readU32(message: ByteArray, offset: Int): Long =
        ((message[offset].toLong() and 0xFF) shl 24) or
            ((message[offset + 1].toLong() and 0xFF) shl 16) or
            ((message[offset + 2].toLong() and 0xFF) shl 8) or
            (message[offset + 3].toLong() and 0xFF)

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }
}

/**
 * Result of decoding a DNS response.
 *
 * @property status coarse outcome. [DnsQueryStatus.EMPTY] means the resolver answered but the answer
 *   section carried nothing usable.
 * @property answers decoded records, of every type that carried a value.
 * @property canonicalNames targets of any `CNAME` in the answer, used to follow an alias once.
 * @property truncated the `TC` bit was set, meaning the response does not fit in the transport.
 */
data class ParsedDnsMessage(
    val transactionId: Int = -1,
    val status: DnsQueryStatus = DnsQueryStatus.MALFORMED,
    val answers: List<DnsAnswer> = emptyList(),
    val canonicalNames: List<String> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
) {
    companion object {
        fun malformed(reason: String, transactionId: Int = -1) = ParsedDnsMessage(
            transactionId = transactionId,
            status = DnsQueryStatus.MALFORMED,
            error = reason,
        )
    }
}
