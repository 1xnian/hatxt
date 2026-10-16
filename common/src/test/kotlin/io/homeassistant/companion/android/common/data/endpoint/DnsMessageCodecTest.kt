package io.homeassistant.companion.android.common.data.endpoint

import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Coverage for the DNS wire format codec.
 *
 * The fixtures are **not** invented. `REAL_TXT_RESPONSE` and `REAL_QUERY` are the exact bytes
 * exchanged with `223.5.5.5` for the TXT record `hat.22345678.xyz` that the app failed to read, so
 * the decoder is checked against a real answer, real name compression and a real RCODE.
 * `REAL_A_RESPONSE` is the answer for the same name queried as `A`: it carries no answer record and
 * an SOA in the authority section, which the decoder must skip without failing.
 */
class DnsMessageCodecTest {

    /** Exactly what the resolver sent for `hat.22345678.xyz` type TXT, id 0x1234. */
    private val realQuery = "123401000001000000000000036861740832323334353637380378797a0000100001"

    /** Exactly what `223.5.5.5` answered: one TXT record, name compressed to offset 12. */
    private val realTxtResponse =
        "123481800001000100000000036861740832323334353637380378797a0000100001" +
            "c00c00100001000002580013123131372e3138332e352e32393a3133343831"

    /** The `A` answer for the same name: `ANCOUNT=0` plus an SOA in the authority section. */
    private val realAResponse =
        "123481800001000000010000036861740832323334353637380378797a0000010001" +
            "c01000060001000000b4004107663167316e733106646e73706f64036e6574000c6672656564" +
            "6e7361646d696e06646e73706f6403636f6d006ad1d81700000e10000000b4001275000000" +
            "00b4"

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private fun hex(data: ByteArray): String =
        data.joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------------------------------------
    // The encoder has to produce exactly what a real client produces.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the encoder reproduces the query a real resolver accepted`() {
        val built = DnsMessageCodec.buildQuery("hat.22345678.xyz", DnsRecordType.TXT, 0x1234)

        assertNotNull(built)
        assertEquals(realQuery, hex(built!!))
    }

    @Test
    fun `a query carries the requested type`() {
        val txt = DnsMessageCodec.buildQuery("ha.example.com", DnsRecordType.TXT, 1)!!
        val srv = DnsMessageCodec.buildQuery("ha.example.com", DnsRecordType.SRV, 1)!!

        assertEquals(DnsRecordType.TXT.code, questionTypeOf(txt))
        assertEquals(DnsRecordType.SRV.code, questionTypeOf(srv))
    }

    @Test
    fun `an unencodable name produces no query instead of throwing`() {
        assertNull(DnsMessageCodec.buildQuery("", DnsRecordType.TXT))
        assertNull(DnsMessageCodec.buildQuery("   ", DnsRecordType.TXT))
        // A label may not exceed 63 octets.
        assertNull(DnsMessageCodec.buildQuery("${"a".repeat(64)}.example.com", DnsRecordType.TXT))
        // And the whole name may not exceed 255 octets.
        assertNull(DnsMessageCodec.buildQuery(List(5) { "a".repeat(60) }.joinToString("."), DnsRecordType.TXT))
    }

    @Test
    fun `an internationalised name is punycode encoded`() {
        val built = DnsMessageCodec.buildQuery("münchen.example", DnsRecordType.TXT)!!
        // xn--mnchen-3ya is the A-label of münchen.
        assertTrue(String(built, Charsets.ISO_8859_1).contains("xn--mnchen-3ya"), hex(built))
    }

    // ---------------------------------------------------------------------------------------------
    // The decoder against the real answer.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the decoder reads the real TXT answer`() {
        val parsed = DnsMessageCodec.parse(bytes(realTxtResponse))

        assertEquals(0x1234, parsed.transactionId)
        assertEquals(DnsQueryStatus.NOERROR, parsed.status)
        assertFalse(parsed.truncated)
        assertEquals(1, parsed.answers.size)

        val answer = parsed.answers.single()
        assertEquals(DnsRecordType.TXT, answer.type)
        // Quoting removed, and this is the value the Home Assistant instance is reachable at.
        assertEquals("117.183.5.29:13481", answer.value)
        // The name came through a compression pointer and is fully expanded.
        assertEquals("hat.22345678.xyz", answer.name)
        assertEquals(600L, answer.ttlSeconds)
    }

    @Test
    fun `the decoder reports an empty answer set from the real A response`() {
        val parsed = DnsMessageCodec.parse(bytes(realAResponse))

        // ANCOUNT is 0 and the SOA lives in the authority section, which is skipped.
        assertEquals(DnsQueryStatus.EMPTY, parsed.status)
        assertTrue(parsed.answers.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Structural handling: multi part TXT values, compression, truncation, error codes.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a TXT value split across several character strings is concatenated`() {
        // Two character-strings: "117.183." and "5.29:13481" -> one value.
        val response = buildResponse(
            rcode = 0,
            answers = listOf(txtAnswer(listOf("117.183.", "5.29:13481"))),
        )

        val answer = DnsMessageCodec.parse(response).answers.single()

        assertEquals("117.183.5.29:13481", answer.value)
    }

    @Test
    fun `an SRV record is rendered as priority weight port target`() {
        val response = buildResponse(
            rcode = 0,
            answers = listOf(srvAnswer(priority = 10, weight = 60, port = 8123, target = "node1.example.com")),
        )

        val answer = DnsMessageCodec.parse(response).answers.single()

        assertEquals(DnsRecordType.SRV, answer.type)
        assertEquals("10 60 8123 node1.example.com", answer.value)
    }

    @Test
    fun `an A and an AAAA record are rendered as literals`() {
        val response = buildResponse(
            rcode = 0,
            answers = listOf(aAnswer(203, 0, 113, 10)),
        )
        assertEquals("203.0.113.10", DnsMessageCodec.parse(response).answers.single().value)

        val aaaa = ByteArray(16)
        aaaa[0] = 0x20
        aaaa[1] = 0x01
        aaaa[15] = 0x01
        val v6Response = buildResponse(rcode = 0, answers = listOf(rr(28, aaaa)))
        assertEquals("2001:0000:0000:0000:0000:0000:0000:0001", DnsMessageCodec.parse(v6Response).answers.single().value)
    }

    @Test
    fun `a cname is reported as an alias and not as a value`() {
        val response = buildResponse(rcode = 0, answers = listOf(cnameAnswer("home.example.com")))

        val parsed = DnsMessageCodec.parse(response)

        assertTrue(parsed.answers.isEmpty())
        assertEquals(DnsQueryStatus.EMPTY, parsed.status)
        assertEquals(listOf("home.example.com"), parsed.canonicalNames)
    }

    @Test
    fun `error codes are mapped and never reported as success`() {
        assertEquals(DnsQueryStatus.NXDOMAIN, DnsMessageCodec.parse(buildResponse(rcode = 3)).status)
        assertEquals(DnsQueryStatus.SERVER_FAILURE, DnsMessageCodec.parse(buildResponse(rcode = 2)).status)
        assertEquals(DnsQueryStatus.REFUSED, DnsMessageCodec.parse(buildResponse(rcode = 5)).status)
        assertEquals(DnsQueryStatus.MALFORMED, DnsMessageCodec.parse(buildResponse(rcode = 1)).status)
    }

    @Test
    fun `the truncation bit is reported`() {
        val response = buildResponse(rcode = 0, answers = listOf(txtAnswer(listOf("a"))), truncated = true)
        assertTrue(DnsMessageCodec.parse(response).truncated)
    }

    @Test
    fun `a message that is not a response is rejected`() {
        val query = DnsMessageCodec.buildQuery("ha.example.com", DnsRecordType.TXT, 7)!!
        val parsed = DnsMessageCodec.parse(query)

        assertEquals(DnsQueryStatus.MALFORMED, parsed.status)
        assertNotNull(parsed.error)
    }

    @Test
    fun `malformed input never throws`() {
        val cases = listOf(
            ByteArray(0),
            ByteArray(5),
            // Header promises one answer, but there is no question and no answer after it.
            bytes("1234818000010001"),
            // A complete question name without the four bytes of type and class.
            bytes("12348180000100010368617400"),
            // A compression pointer whose second byte is missing.
            bytes("1234818000010001c0"),
            // A name pointing at itself can never terminate.
            bytes("1234818000010001c00c"),
        )

        cases.forEach { case ->
            val parsed = DnsMessageCodec.parse(case)
            assertEquals(DnsQueryStatus.MALFORMED, parsed.status, hex(case))
            assertNotNull(parsed.error, hex(case))
        }
    }

    @Test
    fun `a pointer that does not point backwards is rejected`() {
        val message = buildResponse(rcode = 0, answers = listOf(txtAnswer(listOf("ignored"))))
        val answerOffset = 12 + encodeName("ha.example.com").size + 4
        // Turn the answer's compressed name into a pointer to its own offset.
        message[answerOffset] = 0xC0.toByte()
        message[answerOffset + 1] = answerOffset.toByte()

        assertEquals(DnsQueryStatus.MALFORMED, DnsMessageCodec.parse(message).status)
    }

    @Test
    fun `the transaction id of a message is readable`() {
        assertEquals(0x1234, DnsMessageCodec.transactionIdOf(bytes(realQuery)))
        assertNull(DnsMessageCodec.transactionIdOf(ByteArray(1)))
        assertTrue(DnsMessageCodec.newTransactionId() in 0..0xFFFF)
    }

    private fun questionTypeOf(query: ByteArray): Int =
        ((query[query.size - 4].toInt() and 0xFF) shl 8) or (query[query.size - 3].toInt() and 0xFF)

    // ---------------------------------------------------------------------------------------------
    // Fixture builders.
    // ---------------------------------------------------------------------------------------------

    private fun buildResponse(rcode: Int, answers: List<ByteArray> = emptyList(), truncated: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeShort(0x1234)
        out.writeShort(0x8180 or rcode or (if (truncated) 0x0200 else 0))
        out.writeShort(1)
        out.writeShort(answers.size)
        out.writeShort(0)
        out.writeShort(0)
        out.write(encodeName("ha.example.com"))
        out.writeShort(16)
        out.writeShort(1)
        answers.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.UTF_8))
        }
        out.write(0)
        return out.toByteArray()
    }

    /** A resource record with the name compressed to the question at offset 12. */
    private fun rr(type: Int, rdata: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeShort(0xC00C)
        out.writeShort(type)
        out.writeShort(1)
        out.write(byteArrayOf(0, 0, 0, 60))
        out.writeShort(rdata.size)
        out.write(rdata)
        return out.toByteArray()
    }

    private fun txtAnswer(chunks: List<String>): ByteArray {
        val rdata = ByteArrayOutputStream()
        chunks.forEach { chunk ->
            rdata.write(chunk.length)
            rdata.write(chunk.toByteArray(Charsets.UTF_8))
        }
        return rr(16, rdata.toByteArray())
    }

    private fun cnameAnswer(target: String): ByteArray = rr(5, encodeName(target))

    private fun srvAnswer(priority: Int, weight: Int, port: Int, target: String): ByteArray {
        val rdata = ByteArrayOutputStream()
        rdata.writeShort(priority)
        rdata.writeShort(weight)
        rdata.writeShort(port)
        rdata.write(encodeName(target))
        return rr(33, rdata.toByteArray())
    }

    private fun aAnswer(a: Int, b: Int, c: Int, d: Int): ByteArray = rr(1, byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }
}
